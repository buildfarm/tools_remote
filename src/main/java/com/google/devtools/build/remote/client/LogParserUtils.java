// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.remote.client;

import static java.nio.charset.StandardCharsets.UTF_8;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.ExecuteOperationMetadata;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.LogEntry;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.RpcCallDetails;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.V1WatchDetails;
import com.google.devtools.build.remote.client.RemoteClientOptions.PrintLogCommand;
import com.google.longrunning.Operation;
import com.google.longrunning.Operation.ResultCase;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import com.google.watcher.v1.Change;
import com.google.watcher.v1.Change.State;
import com.google.watcher.v1.ChangeBatch;
import io.grpc.Status.Code;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Methods for printing log files. */
public class LogParserUtils {

  private String jsonString;

  private static final String DELIMETER =
      "---------------------------------------------------------\n";

  public static class ParamException extends Exception {

    public ParamException(String message) {
      super(message);
    }
  }

  private String filename;

  public LogParserUtils(String filename) {
    this.filename = filename;
    this.jsonString = "[";
  }

  private FileInputStream openGrpcFileInputStream() throws ParamException, IOException {
    if (filename.isEmpty()) {
      throw new ParamException("This operation cannot be performed without specifying --grpc_log.");
    }
    return new FileInputStream(filename);
  }

  // Returns an ExecuteResponse contained in the operation and null if none.
  // If the operation contains an error, returns null and populates StringBuilder "error" with
  // the error message.
  public static <T extends Message> T getExecuteResponse(
      Operation o, Class<T> t, StringBuilder error) throws IOException {
    if (o.getResultCase() == ResultCase.ERROR && o.getError().getCode() != Code.OK.value()) {
      error.append(o.getError().toString());
      return null;
    }
    if (o.getResultCase() == ResultCase.RESPONSE && o.getDone()) {
      return o.getResponse().unpack(t);
    }
    return null;
  }

  // Returns a Digest contained in the log entry, and null if none.
  public static Digest extractDigest(LogEntry entry) {
    if (!entry.hasDetails()) {
      return null;
    }
    RpcCallDetails details = entry.getDetails();
    if (details.hasExecute()) {
      if (details.getExecute().hasRequest()
          && details.getExecute().getRequest().hasActionDigest()) {
        return details.getExecute().getRequest().getActionDigest();
      }
    }
    if (details.hasGetActionResult()) {
      if (details.getGetActionResult().hasRequest()
          && details.getGetActionResult().getRequest().hasActionDigest()) {
        return details.getGetActionResult().getRequest().getActionDigest();
      }
    }
    return null;
  }

  private static List<ExecuteResponse> extractExecuteResponse(List<Operation> operations)
      throws IOException {
    ArrayList<ExecuteResponse> result = new ArrayList<>();
    for (Operation o : operations) {
      StringBuilder error = new StringBuilder();
      ExecuteResponse response = getExecuteResponse(o, ExecuteResponse.class, error);
      if (response != null
          && (response.hasResult()
              || (response.hasStatus()) && response.getStatus().getCode() != Code.OK.value())) {
        result.add(response);
      }
    }
    return result;
  }

  // Returns a list of ExecuteResponse messages contained in the log entry,
  // an empty list if there are none.
  // If the LogEntry contains a successful cache lookup, an ExecuteResponse is constructed
  // with that ActionResult and cached_result set to true.
  public static List<ExecuteResponse> extractExecuteResponse(LogEntry entry) throws IOException {
    if (!entry.hasDetails()) {
      return Collections.emptyList();
    }
    if (entry.getStatus().getCode() != Code.OK.value()) {
      return Collections.emptyList();
    }
    RpcCallDetails details = entry.getDetails();
    if (details.hasExecute()) {
      return extractExecuteResponse(details.getExecute().getResponsesList());
    } else if (details.hasWaitExecution()) {
      return extractExecuteResponse(details.getWaitExecution().getResponsesList());
    } else if (details.hasGetActionResult()) {
      ExecuteResponse response =
          ExecuteResponse.newBuilder()
              .setResult(details.getGetActionResult().getResponse())
              .setCachedResult(true)
              .build();
      return Arrays.asList(response);
    }
    return Collections.emptyList();
  }

  // Returns true iff the given details contains an entry of V1 API
  public static boolean isV1Entry(RpcCallDetails details) {
    return details.hasV1Execute()
        || details.hasV1FindMissingBlobs()
        || details.hasV1GetActionResult()
        || details.hasV1Watch();
  }

  private static <T extends Message> boolean maybePrintOperation(
      Operation o, PrintWriter out, Class<T> t) throws IOException {
    StringBuilder error = new StringBuilder();
    T result = getExecuteResponse(o, t, error);
    if (result != null) {
      out.println("ExecuteResponse extracted:");
      out.println(result.toString());
      return true;
    }
    String errString = error.toString();
    if (!errString.isEmpty()) {
      out.printf("Operation contained error: %s\n", o.getError().toString());
      return true;
    }
    return false;
  }

  /**
   * Attempt to find and print ExecuteResponse from the details of a log entry for a Watch call. If
   * no Operation could be found in the Watch call responses, or an Operation was found but failed,
   * a failure message is printed.
   */
  private static void printExecuteResponse(V1WatchDetails watch, PrintWriter out)
      throws IOException {
    for (ChangeBatch cb : watch.getResponsesList()) {
      for (Change ch : cb.getChangesList()) {
        if (ch.getState() != State.EXISTS) {
          continue;
        }
        Operation o = ch.getData().unpack(Operation.class);
        maybePrintOperation(
            o, out, com.google.devtools.remoteexecution.v1test.ExecuteResponse.class);
        return;
      }
    }
    out.println("Could not find ExecuteResponse in Watch call details.");
  }

