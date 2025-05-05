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
import static com.google.common.base.Preconditions.checkNotNull;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.ExecuteOperationMetadata;
import build.bazel.remote.execution.v2.ExecutedActionMetadata;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.LogEntry;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.RpcCallDetails;
import com.google.devtools.build.remote.client.RemoteClientOptions.PrintLogCommand;
import com.google.longrunning.Operation;
import com.google.longrunning.Operation.ResultCase;
import com.google.protobuf.Any;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.StringValue;
import com.google.protobuf.util.JsonFormat;
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
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

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

  // Returns an ExecuteOperationMetadata contained in the operation and null if none.
  // If the operation contains an error, returns null and populates StringBuilder "error" with
  // the error message.
  public static <T extends Message> T getExecuteMetadata(
      Operation o, Class<T> t, StringBuilder error) throws IOException {
    try {
      return o.getMetadata().unpack(t);
    } catch (InvalidProtocolBufferException e) {
      return null;
    }
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

  private static <T extends Message> boolean maybePrintMetadata(
      Operation o, PrintWriter out, Class<T> t) throws IOException {
    StringBuilder error = new StringBuilder();
    T result = getExecuteMetadata(o, t, error);
    if (result != null) {
      out.println("Metadata extracted:");
      out.println(result.toString());
      return true;
    }
    return false;
  }

  /** Print execute responses or errors contained in the given list of operations. */
  private static void printExecuteResponse(List<Operation> operations, PrintWriter out)
      throws IOException {
    for (Operation o : operations) {
      maybePrintOperation(o, out, build.bazel.remote.execution.v2.ExecuteResponse.class);
      maybePrintMetadata(o, out, build.bazel.remote.execution.v2.ExecuteOperationMetadata.class);
    }
  }
  /** Print an individual log entry. */
  static void printLogEntry(LogEntry entry, PrintWriter out) throws IOException {
    out.println(entry.toString());

    switch (entry.getDetails().getDetailsCase()) {
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

  static String protobufToJsonEntry(LogEntry input) throws InvalidProtocolBufferException {
      return JsonFormat.printer()
          .usingTypeRegistry(
              JsonFormat.TypeRegistry.newBuilder()
              .add(ExecuteOperationMetadata.getDescriptor())
              .build())
          .print(checkNotNull(input));
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

  private List<Operation> getResponses(RpcCallDetails details) {
    switch (details.getDetailsCase()) {
    case EXECUTE:
      return details.getExecute().getResponsesList();
    case WAIT_EXECUTION:
      return details.getWaitExecution().getResponsesList();
    }
    return null;
  }

  private void filterExecutedActionMetadata(ExecutedActionMetadata.Builder builder) {
    List<Any> auxiliaryMetadata = new ArrayList<>(builder.getAuxiliaryMetadataList());
    builder.clearAuxiliaryMetadata();
    for (Any metadata : auxiliaryMetadata) {
      builder.addAuxiliaryMetadata(Any.pack(StringValue.newBuilder().setValue(metadata.toString()).build()));
    }
  }

  private LogEntry filterUnknownAny(LogEntry entry) throws IOException {
    ActionResult result;
    LogEntry.Builder builder = entry.toBuilder();
    switch (entry.getDetails().getDetailsCase()) {
      case EXECUTE:
      case WAIT_EXECUTION:
        List<Operation> responses = getResponses(entry.getDetails());
        List<Operation> filteredResponses = new ArrayList<>();
        for (Operation response : responses) {
          StringBuilder error = new StringBuilder();
          ExecuteOperationMetadata metadata = getExecuteMetadata(response, ExecuteOperationMetadata.class, error);
          Operation.Builder filteredResponse = response.toBuilder();
          if (metadata != null && metadata.hasPartialExecutionMetadata()) {
            ExecuteOperationMetadata.Builder metadataBuilder = metadata.toBuilder();
            filterExecutedActionMetadata(metadataBuilder.getPartialExecutionMetadataBuilder());
            filteredResponse.setMetadata(Any.pack(metadataBuilder.build()));
          }
          ExecuteResponse executeResponse = getExecuteResponse(response, ExecuteResponse.class, error);
          if (executeResponse != null && executeResponse.getResult().hasExecutionMetadata()) {
            ExecuteResponse.Builder responseBuilder = executeResponse.toBuilder();
            filterExecutedActionMetadata(responseBuilder.getResultBuilder().getExecutionMetadataBuilder());
            filteredResponse.setResponse(Any.pack(responseBuilder.build()));
          }
          filteredResponses.add(filteredResponse.build());
        }
        switch (entry.getDetails().getDetailsCase()) {
          case EXECUTE:
            builder.getDetailsBuilder().getExecuteBuilder().clearResponses().addAllResponses(filteredResponses);
            break;
          case WAIT_EXECUTION:
            builder.getDetailsBuilder().getWaitExecutionBuilder().clearResponses().addAllResponses(filteredResponses);
            break;
        }
        return builder.build();
      case GET_ACTION_RESULT:
        ActionResult.Builder resultBuilder = builder.getDetailsBuilder().getGetActionResultBuilder().getResponseBuilder();
        filterExecutedActionMetadata(resultBuilder.getExecutionMetadataBuilder());
        return builder.build();
    }
    return entry;
  }

  /**
   * Prints each entry out individually (ungrouped) and a message at the end for how many entries
   * were printed/skipped.
   */
  private void printEntriesInJson() throws IOException, ParamException {
    try (InputStream in = openGrpcFileInputStream()) {
      LogEntry entry;
      JSONArray entries = new JSONArray();
      while ((entry = filterUnknownAny(LogEntry.parseDelimitedFrom(in))) != null) {
        String s = protobufToJsonEntry(entry);
        Object obj = JSONValue.parse(s);
        entries.add(obj);
      }
      System.out.print(entries);
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

  private void printEntriesGroupedByActionJson()
      throws IOException, ParamException {
    ActionGrouping byAction = initActionGrouping();
    byAction.printByActionJson();
  }

  /** Print log entries to standard output according to the command line arguments given. */
  public void printLog(PrintLogCommand options) throws IOException {
    try {
      if (options.formatJson && options.groupByAction){
        printEntriesGroupedByActionJson();
      } else if (options.formatJson) {
        printEntriesInJson();
      } else if (options.groupByAction){
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
