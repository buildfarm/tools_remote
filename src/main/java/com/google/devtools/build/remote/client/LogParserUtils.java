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

import com.google.common.collect.Multiset;
import com.google.common.collect.TreeMultiset;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.LogEntry;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.RpcCallDetails.DetailsCase;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.WatchDetails;
import com.google.devtools.build.remote.client.RemoteClientOptions.PrintLogCommand;
import com.google.devtools.remoteexecution.v1test.ExecuteResponse;
import com.google.longrunning.Operation;
import com.google.longrunning.Operation.ResultCase;
import com.google.protobuf.Timestamp;
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
import java.util.HashMap;
import java.util.Map;

/**
 * Methods for printing log files.
 */
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

  private String getFilename() throws ParamException {
    if (filename.isEmpty()) {
      throw new ParamException("This operation cannot be performed without specifying --grpc_log.");
    }
    return filename;
  }


  /**
   * Attempt to find and print ExecuteResponse from the details of a log entry for a Watch call. If
   * no Operation could be found in the Watch call responses, or an Operation was found but failed,
   * a failure message is printed.
   */
  private static void printExecuteResponse(WatchDetails watch, PrintWriter out) throws IOException {
    for (ChangeBatch cb : watch.getResponsesList()) {
      for (Change ch : cb.getChangesList()) {
        if (ch.getState() != State.EXISTS) {
          continue;
        }
        Operation o = ch.getData().unpack(Operation.class);
        if (o.getResultCase() == ResultCase.ERROR && o.getError().getCode() != Code.OK.value()) {
          out.printf("Operation contained error: %s\n", o.getError().toString());
          return;
        } else if (o.getResultCase() == ResultCase.RESPONSE && o.getDone()) {
          out.println("ExecuteResponse extracted:");
          out.println(o.getResponse().unpack(ExecuteResponse.class).toString());
          return;
        }
      }
    }
    out.println("Could not find ExecuteResponse in Watch call details.");
  }

  /**
   * Print an individual log entry.
   */
  static void printLogEntry(LogEntry entry, PrintWriter out) throws IOException {
    out.println(entry.toString());
    if (entry.getDetails().getDetailsCase() == DetailsCase.WATCH) {
      out.println("\nAttempted to extract ExecuteResponse from Watch call responses:");
      printExecuteResponse(entry.getDetails().getWatch(), out);
    }
  }

  /**
   * Prints each entry out individually (ungrouped) and a message at the end for how many entries
   * were printed/skipped.
   */
  private void printEntriesInOrder(OutputStream outStream) throws IOException, ParamException {
    try (InputStream in = new FileInputStream(getFilename())) {
      PrintWriter out =
          new PrintWriter(new BufferedWriter(new OutputStreamWriter(outStream, UTF_8)), true);
      LogEntry entry;
      while ((entry = LogEntry.parseDelimitedFrom(in)) != null) {
        printLogEntry(entry, out);
        System.out.print(DELIMETER);
      }
    }
  }

  private void printEntriesGroupedByAction(OutputStream outStream)
      throws IOException, ParamException {
    ActionGrouping byAction = new ActionGrouping();
    ;
    try (InputStream in = new FileInputStream(getFilename())) {
      LogEntry entry;
      while ((entry = LogEntry.parseDelimitedFrom(in)) != null) {
        byAction.addLogEntry(entry);
      }
    }
    PrintWriter out =
        new PrintWriter(new BufferedWriter(new OutputStreamWriter(outStream, UTF_8)), true);
    byAction.printByAction(out);
  }


  /**
   * Print log entries to standard output according to the command line arguments given.
   */
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
}
