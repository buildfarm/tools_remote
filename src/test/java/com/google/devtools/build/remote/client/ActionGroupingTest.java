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

import static com.google.common.truth.Truth.assertWithMessage;

import build.bazel.remote.execution.v2.RequestMetadata;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.LogEntry;
import com.google.protobuf.util.Timestamps;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
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

  private LogEntry getLogEntry(String actionId, String method, int nanos) {
    RequestMetadata m = RequestMetadata.newBuilder().setActionId(actionId).build();
    LogEntry result =
        LogEntry.newBuilder()
            .setMetadata(m)
            .setMethodName(method)
            .setStartTime(Timestamps.fromNanos(nanos))
            .build();
    return result;
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
}
