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

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.google.devtools.build.remote.client.RemoteClientOptions.CatCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.GetDirCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.GetOutDirCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.LsCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.LsOutDirCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.PrintLogCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.RunCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.ShowActionCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.ShowActionResultCommand;
import com.google.devtools.remoteexecution.v1test.Action;
import com.google.devtools.remoteexecution.v1test.ActionResult;
import com.google.devtools.remoteexecution.v1test.Command;
import com.google.devtools.remoteexecution.v1test.Command.EnvironmentVariable;
import com.google.devtools.remoteexecution.v1test.Digest;
import com.google.devtools.remoteexecution.v1test.Directory;
import com.google.devtools.remoteexecution.v1test.DirectoryNode;
import com.google.devtools.remoteexecution.v1test.FileNode;
import com.google.devtools.remoteexecution.v1test.OutputDirectory;
import com.google.devtools.remoteexecution.v1test.OutputFile;
import com.google.devtools.remoteexecution.v1test.RequestMetadata;
import com.google.devtools.remoteexecution.v1test.ToolDetails;
import com.google.devtools.remoteexecution.v1test.Tree;
import com.google.protobuf.TextFormat;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import io.grpc.Status;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A standalone client for interacting with remote caches in Bazel. */
public class RemoteClient {

  private final AbstractRemoteActionCache cache;
  private final DigestUtil digestUtil;

  private RemoteClient(AbstractRemoteActionCache cache) {
    this.cache = cache;
    this.digestUtil = cache.getDigestUtil();
  }

  public AbstractRemoteActionCache getCache() {
    return cache;
  }

  // Prints the details (path and digest) of a DirectoryNode.
  private void printDirectoryNodeDetails(DirectoryNode directoryNode, Path directoryPath) {
    System.out.printf(
        "%s [Directory digest: %s]\n",
        directoryPath.toString(), digestUtil.toString(directoryNode.getDigest()));
  }

  // Prints the details (path and content digest) of a FileNode.
  private void printFileNodeDetails(FileNode fileNode, Path filePath) {
    System.out.printf(
        "%s [File content digest: %s]\n",
        filePath.toString(), digestUtil.toString(fileNode.getDigest()));
  }

  // List the files in a directory assuming the directory is at the given path. Returns the number
  // of files listed.
  private int listFileNodes(Path path, Directory dir, int limit) {
    int numFilesListed = 0;
    for (FileNode child : dir.getFilesList()) {
      if (numFilesListed >= limit) {
        System.out.println(" ... (too many files to list, some omitted)");
        break;
      }
      Path childPath = path.resolve(child.getName());
      printFileNodeDetails(child, childPath);
      numFilesListed++;
    }
    return numFilesListed;
  }

  // Recursively list directory files/subdirectories with digests. Returns the number of files
  // listed.
  private int listDirectory(Path path, Directory dir, Map<Digest, Directory> childrenMap, int limit)
      throws IOException {
    // Try to list the files in this directory before listing the directories.
    int numFilesListed = listFileNodes(path, dir, limit);
    if (numFilesListed >= limit) {
      return numFilesListed;
    }
    for (DirectoryNode child : dir.getDirectoriesList()) {
      Path childPath = path.resolve(child.getName());
      printDirectoryNodeDetails(child, childPath);
      Digest childDigest = child.getDigest();
      Directory childDir = childrenMap.get(childDigest);
      numFilesListed += listDirectory(childPath, childDir, childrenMap, limit - numFilesListed);
      if (numFilesListed >= limit) {
        return numFilesListed;
      }
    }
    return numFilesListed;
  }

  // Recursively list OutputDirectory with digests.
  private void listOutputDirectory(OutputDirectory dir, int limit) throws IOException {
    Tree tree;
    try {
      tree = Tree.parseFrom(cache.downloadBlob(dir.getTreeDigest()));
    } catch (IOException e) {
      throw new IOException("Failed to obtain Tree for OutputDirectory.", e);
    }
    Map<Digest, Directory> childrenMap = new HashMap<>();
    for (Directory child : tree.getChildrenList()) {
      childrenMap.put(digestUtil.compute(child), child);
    }
    System.out.printf("OutputDirectory rooted at %s:\n", dir.getPath());
    listDirectory(Paths.get(""), tree.getRoot(), childrenMap, limit);
  }

