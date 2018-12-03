package com.google.devtools.build.remote.client;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Multiset;
import com.google.common.collect.TreeMultiset;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.LogEntry;
import com.google.protobuf.util.Timestamps;
import io.grpc.Status;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** A class to handle GRPc log grouped by actions */
final class ActionGrouping {

  @VisibleForTesting
  static final String actionDelimiter = "************************************************";

  @VisibleForTesting
  static final String entryDelimiter = "------------------------------------------------";

  @VisibleForTesting static final String actionString = "Entries for action with hash '%s'\n";

  @VisibleForTesting
  static class ActionDetails {
    Multiset<LogEntry> log;
    Digest digest;
    String actionId;
    ExecuteResponse executeResponse;

    private ActionDetails() {}

    Digest getDigest() {
      return digest;
    }

    public ExecuteResponse getExecuteResponse() {
      return executeResponse;
    }

    // We will consider an action to be failed if we successfully got an action result but the exit
    // code is non-zero
    boolean isFailed() {
      if (executeResponse == null) {
        // Action was not successfully completed (either cancelled or RPC error)
        // We don't know if it's a failing action.
        return false;
      }

      if (executeResponse.hasStatus()
          && executeResponse.getStatus().getCode() != Status.Code.OK.value()) {
        // Errors such as PERMISSION_DENIED or DEADLINE_EXCEEDED
        return true;
      }

      // Return true if the action was not successful
      return executeResponse.hasResult() && executeResponse.getResult().getExitCode() != 0;
    }

    Iterable<? extends LogEntry> getSortedElements() {
      return log;
    }

    public static class Builder {
      Multiset<LogEntry> log;
      Digest digest;
      String actionId;
      ExecuteResponse executeResponse;

      public Builder(String actionId) {
        log =
            TreeMultiset.create(
                (a, b) -> {
                  int i = Timestamps.compare(a.getStartTime(), b.getStartTime());
                  if (i != 0) {
                    return i;
                  }
                  // In the improbable case of the same timestamp, ensure the messages do not
                  // override each other.
                  return a.hashCode() - b.hashCode();
                });
        this.actionId = actionId;
      }

      void add(LogEntry entry) throws IOException {
        log.add(entry);

        Digest d = LogParserUtils.extractDigest(entry);
        if (d != null) {
          if (digest != null && !d.equals(digest)) {
            System.err.println("Warning: conflicting digests: " + d + " and " + digest);
          }
          if (digest != null && digest.getHash() != actionId) {
            System.err.println(
                "Warning: bad digest: " + d + " doesn't match action Id " + actionId);
          }
          digest = d;
        }

        List<ExecuteResponse> r = LogParserUtils.extractExecuteResponse(entry);
        if (r.size() > 0) {
          if (executeResponse != null) {
            System.err.println(
                "Warning: unexpected log format: multiple action results for action " + actionId);
          }
          executeResponse = r.get(r.size() - 1);
        }
      }

      ActionDetails build() {
        ActionDetails result = new ActionDetails();

        result.log = this.log;
        result.digest = this.digest;
        result.actionId = this.actionId;
        result.executeResponse = this.executeResponse;

        return result;
      }
    }
  };

  private Map<String, ActionDetails> actionMap = new LinkedHashMap<>();

  // True if found V1 entries in the log.
  private boolean V1found = false;

  void printByAction(PrintWriter out) throws IOException {
    for (String hash : actionMap.keySet()) {
      out.println(actionDelimiter);
      out.printf(actionString, hash);
      out.println(actionDelimiter);
      for (LogEntry entry : actionMap.get(hash).getSortedElements()) {
        LogParserUtils.printLogEntry(entry, out);
        out.println(entryDelimiter);
      }
    }
  }

  List<Digest> failedActions() throws IOException {
    if (V1found) {
      System.err.println(
          "This functinality is not supported for V1 API. Please upgrade your Bazel version.");
      System.exit(1);
    }

    ArrayList<Digest> result = new ArrayList<>();

    for (String hash : actionMap.keySet()) {
      ActionDetails a = actionMap.get(hash);
      if (a.isFailed()) {
        Digest digest = a.getDigest();
        if (digest == null) {
          System.err.println("Error: missing digest for failed action " + hash);
        } else {
          result.add(digest);
        }
      }
    }

    return result;
  }

  private ActionGrouping() {};

  public static class Builder {
    private Map<String, ActionDetails.Builder> actionMap = new LinkedHashMap<>();
    // True if found V1 entries in the log.
    private boolean V1found = false;

    // The number of entries skipped
    private int numSkipped = 0;

    void addLogEntry(LogEntry entry) throws IOException {
      if (entry.hasDetails() && LogParserUtils.isV1Entry(entry.getDetails())) {
        V1found = true;
      }

      if (!entry.hasMetadata()) {
        numSkipped++;
        return;
      }
      String hash = entry.getMetadata().getActionId();

      if (!actionMap.containsKey(hash)) {
        actionMap.put(hash, new ActionDetails.Builder(hash));
      }
      actionMap.get(hash).add(entry);
    }

    public ActionGrouping build() {
      if (numSkipped > 0) {
        System.err.printf(
            "WARNING: Skipped %d entrie(s) due to absence of request metadata.\n", numSkipped);
      }
      Map<String, ActionDetails> builtActionMap =
          actionMap.entrySet().stream()
              .collect(
                  Collectors.toMap(
                      Map.Entry::getKey,
                      e -> e.getValue().build(),
                      (u, v) -> {
                        throw new IllegalStateException(String.format("Duplicate key %s", u));
                      },
                      LinkedHashMap::new));

      ActionGrouping result = new ActionGrouping();
      result.V1found = this.V1found;
      result.actionMap = builtActionMap;
      return result;
    }
  }
}
