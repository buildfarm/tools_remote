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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.GetActionResultRequest;
import build.bazel.remote.execution.v2.RequestMetadata;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.ExecuteDetails;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.GetActionResultDetails;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.LogEntry;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.RpcCallDetails;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.WaitExecutionDetails;
import com.google.devtools.build.remote.client.ActionGrouping.ActionDetails;
import com.google.devtools.build.remote.client.ActionGrouping.ActionResultSummary;
import com.google.devtools.build.remote.client.LogParserUtils.ParamException;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.util.Timestamps;
import io.grpc.Status;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ShellEscaper}. */
@RunWith(JUnit4.class)
public class ActionGroupingTest {

  /** Returns the string obtained from printByAction in actionGrouping */
  private String getOutput(ActionGrouping actionGrouping) throws IOException {
    StringWriter stringOut = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringOut);

    actionGrouping.printByAction(printWriter);
    printWriter.flush();
    return stringOut.toString();
  }
  /*
   * Asserts that the actionGrouping contains the given strings.
   * To pass, all action and entry delimiters must be explicitly included in the arguments
   * (this way an extra action or an entry would fail the test).
   * All other arguments need to be substrings of a single line.
   */
  private void checkOutput(ActionGrouping actionGrouping, String... args) throws IOException {
    String result = getOutput(actionGrouping);

    // A dummy string to use when we run out of args.
    // We don't expect it to appear in the input
    final String sentinel = "<<<<<SENTINEL>>>>> No more inputs to process <<<<<SENTINEL>>>>>";

    Scanner scanner = new Scanner(result);
    int ind = 0;
    int lineNum = 0;
    while (scanner.hasNextLine()) {
      String got = scanner.nextLine().trim();
      lineNum++;
      String want = (ind < args.length ? args[ind] : sentinel);
      if (got.contains(want)) {
        ind++;
        continue;
      }

      assertWithMessage(
              "Expecting "
                  + want
                  + ", got action delimiter on line "
                  + lineNum
                  + ".Output: \n"
                  + result)
          .that(got)
          .isNotEqualTo(ActionGrouping.actionDelimiter);

      assertWithMessage(
              "Expecting "
                  + want
                  + ", got entry delimiter on line "
                  + lineNum
                  + ".Output: \n"
                  + result)
          .that(got)
          .isNotEqualTo(ActionGrouping.entryDelimiter);

      // Ignore unmached lines that are not delimiters
    }
    assertWithMessage(
            "Not all expected arguments are found. "
                + (ind < args.length ? "Looking for " + args[ind] : "Expected no output")
                + ". Output: \n"
                + result)
        .that(ind)
        .isEqualTo(args.length);
    scanner.close();
  }

  @Test
  public void EmptyGrouping() throws Exception {
    ActionGrouping actionGrouping = new ActionGrouping();
    checkOutput(actionGrouping); // This will ensure there are no delimiters in the output
  }

  private LogEntry getLogEntry(String actionId, String method, int nanos, RpcCallDetails details) {
    RequestMetadata m = RequestMetadata.newBuilder().setActionId(actionId).build();
    LogEntry.Builder result =
        LogEntry.newBuilder()
            .setMetadata(m)
            .setMethodName(method)
            .setStartTime(Timestamps.fromNanos(nanos));
    if(details != null) {
      result.setDetails(details);
    }
    return result.build();
  }

  private LogEntry getLogEntry(String actionId, String method, int nanos) {
    return getLogEntry(actionId, method, nanos, null);
  }

  private String actionHeader(String actionId) {
    return String.format(ActionGrouping.actionString, actionId).trim();
  }

  @Test
  public void SingleLog() throws Exception {
    ActionGrouping actionGrouping = new ActionGrouping();
    actionGrouping.addLogEntry(getLogEntry("action1", "call1", 4));
    checkOutput(
        actionGrouping,
        ActionGrouping.actionDelimiter,
        actionHeader("action1"),
        ActionGrouping.actionDelimiter,
        "call1",
        ActionGrouping.entryDelimiter);
  }

  @Test
  public void SameTimestamp() throws Exception {
    // Events with the same timestamp must all be present, but their ordering is arbitrary.
    ActionGrouping actionGrouping = new ActionGrouping();
    actionGrouping.addLogEntry(getLogEntry("action1", "a1_call1", 5));
    actionGrouping.addLogEntry(getLogEntry("action2", "a2_call1", 5));
    actionGrouping.addLogEntry(getLogEntry("action1", "a1_call2", 5));
    actionGrouping.addLogEntry(getLogEntry("action1", "a1_call3", 5));

    String result = getOutput(actionGrouping);

    assertWithMessage("Output: \n" + result).that(result).contains("a1_call1");
    assertWithMessage("Output: \n" + result).that(result).contains("a1_call2");
    assertWithMessage("Output: \n" + result).that(result).contains("a1_call3");
    assertWithMessage("Output: \n" + result).that(result).contains("a2_call1");
  }

  @Test
  public void Sorting() throws Exception {
    ActionGrouping actionGrouping = new ActionGrouping();
    actionGrouping.addLogEntry(getLogEntry("action1", "a1_call_5", 5));
    actionGrouping.addLogEntry(getLogEntry("action2", "a2_call_10", 10));
    actionGrouping.addLogEntry(getLogEntry("action1", "a1_call_3", 3));
    actionGrouping.addLogEntry(getLogEntry("action1", "a1_call_1", 1));
    actionGrouping.addLogEntry(getLogEntry("action1", "a1_call_100", 100));
    actionGrouping.addLogEntry(getLogEntry("action2", "a2_call_100", 100));
    actionGrouping.addLogEntry(getLogEntry("action2", "a2_call_50", 50));
    actionGrouping.addLogEntry(getLogEntry("action3", "a3_call_1", 1));
    checkOutput(
        actionGrouping,
        ActionGrouping.actionDelimiter,
        actionHeader("action1"),
        ActionGrouping.actionDelimiter,
        "a1_call_1",
        ActionGrouping.entryDelimiter,
        "a1_call_3",
        ActionGrouping.entryDelimiter,
        "a1_call_5",
        ActionGrouping.entryDelimiter,
        "a1_call_100",
        ActionGrouping.entryDelimiter,
        ActionGrouping.actionDelimiter,
        actionHeader("action2"),
        ActionGrouping.actionDelimiter,
        "a2_call_10",
        ActionGrouping.entryDelimiter,
        "a2_call_50",
        ActionGrouping.entryDelimiter,
        "a2_call_100",
        ActionGrouping.entryDelimiter,
        ActionGrouping.actionDelimiter,
        actionHeader("action3"),
        ActionGrouping.actionDelimiter,
        "a3_call_1",
        ActionGrouping.entryDelimiter);
  }

  ActionResult actionResultSuccess = ActionResult.newBuilder().setExitCode(0).build();
  ActionResult actionResultFail = ActionResult.newBuilder().setExitCode(1).build();

  Digest toDigest(String d) {
    String[] parts = d.split("/");
    assert(parts.length == 2);
    long size = Long.parseLong(parts[1]);
    return Digest.newBuilder().setHash(parts[0]).setSizeBytes(size).build();
  }

  private LogEntry makeFailedGetActionResult(int nanos, String digest) {
    RpcCallDetails.Builder details = RpcCallDetails.newBuilder();
    details.getGetActionResultBuilder().getRequestBuilder().setActionDigest(toDigest(digest));
    LogEntry logEntry = getLogEntry(toDigest(digest).getHash(), "getActionResult", nanos, details.build());
    LogEntry.Builder result = LogEntry.newBuilder(logEntry);
    result.getStatusBuilder().setCode(Status.NOT_FOUND.getCode().value());
    return result.build();
  }

  private RpcCallDetails makeGetActionResult(ActionResult result) {
    GetActionResultDetails getActionResult = GetActionResultDetails.newBuilder().setResponse(result).build();
    return RpcCallDetails.newBuilder().setGetActionResult(getActionResult).build();
  }

  private RpcCallDetails makeGetActionResultWithDigest(ActionResult result, String digest) {
    GetActionResultRequest request = GetActionResultRequest.newBuilder().setActionDigest(toDigest(digest)).build();
    GetActionResultDetails getActionResult = GetActionResultDetails.newBuilder().setResponse(result).build();
    return RpcCallDetails.newBuilder().setGetActionResult(getActionResult).build();
  }

  private RpcCallDetails makeExecute(ActionResult result) {
    ExecuteResponse response = ExecuteResponse.newBuilder().setResult(result).build();
    Operation operation = Operation.newBuilder().setResponse(Any.pack(response)).setDone(true).build();
    ExecuteDetails execute = ExecuteDetails.newBuilder().addResponses(operation).build();
    return RpcCallDetails.newBuilder().setExecute(execute).build();
  }

  private RpcCallDetails makeWatch(ActionResult result) {
    ExecuteResponse response = ExecuteResponse.newBuilder().setResult(result).build();
    Operation operation = Operation.newBuilder().setResponse(Any.pack(response)).setDone(true).build();
    WaitExecutionDetails waitExecution = WaitExecutionDetails.newBuilder().addResponses(operation).build();
    return RpcCallDetails.newBuilder().setWaitExecution(waitExecution).build();
  }

  private LogEntry addDigest(LogEntry entry, String digest) {
    LogEntry.Builder result = LogEntry.newBuilder(entry);
    assert(entry.hasDetails());
    if(entry.getDetails().hasExecute()) {
      result.getDetailsBuilder().getExecuteBuilder().getRequestBuilder().setActionDigest(toDigest(digest));
    } else if(entry.getDetails().hasGetActionResult()) {
      result.getDetailsBuilder().getGetActionResultBuilder().getRequestBuilder().setActionDigest(toDigest(digest));
    } else {
      assertWithMessage("Can't add digest to an entry that is neither Execute nor GetActionResult").fail();
    }
    return result.build();
  }

  // Test logic to extract action result
  @Test
  public void ActionResultForEmpty() {
    ActionDetails details = new ActionDetails("actionId");
    // Action with no log entries is not failed but has no actionResult
    assert(details.summary.getActionResult() == null);
    assert(!details.isFailed());
  };

  @Test
  public void ActionResultFromCachePass() throws IOException {
    ActionDetails details = new ActionDetails("actionId");
    // Action with no log entries is not failed but has no actionResult
    details.add(getLogEntry("actionId", "Execute", 10, makeGetActionResult(actionResultSuccess)));
    assert(details.summary.getActionResult() != null);
    assert(!details.isFailed());
  };

  @Test
  public void ActionResultFromCacheFail() throws IOException {
    ActionDetails details = new ActionDetails("actionId");
    // Action with no log entries is not failed but has no actionResult
    details.add(getLogEntry("actionId", "Execute", 10, makeGetActionResult(actionResultFail)));
    assert(details.summary.getActionResult() != null);
    assert(details.isFailed());
  };


  @Test
  public void ActionResultFromExecutePass() throws IOException {
    ActionDetails details = new ActionDetails("actionId");
    // Action with no log entries is not failed but has no actionResult
    details.add(getLogEntry("actionId", "Execute", 10, makeExecute(actionResultSuccess)));
    assert(details.summary.getActionResult() != null);
    assert(!details.isFailed());
  };

  @Test
  public void ActionResultFromExecuteFail() throws IOException {
    ActionDetails details = new ActionDetails("actionId");
    // Action with no log entries is not failed but has no actionResult
    details.add(getLogEntry("actionId", "Execute", 10, makeExecute(actionResultFail)));
    assert(details.summary.getActionResult() != null);
    assert(details.isFailed());
  };

  @Test
  public void ActionResultFromWatchPass() throws IOException {
    ActionDetails details = new ActionDetails("actionId");
    // Action with no log entries is not failed but has no actionResult
    details.add(getLogEntry("actionId", "Execute", 10, makeWatch(actionResultSuccess)));
    assert(details.summary.getActionResult() != null);
    assert(!details.isFailed());
  };

  @Test
  public void ActionResultFromWatchFail() throws IOException {
    ActionDetails details = new ActionDetails("actionId");
    // Action with no log entries is not failed but has no actionResult
    details.add(getLogEntry("actionId", "Execute", 10, makeWatch(actionResultFail)));
    assert(details.summary.getActionResult() != null);
    assert(details.isFailed());
  };

  @Test
  public void FailedActionsEmpty() throws IOException, ParamException {
    ActionGrouping grouping = new ActionGrouping();
    List<Digest> result = grouping.failedActions();
    assert(result.isEmpty());
  }

  @Test
  public void FailedActionsAllPass() throws IOException, ParamException {
    ActionGrouping grouping = new ActionGrouping();
    grouping.addLogEntry(getLogEntry("actionId", "Execute", 10, makeWatch(actionResultSuccess)));
    List<Digest> result = grouping.failedActions();
    assert(result.isEmpty());
  }

  @Test
  public void FailedActionsOneFail() throws IOException, ParamException {
    ActionGrouping grouping = new ActionGrouping();
    grouping.addLogEntry(addDigest(
        getLogEntry("12345", "Execute", 10, makeExecute(actionResultSuccess)),
        "12345/56"));
    grouping.addLogEntry(addDigest(
        getLogEntry("987", "Execute", 10, makeExecute(actionResultFail)),
        "987/22"));
    grouping.addLogEntry(addDigest(
        getLogEntry("345", "Execute", 10, makeExecute(actionResultSuccess)),
        "345/1"));
    List<Digest> result = grouping.failedActions();

    List<Digest> expected = Arrays.asList(toDigest("987/22"));
    assertThat(result).isEqualTo(expected);
  }

  @Test
  public void FailedActionsManyFail() throws IOException, ParamException {
    ActionGrouping grouping = new ActionGrouping();
    grouping.addLogEntry(addDigest(
        getLogEntry("12345", "Execute", 10, makeExecute(actionResultFail)),
        "12345/56"));
    grouping.addLogEntry(addDigest(
        getLogEntry("987", "Execute", 10, makeExecute(actionResultFail)),
        "987/22"));
    grouping.addLogEntry(addDigest(
        getLogEntry("345", "Execute", 10, makeExecute(actionResultFail)),
        "345/1"));
    List<Digest> result = grouping.failedActions();

    List<Digest> expected = Arrays.asList(toDigest("12345/56"), toDigest("987/22"), toDigest("345/1"));
    assertThat(result).isEqualTo(expected);
  }

  @Test
  public void FailedActionsDifferentResults() throws IOException, ParamException {
    ActionGrouping grouping = new ActionGrouping();
    grouping.addLogEntry(addDigest(
        getLogEntry("12345", "Execute", 10, makeGetActionResult(actionResultFail)),
        "12345/56"));
    grouping.addLogEntry(addDigest(
        getLogEntry("987", "Execute", 10, makeGetActionResult(actionResultSuccess)),
        "987/22"));
    grouping.addLogEntry(addDigest(
        getLogEntry("345", "Execute", 10, makeExecute(actionResultFail)),
        "345/1"));
    List<Digest> result = grouping.failedActions();

    List<Digest> expected = Arrays.asList(toDigest("12345/56"), toDigest("345/1"));
    assertThat(result).isEqualTo(expected);
  }

  @Test
  public void FailedActionsGetsDigestFromGetActionResult() throws IOException, ParamException {
    ActionGrouping grouping = new ActionGrouping();
    grouping.addLogEntry(makeFailedGetActionResult(10, "12345/88"));
    grouping.addLogEntry(getLogEntry("12345", "Execute", 10, makeWatch(actionResultFail)));
    List<Digest> result = grouping.failedActions();

    List<Digest> expected = Arrays.asList(toDigest("12345/88"));
    assertThat(result).isEqualTo(expected);
  }

  @Test
  public void FailedActionsGetsDigestFromExecute() throws IOException, ParamException {
    ActionGrouping grouping = new ActionGrouping();
    grouping.addLogEntry(addDigest(
        getLogEntry("12345", "Execute", 10, makeExecute(ActionResult.getDefaultInstance())),
        "12345/1"));
    grouping.addLogEntry(getLogEntry("12345", "Execute", 10, makeWatch(actionResultFail)));
    List<Digest> result = grouping.failedActions();

    List<Digest> expected = Arrays.asList(toDigest("12345/1"));
    assertThat(result).isEqualTo(expected);
  }

  @Test
  public void FailedActionsDoesntGetDigestFromWrongExecute() throws IOException, ParamException {
    ActionGrouping grouping = new ActionGrouping();
    grouping.addLogEntry(addDigest(
        getLogEntry("wrong_action", "Execute", 10, makeExecute(ActionResult.getDefaultInstance())),
        "12345/1"));
    grouping.addLogEntry(getLogEntry("12345", "Execute", 10, makeWatch(actionResultFail)));
    List<Digest> result = grouping.failedActions();

    assertThat(result).isEmpty();
  }
}