  // Recursively list directory files/subdirectories with digests given a Tree of the directory.
  private void listTree(Path path, Tree tree, int limit) throws IOException {
    Map<Digest, Directory> childrenMap = new HashMap<>();
    for (Directory child : tree.getChildrenList()) {
      childrenMap.put(digestUtil.compute(child), child);
    }
    listDirectory(path, tree.getRoot(), childrenMap, limit);
  }

  private static int getNumFiles(Tree tree) {
    return tree.getChildrenList().stream().mapToInt(dir -> dir.getFilesCount()).sum();
  }

  // Outputs a bash executable line that corresponds to executing the given command.
  private static void printCommand(Command command) {
    for (EnvironmentVariable var : command.getEnvironmentVariablesList()) {
      System.out.printf("%s=%s \\\n", var.getName(), ShellEscaper.escapeString(var.getValue()));
    }
    System.out.print("  ");

    System.out.println(ShellEscaper.escapeJoinAll(command.getArgumentsList()));
  }

  private static void printList(List<String> list, int limit) {
    if (list.isEmpty()) {
      System.out.println("(none)");
      return;
    }
    list.stream().limit(limit).forEach(name -> System.out.println(name));
    if (list.size() > limit) {
      System.out.println(" ... (too many to list, some omitted)");
    }
  }

  // Output for print action command.
  private void printAction(Action action, int limit) throws IOException {
    Command command;
    try {
      command = Command.parseFrom(cache.downloadBlob(action.getCommandDigest()));
    } catch (IOException e) {
      throw new IOException("Could not obtain Command from digest.", e);
    }
    System.out.printf("Command [digest: %s]:\n", digestUtil.toString(action.getCommandDigest()));
    printCommand(command);

    Tree tree = cache.getTree(action.getInputRootDigest());
    System.out.printf(
        "\nInput files [total: %d, root Directory digest: %s]:\n",
        getNumFiles(tree), digestUtil.toString(action.getInputRootDigest()));
    listTree(Paths.get(""), tree, limit);

    System.out.println("\nOutput files:");
    printList(action.getOutputFilesList(), limit);

    System.out.println("\nOutput directories:");
    printList(action.getOutputDirectoriesList(), limit);

    System.out.println("\nPlatform:");
    if (action.hasPlatform() && !action.getPlatform().getPropertiesList().isEmpty()) {
      System.out.println(action.getPlatform().toString());
    } else {
      System.out.println("(none)");
    }
  }

  // Display output file (either digest or raw bytes).
  private void printOutputFile(OutputFile file, boolean showRawOutputs) {
    String contentString;
    if (file.hasDigest()) {
      contentString = "Content digest: " + digestUtil.toString(file.getDigest());
    } else if (showRawOutputs) {
      contentString =
          String.format(
              "Raw contents: '%s', size (bytes): %d",
              file.getContent().toStringUtf8(), file.getContent().size());
    } else {
      contentString = "Raw contents (not printed)";
    }
    System.out.printf(
        "%s [%s, executable: %b]\n", file.getPath(), contentString, file.getIsExecutable());
  }

  // Output for print action result command.
  private void printActionResult(ActionResult result, int limit, boolean showRawOutputs)
      throws IOException {
    System.out.println("Output files:");
    result
        .getOutputFilesList()
        .stream()
        .limit(limit)
        .forEach(name -> printOutputFile(name, showRawOutputs));
    if (result.getOutputFilesList().size() > limit) {
      System.out.println(" ... (too many to list, some omitted)");
    } else if (result.getOutputFilesList().isEmpty()) {
      System.out.println("(none)");
    }

    System.out.println("\nOutput directories:");
    if (!result.getOutputDirectoriesList().isEmpty()) {
      for (OutputDirectory dir : result.getOutputDirectoriesList()) {
        listOutputDirectory(dir, limit);
      }
    } else {
      System.out.println("(none)");
    }

    System.out.println(String.format("\nExit code: %d", result.getExitCode()));

    System.out.println("\nStderr buffer:");
    if (result.hasStderrDigest()) {
      byte[] stderr = cache.downloadBlob(result.getStderrDigest());
      System.out.println(new String(stderr, UTF_8));
    } else {
      System.out.println(result.getStderrRaw().toStringUtf8());
    }

    System.out.println("\nStdout buffer:");
    if (result.hasStdoutDigest()) {
      byte[] stdout = cache.downloadBlob(result.getStdoutDigest());
      System.out.println(new String(stdout, UTF_8));
    } else {
      System.out.println(result.getStdoutRaw().toStringUtf8());
    }
  }

