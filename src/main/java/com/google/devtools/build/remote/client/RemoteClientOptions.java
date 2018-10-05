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

import build.bazel.remote.execution.v2.Digest;
import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.converters.FileConverter;
import com.beust.jcommander.converters.PathConverter;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Options for operation of a remote client. */
@Parameters(separators = "=")
public final class RemoteClientOptions {
  @Parameter(names = "--help", description = "This message.", help = true)
  public boolean help;

  @Parameter(names = "--grpc_log", description = "GRPC log to reference for additional information")
  public String grpcLog = "";

  @Parameters(
      commandDescription = "Recursively lists a Directory in remote cache.",
      separators = "=")
  public static class LsCommand {
    @Parameter(
        names = {"--digest", "-d"},
        required = true,
        converter = DigestConverter.class,
        description = "The digest of the Directory to list in hex_hash/size_bytes.")
    public Digest digest = null;

    @Parameter(
        names = {"--limit", "-l"},
        description = "The maximum number of files in the Directory to list.")
    public int limit = 100;
  }

  @Parameters(
      commandDescription = "Recursively lists an OutputDirectory in remote cache.",
      separators = "=")
  public static class LsOutDirCommand {
    @Parameter(
        names = {"--digest", "-d"},
        required = true,
        converter = DigestConverter.class,
        description = "The digest of the OutputDirectory to list in hex_hash/size_bytes.")
    public Digest digest = null;

    @Parameter(
        names = {"--limit", "-l"},
        description = "The maximum number of files in the OutputDirectory to list.")
    public int limit = 100;
  }

  @Parameters(
      commandDescription = "Recursively downloads a Directory from remote cache.",
      separators = "=")
  public static class GetDirCommand {
    @Parameter(
        names = {"--digest", "-d"},
        required = true,
        converter = DigestConverter.class,
        description = "The digest of the Directory to download in hex_hash/size_bytes.")
    public Digest digest = null;

    @Parameter(
        names = {"--path", "-o"},
        converter = PathConverter.class,
        description = "The local path to download the Directory contents into.")
    public Path path = Paths.get("");
  }

  @Parameters(
      commandDescription = "Recursively downloads a OutputDirectory from remote cache.",
      separators = "=")
  public static class GetOutDirCommand {
    @Parameter(
        names = {"--digest", "-d"},
        required = true,
        converter = DigestConverter.class,
        description = "The digest of the OutputDirectory to download in hex_hash/size_bytes.")
    public Digest digest = null;

    @Parameter(
        names = {"--path", "-o"},
        converter = PathConverter.class,
        description = "The local path to download the OutputDirectory contents into.")
    public Path path = Paths.get("");
  }

  @Parameters(
      commandDescription =
          "Write contents of a blob from remote cache to stdout. If specified, "
              + "the contents of the blob can be written to a specific file instead of stdout.",
      separators = "=")
  public static class CatCommand {
    @Parameter(
        names = {"--digest", "-d"},
        required = true,
        converter = DigestConverter.class,
        description = "The digest in the format hex_hash/size_bytes of the blob to download.")
    public Digest digest = null;

    @Parameter(
        names = {"--file", "-o"},
        converter = FileConverter.class,
        description = "Specifies a file to write the blob contents to instead of stdout.")
    public File file = null;
  }

  @Parameters(
      commandDescription =
          "Find and print action ids of failed actions from grpc log.",
      separators = "=")
  public static class FailedActionsCommand {
  }


  @Parameters(
      commandDescription = "Parse and display an Action with its corresponding command.",
      separators = "=")
  public static class ShowActionCommand {
    @Parameter(
        names = {"--textproto", "-p"},
        converter = FileConverter.class,
        description = "Path to a V1 Action proto stored in protobuf text format.")
    public File file = null;

    @Parameter(
        names = {"--digest", "-d"},
        converter = DigestConverter.class,
        description = "Action digest in the form hex_hash/size_bytes. Use for V2 API.")
    public Digest actionDigest = null;

    @Parameter(
        names = {"--limit", "-l"},
        description = "The maximum number of input/output files to list.")
    public int limit = 100;
  }

  @Parameters(commandDescription = "Parse and display an ActionResult.", separators = "=")
  public static class ShowActionResultCommand {
    @Parameter(
        names = {"--textproto", "-p"},
        required = true,
        converter = FileConverter.class,
        description = "Path to a ActionResult proto stored in protobuf text format.")
    public File file = null;

    @Parameter(
        names = {"--limit", "-l"},
        description = "The maximum number of output files to list.")
    public int limit = 100;
  }

  @Parameters(
      commandDescription =
          "Write all log entries from a Bazel gRPC log to standard output. The Bazel gRPC log "
              + "consists of a sequence of delimited serialized LogEntry protobufs, as produced by "
              + "the method LogEntry.writeDelimitedTo(OutputStream).",
      separators = "=")
  public static class PrintLogCommand {
    @Parameter(
        names = {"--group_by_action", "-g"},
        description =
            "Display entries grouped by action instead of individually. Entries are printed in order "
                + "of their call started timestamps (earliest first). Entries without action-id"
                + "metadata are skipped.")
    public boolean groupByAction;
  }

  @Parameters(
      commandDescription =
          "Sets up a directory and Docker command to locally run a single action"
              + "given its Action proto. This requires the Action's inputs to be stored in CAS so that "
              + "they can be retrieved.",
      separators = "=")
  public static class RunCommand {
    @Parameter(
        names = {"--textproto", "-p"},
        converter = FileConverter.class,
        description =
            "Path to the Action proto stored in protobuf text format to be run in the "
                + "container.")
    public File file = null;

    @Parameter(
        names = {"--digest", "-d"},
        converter = DigestConverter.class,
        description = "Action digest in the form hex_hash/size_bytes. Use for V2 API.")
    public Digest actionDigest = null;

    @Parameter(
        names = {"--path", "-o"},
        converter = PathConverter.class,
        description = "Path to set up the action inputs in.")
    public Path path = null;
  }

  /** Converter for hex_hash/size_bytes string to a Digest object. */
  public static class DigestConverter implements IStringConverter<Digest> {
    @Override
    public Digest convert(String input) {
      int slash = input.indexOf('/');
      if (slash < 0) {
        throw new ParameterException("'" + input + "' is not as hex_hash/size_bytes");
      }
      try {
        long size = Long.parseLong(input.substring(slash + 1));
        return DigestUtil.buildDigest(input.substring(0, slash), size);
      } catch (NumberFormatException e) {
        throw new ParameterException("'" + input + "' is not a hex_hash/size_bytes: " + e);
      }
    }
  }
}