  /** Print execute responses or errors contained in the given list of operations. */
  private static void printExecuteResponse(List<Operation> operations, PrintWriter out)
      throws IOException {
    for (Operation o : operations) {
      maybePrintOperation(o, out, build.bazel.remote.execution.v2.ExecuteResponse.class);
    }
  }
  /** Print an individual log entry. */
  static void printLogEntry(LogEntry entry, PrintWriter out) throws IOException {
    out.println(entry.toString());

    switch (entry.getDetails().getDetailsCase()) {
      case V1_WATCH:
        out.println("\nAttempted to extract ExecuteResponse from Watch call responses:");
        printExecuteResponse(entry.getDetails().getV1Watch(), out);
        break;
      case EXECUTE:
        out.println(
            "\nAttempted to extract ExecuteResponse from streaming Execute call responses:");
        printExecuteResponse(entry.getDetails().getExecute().getResponsesList(), out);
        break;
      case WAIT_EXECUTION:
        out.println(
            "\nAttempted to extract ExecuteResponse from streaming WaitExecution call responses:");
        printExecuteResponse(entry.getDetails().getWaitExecution().getResponsesList(), out);
        break;
    }
  }

  private String protobufToJsonEntry(LogEntry input) {
      String jsonString = "";
      if (input == null) {
          throw new RuntimeException("No input provided for parsing");
      } else {
          try {
              jsonString = JsonFormat.printer()
              .usingTypeRegistry(
                  JsonFormat.TypeRegistry.newBuilder()
                      .add(ExecuteOperationMetadata.getDescriptor())
                      .build())
                      .print(input);
          } catch (Exception e) {
              throw new RuntimeException("Error deserializing protobuf to json", e);
          }
      }
      // We want each entry to have it's own comma
      return jsonString;
  }

  /**
   * Prints each entry out individually (ungrouped) and a message at the end for how many entries
   * were printed/skipped.
   */
  private void printEntriesInOrder(OutputStream outStream) throws IOException, ParamException {
    try (InputStream in = openGrpcFileInputStream()) {
      PrintWriter out =
          new PrintWriter(new BufferedWriter(new OutputStreamWriter(outStream, UTF_8)), true);
      LogEntry entry;
      while ((entry = LogEntry.parseDelimitedFrom(in)) != null) {
        printLogEntry(entry, out);
        System.out.print(DELIMETER);
      }
    }
  }

  /**
   * Prints each entry out individually (ungrouped) and a message at the end for how many entries
   * were printed/skipped.
   */
  private void printEntriesInJson(OutputStream outStream) throws IOException, ParamException {
    try (InputStream in = openGrpcFileInputStream()) {
      PrintWriter out =
          new PrintWriter(new BufferedWriter(new OutputStreamWriter(outStream, UTF_8)), true);
      LogEntry entry;
      boolean second_entry = false;
      while ((entry = LogEntry.parseDelimitedFrom(in)) != null) {
        if(second_entry){
          jsonString = jsonString.concat("," + protobufToJsonEntry(entry));
        } else {
          jsonString = jsonString.concat(protobufToJsonEntry(entry));
        }
        second_entry = true;
      }
      jsonString = jsonString.concat("]");
      System.out.print(jsonString);
    }
  }

  private ActionGrouping initActionGrouping() throws IOException, ParamException {
    ActionGrouping.Builder result = new ActionGrouping.Builder();
    try (InputStream in = openGrpcFileInputStream()) {
      LogEntry entry;
      while ((entry = LogEntry.parseDelimitedFrom(in)) != null) {
        result.addLogEntry(entry);
      }
    }
    return result.build();
  }

  private void printEntriesGroupedByAction(OutputStream outStream)
      throws IOException, ParamException {
    ActionGrouping byAction = initActionGrouping();
    PrintWriter out =
        new PrintWriter(new BufferedWriter(new OutputStreamWriter(outStream, UTF_8)), true);
    byAction.printByAction(out);
  }

  /** Print log entries to standard output according to the command line arguments given. */
  public void printLog(PrintLogCommand options) throws IOException {
    try {
      if (options.groupByAction && options.formatJson){
        System.err.println("You can't use groupByAction with formatJson");
      }
      else if (options.groupByAction){
        printEntriesGroupedByAction(System.out);
      }
      else if (options.formatJson) {
        printEntriesInJson(System.out);
      }
      else {
        printEntriesInOrder(System.out);
      }
    } catch (ParamException e) {
      System.err.println(e.getMessage());
      System.exit(1);
    }
  }

  /** Returns a list of actions failed in the grpc log */
  public List<Digest> failedActions() throws IOException, ParamException {
    ActionGrouping a = initActionGrouping();
    return a.failedActions();
  }

  /** Print a list of actions */
  public void printFailedActions() throws IOException, ParamException {
    PrintWriter out =
        new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.out, UTF_8)), true);
    List<Digest> actions = failedActions();
    if (actions.size() == 0) {
      out.println("No failed actions found.");
      return;
    }
    for (Digest d : actions) {
      out.println("Failed action: " + d.getHash() + "/" + d.getSizeBytes());
    }
  }
}