  // Given a docker run action, sets up a directory for an Action to be run in (download Action
  // inputs, set up output directories), and display a docker command that will run the Action.
  private void setupDocker(Action action, Path root) throws IOException {
    System.out.printf("Setting up Action in directory %s...\n", root.toAbsolutePath());
    Command command;
    try {
      command = Command.parseFrom(cache.downloadBlob(action.getCommandDigest()));
    } catch (IOException e) {
      throw new IOException("Failed to get Command for Action.", e);
    }

    try {
      cache.downloadDirectory(root, action.getInputRootDigest());
    } catch (IOException e) {
      throw new IOException("Failed to download action inputs.", e);
    }

    // Setup directory structure for outputs.
    for (String output : action.getOutputFilesList()) {
      Path file = root.resolve(output);
      if (java.nio.file.Files.exists(file)) {
        throw new FileSystemAlreadyExistsException("Output file already exists: " + file);
      }
      Files.createParentDirs(file.toFile());
    }
    for (String output : action.getOutputDirectoriesList()) {
      Path dir = root.resolve(output);
      if (java.nio.file.Files.exists(dir)) {
        throw new FileSystemAlreadyExistsException("Output directory already exists: " + dir);
      }
      java.nio.file.Files.createDirectories(dir);
    }
    String dockerCommand = DockerUtil.getDockerCommand(action, command, root.toString());
    System.out.println("\nSuccessfully setup Action in directory " + root.toString() + ".");
    System.out.println("\nTo run the Action locally, run:");
    System.out.println("  " + dockerCommand);
  }

  private static RemoteClient makeClientWithOptions(
      RemoteOptions remoteOptions, AuthAndTLSOptions authAndTlsOptions) throws IOException {
    DigestUtil digestUtil = new DigestUtil(Hashing.sha256());
    AbstractRemoteActionCache cache;

    if (GrpcRemoteCache.isRemoteCacheOptions(remoteOptions)) {
      cache = new GrpcRemoteCache(remoteOptions, authAndTlsOptions, digestUtil);
      RequestMetadata metadata =
          RequestMetadata.newBuilder()
              .setToolDetails(ToolDetails.newBuilder().setToolName("remote_client"))
              .build();
      TracingMetadataUtils.contextWithMetadata(metadata).attach();
    } else {
      throw new UnsupportedOperationException("Only gRPC remote cache supported currently.");
    }
    return new RemoteClient(cache);
  }

  private static void doPrintLog(String grpcLogFile, PrintLogCommand options) throws IOException {
    LogParserUtils parser = new LogParserUtils(grpcLogFile);
    parser.printLog(options);
  }

  private static void doLs(LsCommand options, RemoteClient client) throws IOException {
    Tree tree = client.getCache().getTree(options.digest);
    client.listTree(Paths.get(""), tree, options.limit);
  }

  private static void doLsOutDir(LsOutDirCommand options, RemoteClient client) throws IOException {
    OutputDirectory dir;
    try {
      dir = OutputDirectory.parseFrom(client.getCache().downloadBlob(options.digest));
    } catch (IOException e) {
      throw new IOException("Failed to obtain OutputDirectory.", e);
    }
    client.listOutputDirectory(dir, options.limit);
  }

  private static void doGetDir(GetDirCommand options, RemoteClient client) throws IOException {
    client.getCache().downloadDirectory(options.path, options.digest);
  }

  private static void doGetOutDir(GetOutDirCommand options, RemoteClient client)
      throws IOException {
    OutputDirectory dir;
    try {
      dir = OutputDirectory.parseFrom(client.getCache().downloadBlob(options.digest));
    } catch (IOException e) {
      throw new IOException("Failed to obtain OutputDirectory.", e);
    }
    client.getCache().downloadOutputDirectory(dir, options.path);
  }

  private static void doCat(CatCommand options, RemoteClient client) throws IOException {
    OutputStream output;
    if (options.file != null) {
      output = new FileOutputStream(options.file);

      if (!options.file.exists()) {
        options.file.createNewFile();
      }
    } else {
      output = System.out;
    }

    try {
      client.getCache().downloadBlob(options.digest, output);
    } catch (CacheNotFoundException e) {
      System.err.println("Error: " + e);
    } finally {
      output.close();
    }
  }

  private static void doShowAction(ShowActionCommand options, RemoteClient client)
      throws IOException {
    Action.Builder builder = Action.newBuilder();
    FileInputStream fin = new FileInputStream(options.file);
    TextFormat.getParser().merge(new InputStreamReader(fin), builder);

    client.printAction(builder.build(), options.limit);
  }

