package com.google.devtools.build.remote.client;

import com.google.common.annotations.VisibleForTesting;
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

  @VisibleForTesting
  static final String actionDelimiter = "************************************************";

  @VisibleForTesting
  static final String entryDelimiter = "------------------------------------------------";

  @VisibleForTesting static final String actionString = "Entries for action with hash '%s'\n";

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
          TreeMultiset.create(
              (a, b) -> {
                int i = Timestamps.compare(a.getStartTime(), b.getStartTime());
                if (i != 0) {
                  return i;
                }
                // In the improbable case of the same timestamp, ensure the messages do not
                // override each other.
                return a.hashCode() - b.hashCode();
              }));
    }
    actionMap.get(hash).add(entry);
  }

  public void printByAction(PrintWriter out) throws IOException {
    for (String hash : actionMap.keySet()) {
      out.println(actionDelimiter);
      out.printf(actionString, hash);
      out.println(actionDelimiter);
      for (LogEntry entry : actionMap.get(hash)) {
        LogParserUtils.printLogEntry(entry, out);
        out.println(entryDelimiter);
      }
    }
    if (numSkipped > 0) {
      System.err.printf(
          "WARNING: Skipped %d entrie(s) due to absence of request metadata.\n", numSkipped);
    }
  }
}
