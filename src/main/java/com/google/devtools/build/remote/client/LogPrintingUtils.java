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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Methods for printing log files. */
public class LogPrintingUtils {
  private static final String DELIMETER =
      "---------------------------------------------------------\n";

  /**
   * Attempt to find and print ExecuteResponse from the details of a log entry for a Watch call. If
   * no Operation could be found in the Watch call responses, or an Operation was found but failed,
   * a failure message is printed.
   */
  private static void printExecuteResponse(WatchDetails watch) throws IOException {
    for (ChangeBatch cb : watch.getResponsesList()) {
      for (Change ch : cb.getChangesList()) {
        if (ch.getState() != State.EXISTS) {
          continue;
        }
        Operation o = ch.getData().unpack(Operation.class);
        if (o.getResultCase() == ResultCase.ERROR && o.getError().getCode() != Code.OK.value()) {
          System.out.printf("Operation contained error: %s\n", o.getError().toString());
          return;
        } else if (o.getResultCase() == ResultCase.RESPONSE && o.getDone()) {
          System.out.println("ExecuteResponse extracted:");
          System.out.println(o.getResponse().unpack(ExecuteResponse.class).toString());
          return;
        }
      }
    }
    System.out.println("Could not find ExecuteResponse in Watch call details.");
  }

  /** Print an individual log entry. */
  private static void printLogEntry(LogEntry entry) throws IOException {
    System.out.println(entry.toString());
    if (entry.getDetails().getDetailsCase() == DetailsCase.WATCH) {
      System.out.println("\nAttempted to extract ExecuteResponse from Watch call responses:");
      printExecuteResponse(entry.getDetails().getWatch());
    }
  }

  /**
   * Prints each entry out individually (ungrouped) and a message at the end for how many entries
   * were printed/skipped.
   */
  private static void printEntriesInOrder(PrintLogCommand options) throws IOException {
    try (InputStream in = new FileInputStream(options.file)) {
      LogEntry entry;
      while ((entry = LogEntry.parseDelimitedFrom(in)) != null) {
        printLogEntry(entry);
        System.out.print(DELIMETER);
      }
    }
  }

  /**
   * Gives comparator values for timestamps.
   *
   * @return {@code 0} if {@code t1} is equal to {@code t2}; a value less than {code 0} if {@code
   *     t1} occurs before {@code t2}; a value greater than {code 0} if {@code t1} occurs after
   *     {@code t2}.
   */
  private static int compareTimestamps(Timestamp t1, Timestamp t2) {
    int cmpSeconds = Long.compare(t1.getSeconds(), t2.getSeconds());
    return (cmpSeconds != 0) ? cmpSeconds : Integer.compare(t1.getNanos(), t2.getNanos());
  }

  /**
   * Prints each entry in groups by action, and a message at the end for how many entries were
   * printed/skipped. If an entry does not have metadata to identify the action, is it skipped.
   *
   * <p>Entries for each action are printed in ascending order of their call started timestamps
   * (earliest first).
   */
  private static void printEntriesGroupedByAction(PrintLogCommand options) throws IOException {
    Map<String, Multiset<LogEntry>> actionMap = new HashMap<>();
    int numSkipped = 0;
    try (InputStream in = new FileInputStream(options.file)) {
      LogEntry entry;
      while ((entry = LogEntry.parseDelimitedFrom(in)) != null) {
        if (!entry.hasMetadata()) {
          numSkipped++;
          continue;
        }
        String hash = entry.getMetadata().getActionId();
        if (!actionMap.containsKey(hash)) {
          actionMap.put(
              hash,
              TreeMultiset.create((a, b) -> compareTimestamps(a.getStartTime(), b.getStartTime())));
        }
        actionMap.get(hash).add(entry);
      }
    }
    for (String hash : actionMap.keySet()) {
      System.out.printf("Entries for action with hash '%s'\n", hash);
      System.out.print(DELIMETER);
      for (LogEntry entry : actionMap.get(hash)) {
        printLogEntry(entry);
        System.out.print(DELIMETER);
      }
    }
    if (numSkipped > 0) {
      System.out.printf(
          "WARNING: Skipped %d entrie(s) due to absence of request metadata.\n", numSkipped);
    }
  }

  /** Print log entries to standard output according to the command line arguments given. */
  public static void printLog(PrintLogCommand options) throws IOException {
    if (options.groupByAction) {
      printEntriesGroupedByAction(options);
    } else {
      printEntriesInOrder(options);
    }
  }
}