  private static void doShowActionResult(ShowActionResultCommand options, RemoteClient client)
      throws IOException {
    ActionResult.Builder builder = ActionResult.newBuilder();
    FileInputStream fin = new FileInputStream(options.file);
    TextFormat.getParser().merge(new InputStreamReader(fin), builder);
    client.printActionResult(builder.build(), options.limit, options.showRawOutputs);
  }

  private static void doRun(RunCommand options, RemoteClient client) throws IOException {
    Action.Builder builder = Action.newBuilder();
    FileInputStream fin = new FileInputStream(options.file);
    TextFormat.getParser().merge(new InputStreamReader(fin), builder);
    client.setupDocker(
        builder.build(), options.path != null ? options.path : Files.createTempDir().toPath());
  }

  public static void main(String[] args) throws Exception {
    try {
      selectAndPerformCommand(args);
    } catch (io.grpc.StatusRuntimeException e) {
      Status s = Status.fromThrowable(e);
      if (s.getCode() == Status.Code.INTERNAL && s.getDescription().contains("http2")) {
        System.err.println("http2 exception. Did you forget --tls_enabled?");
      }
      throw e;
    }
  }

  public static void selectAndPerformCommand(String[] args) throws Exception {
    AuthAndTLSOptions authAndTlsOptions = new AuthAndTLSOptions();
    RemoteOptions remoteOptions = new RemoteOptions();
    RemoteClientOptions remoteClientOptions = new RemoteClientOptions();
    LsCommand lsCommand = new LsCommand();
    LsOutDirCommand lsOutDirCommand = new LsOutDirCommand();
    GetDirCommand getDirCommand = new GetDirCommand();
    GetOutDirCommand getOutDirCommand = new GetOutDirCommand();
    CatCommand catCommand = new CatCommand();
    ShowActionCommand showActionCommand = new ShowActionCommand();
    ShowActionResultCommand showActionResultCommand = new ShowActionResultCommand();
    PrintLogCommand printLogCommand = new PrintLogCommand();
    RunCommand runCommand = new RunCommand();

    JCommander optionsParser =
        JCommander.newBuilder()
            .programName("remote_client")
            .addObject(authAndTlsOptions)
            .addObject(remoteOptions)
            .addObject(remoteClientOptions)
            .addCommand("ls", lsCommand)
            .addCommand("lsoutdir", lsOutDirCommand)
            .addCommand("getdir", getDirCommand)
            .addCommand("getoutdir", getOutDirCommand)
            .addCommand("cat", catCommand)
            .addCommand("show_action", showActionCommand, "sa")
            .addCommand("show_action_result", showActionResultCommand, "sar")
            .addCommand("printlog", printLogCommand)
            .addCommand("run", runCommand)
            .build();

    try {
      optionsParser.parse(args);
    } catch (ParameterException e) {
      System.err.println("Unable to parse options: " + e.getLocalizedMessage());
      optionsParser.usage();
      System.exit(1);
    }

    if (remoteClientOptions.help) {
      optionsParser.usage();
      return;
    }

    if (optionsParser.getParsedCommand() == null) {
      System.err.println("No command specified.");
      optionsParser.usage();
      System.exit(1);
    }

    switch (optionsParser.getParsedCommand()) {
      case "printlog":
        doPrintLog(remoteClientOptions.grpcLog, printLogCommand);
        break;
      case "ls":
        doLs(lsCommand, makeClientWithOptions(remoteOptions, authAndTlsOptions));
        break;
      case "lsoutdir":
        doLsOutDir(lsOutDirCommand, makeClientWithOptions(remoteOptions, authAndTlsOptions));
        break;
      case "getdir":
        doGetDir(getDirCommand, makeClientWithOptions(remoteOptions, authAndTlsOptions));
        break;
      case "getoutdir":
        doGetOutDir(getOutDirCommand, makeClientWithOptions(remoteOptions, authAndTlsOptions));
        break;
      case "cat":
        doCat(catCommand, makeClientWithOptions(remoteOptions, authAndTlsOptions));
        break;
      case "show_action":
        doShowAction(showActionCommand, makeClientWithOptions(remoteOptions, authAndTlsOptions));
        break;
      case "show_action_result":
        doShowActionResult(
            showActionResultCommand, makeClientWithOptions(remoteOptions, authAndTlsOptions));
        break;
      case "run":
        doRun(runCommand, makeClientWithOptions(remoteOptions, authAndTlsOptions));
        break;
      default:
        throw new IllegalArgumentException("Unknown command.");
    }
  }
}
