package com.google.devtools.build.remote.client;

import com.google.common.collect.Multiset;
import com.google.common.collect.TreeMultiset;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.LogEntry;
import com.google.protobuf.util.Timestamps;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/** A class to handle GRPc log grouped by actions */
final class ActionGrouping {

  // Key: actionId; Value: a set of associated log entries.
  Map<String, Multiset<LogEntry>> actionMap = new HashMap<>();
  int numSkipped = 0;

  public void addLogEntry(LogEntry entry) {
    if (!entry.hasMetadata()) {
      numSkipped++;
      return;
    }
    String hash = entry.getMetadata().getActionId();
    if (!actionMap.containsKey(hash)) {
      actionMap.put(
          hash,
          TreeMultiset.create((a, b) -> Timestamps.compare(a.getStartTime(), b.getStartTime())));
    }
    actionMap.get(hash).add(entry);
  }

  public void printByAction(PrintWriter out) throws IOException {
    for (String hash : actionMap.keySet()) {
      out.println("************************************************");
      out.printf("Entries for action with hash '%s'\n", hash);
      out.println("************************************************");
      for (LogEntry entry : actionMap.get(hash)) {
        LogParserUtils.printLogEntry(entry, out);
        out.println("------------------------------------------------");
      }
    }
    if (numSkipped > 0) {
      System.err.printf(
          "WARNING: Skipped %d entrie(s) due to absence of request metadata.\n", numSkipped);
    }
  }
}
