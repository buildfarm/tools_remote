package com.google.devtools.build.remote.client;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.Multiset;
import com.google.common.collect.TreeMultiset;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.LogEntry;
import com.google.protobuf.Timestamp;
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
 * A class to handle GRPc log grouped by actions
 */
final class ActionGrouping {

  /**
   * Gives comparator values for timestamps.
   *
   * @return {@code 0} if {@code t1} is equal to {@code t2}; a value less than {code 0} if {@code
   * t1} occurs before {@code t2}; a value greater than {code 0} if {@code t1} occurs after {@code
   * t2}.
   */
  private static int compareTimestamps(Timestamp t1, Timestamp t2) {
    int cmpSeconds = Long.compare(t1.getSeconds(), t2.getSeconds());
    return (cmpSeconds != 0) ? cmpSeconds : Integer.compare(t1.getNanos(), t2.getNanos());
  }

  Map<String, Multiset<LogEntry>> actionMap = new HashMap<>();
  int numSkipped = 0;

  public ActionGrouping() {
  }

  public void addLogEntry(LogEntry entry) {
    if (!entry.hasMetadata()) {
      numSkipped++;
      return;
    }
    String hash = entry.getMetadata().getActionId();
    if (!actionMap.containsKey(hash)) {
      actionMap.put(
          hash,
          TreeMultiset.create((a, b) -> compareTimestamps(a.getStartTime(), b.getStartTime())));
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
