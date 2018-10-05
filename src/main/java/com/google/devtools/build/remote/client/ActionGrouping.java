package com.google.devtools.build.remote.client;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Multiset;
import com.google.common.collect.TreeMultiset;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.ExecuteDetails;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.LogEntry;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.RpcCallDetails;
import com.google.longrunning.Operation;
import com.google.longrunning.Operation.ResultCase;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import io.grpc.Status.Code;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** A class to handle GRPc log grouped by actions */
final class ActionGrouping {

  @VisibleForTesting
  static final String actionDelimiter = "************************************************";

  @VisibleForTesting
  static final String entryDelimiter = "------------------------------------------------";

  @VisibleForTesting static final String actionString = "Entries for action with hash '%s'\n";

  // A summary of ActionResult for a single action:
  // This finds and records an ActionResult, regardless of how it was obtained.
  static class ActionResultSummary {
    String actionId;
    ActionResult actionResult;

    Timestamp latestErrorTimestamp = Timestamps.MIN_VALUE;
    String latestError;

    ActionResultSummary(String actionId) {
      this.actionId = actionId;
    }

    ActionResult getActionResult() {
      return actionResult;
    }

    private void setResult(ActionResult result) {
      if (this.actionResult != null) {
        System.err.println(
            "Warning: unexpected log format: multiple action results for action " + actionResult);
      }
      actionResult = result;
    }

    private void add(List<Operation> operations) throws IOException {
      for(Operation o : operations) {
        StringBuilder error = new StringBuilder();
        ExecuteResponse response = LogParserUtils.getExecutionResponse(o, ExecuteResponse.class, error);
        if(response != null && response.hasResult()) {
          setResult(response.getResult());
        }
      }
    }

    void add(LogEntry entry) throws IOException {
      if(!entry.hasDetails()) {
        return;
      }

      if(entry.getStatus().getCode() != Code.OK.value()) {
        return;
      }

      RpcCallDetails details = entry.getDetails();

      if(details.hasExecute()) {
        add(details.getExecute().getResponsesList());
      } else if (details.hasWaitExecution()){
        add(details.getWaitExecution().getResponsesList());
      } else if (details.hasGetActionResult()) {
        setResult(details.getGetActionResult().getResponse());
      }
    }
  }

  @VisibleForTesting static class ActionDetails {
    Multiset<LogEntry> log;
    Digest digest;
    ActionResultSummary summary;

    ActionDetails(String actionId) {
      log = TreeMultiset.create(
          (a, b) -> {
            int i = Timestamps.compare(a.getStartTime(), b.getStartTime());
            if (i != 0) {
              return i;
            }
            // In the improbable case of the same timestamp, ensure the messages do not
            // override each other.
            return a.hashCode() - b.hashCode();
          });
      summary = new ActionResultSummary(actionId);
    }

    private Digest extractDigest(LogEntry entry) {
      if(!entry.hasDetails()) {
        return null;
      }
      RpcCallDetails details = entry.getDetails();
      if(details.hasExecute()) {
        if(details.getExecute().hasRequest() && details.getExecute().getRequest().hasActionDigest()) {
          return details.getExecute().getRequest().getActionDigest();
        }
      }
      if(details.hasGetActionResult()) {
        if(details.getGetActionResult().hasRequest() && details.getGetActionResult().getRequest().hasActionDigest()) {
          return details.getGetActionResult().getRequest().getActionDigest();
        }
      }
      return null;
    }

    Digest getDigest() {
      return digest;
    }

    void add(LogEntry entry) throws IOException {
      log.add(entry);

      Digest d = extractDigest(entry);
      if(d != null) {
        if(digest != null && !d.equals(digest)) {
          System.err.println("Warning: conflicting digests: " + d + " and " + digest);
        }
        digest = d;
      }

      summary.add(entry);
    }

    // We will consider an action to be failed if we successfully got an action result but the exit
    // code is non-zero
    boolean isFailed() {
      return summary.getActionResult() != null && summary.getActionResult().getExitCode() != 0;
    }

    Iterable<? extends LogEntry> getSortedElements() {
      return log;
    }
  };

  // Key: actionId; Value: a set of associated log entries.
  private Map<String, ActionDetails> actionMap = new LinkedHashMap<>();

  // True if found V1 entries in the log.
  private boolean V1found = false;

  // The number of entries skipped
  private int numSkipped = 0;

  private boolean isV1Entry(RpcCallDetails details) {
    return details.hasV1Execute() || details.hasV1FindMissingBlobs() || details.hasV1GetActionResult() || details.hasV1Watch();
  }

  void addLogEntry(LogEntry entry) throws IOException {
    if(entry.hasDetails() && isV1Entry(entry.getDetails())) {
      V1found = true;
    }

    if (!entry.hasMetadata()) {
      numSkipped++;
      return;
    }
    String hash = entry.getMetadata().getActionId();

    if (!actionMap.containsKey(hash)) {
      actionMap.put(hash, new ActionDetails(hash));
    }
    actionMap.get(hash).add(entry);
  }

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
    if (numSkipped > 0) {
      System.err.printf(
          "WARNING: Skipped %d entrie(s) due to absence of request metadata.\n", numSkipped);
    }
  }

  List<Digest> failedActions() throws IOException {
    if(V1found) {
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
}
