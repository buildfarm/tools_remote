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
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.LogEntry;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.V1WatchDetails;
import com.google.devtools.build.remote.client.RemoteClientOptions.PrintLogCommand;
import com.google.longrunning.Operation;
import com.google.longrunning.Operation.ResultCase;
import com.google.protobuf.Message;
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
import java.util.List;

/** Methods for printing log files. */
public class LogParserUtils {

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
  }

  private FileInputStream openGrpcFileInputStream() throws ParamException, IOException {
    if (filename.isEmpty()) {
      throw new ParamException("This operation cannot be performed without specifying --grpc_log.");
    }
    return new FileInputStream(filename);
  }

  // Returns an ExecutionResponse contained in the operation and null if none.
  // If the operation contains an error, returns null and populates StringBuilder "error" with
  // the error message.
  public static <T extends Message> T getExecutionResponse(
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

  private static <T extends Message> boolean maybePrintOperation(
      Operation o, PrintWriter out, Class<T> t) throws IOException {
    StringBuilder error = new StringBuilder();
    T result = getExecutionResponse(o, t, error);
    if(result != null) {
      out.println("ExecuteResponse extracted:");
      out.println(o.getResponse().unpack(t).toString());
      return true;
    }
    String errString = error.toString();
    if(!errString.isEmpty()) {
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

  private ActionGrouping initActionGrouping() throws IOException, ParamException{
    ActionGrouping result = new ActionGrouping();
    try (InputStream in = openGrpcFileInputStream()) {
      LogEntry entry;
      while ((entry = LogEntry.parseDelimitedFrom(in)) != null) {
        result.addLogEntry(entry);
      }
    }
    return result;
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
      if (options.groupByAction) {
        printEntriesGroupedByAction(System.out);
      } else {
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

  /** Print a list of actions  */
  public void printFailedActions() throws IOException, ParamException {
    PrintWriter out =
        new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.out, UTF_8)), true);
    List<Digest> actions = failedActions();
    if(actions.size() == 0) {
      out.println("No failed actions found.");
      return;
    }
    for(Digest d : actions) {
      out.println("Failed action: " + d.getHash() + "/" + d.getSizeBytes());
    }
  }
}
