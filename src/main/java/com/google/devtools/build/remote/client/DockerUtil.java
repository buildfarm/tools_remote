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

import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Command.EnvironmentVariable;
import build.bazel.remote.execution.v2.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

public final class DockerUtil {
  private static final String CONTAINER_IMAGE_ENTRY_NAME = "container-image";
  private static final String DOCKER_IMAGE_PREFIX = "docker://";

  @VisibleForTesting
  static class UidGetter {
    /**
     * Gets uid of the current user. If the uid could not be fetched, prints a message to stderr and
     * returns -1.
     */
    @VisibleForTesting
    long getUid() {
      ProcessBuilder processBuilder = new ProcessBuilder();
      processBuilder.command("id", "-u");
      try {
        InputStream stdout = processBuilder.start().getInputStream();
        byte[] output = ByteStreams.toByteArray(stdout);
        return Long.parseLong(new String(output).trim());
      } catch (IOException | NumberFormatException e) {
        System.err.printf(
            "Could not fetch UID for passing to Docker container. The provided docker "
                + "command will not specify a uid (error: %s)\n",
            e.toString());
        return -1;
      }
    }
  }

  @VisibleForTesting
  static UidGetter uidGetter = new UidGetter();

  /**
   * Checks Action for Docker container definition.
   *
   * @return The docker container for the command. If no container could be found, returns null.
   */
  private static @Nullable String dockerContainer(Command command) {
    String result = null;
    for (Platform.Property property : command.getPlatform().getPropertiesList()) {
      if (property.getName().equals(CONTAINER_IMAGE_ENTRY_NAME)) {
        if (result != null) {
          // Multiple container name entries
          throw new IllegalArgumentException(
              String.format(
                  "Multiple entries for %s in command.Platform", CONTAINER_IMAGE_ENTRY_NAME));
        }
        result = property.getValue();
        if (!result.startsWith(DOCKER_IMAGE_PREFIX)) {
          throw new IllegalArgumentException(
              String.format(
                  "%s: Docker images must be stored in gcr.io with an image spec in the form "
                      + "'docker://gcr.io/{IMAGE_NAME}'",
                  CONTAINER_IMAGE_ENTRY_NAME));
        }
        result = result.substring(DOCKER_IMAGE_PREFIX.length());
      }
    }
    return result;
  }

  /**
   * Outputs a Docker command that will execute the given action in the given path.
   *
   * @param action The Action to be executed in the output docker container command.
   * @param command The Command of the Action being executed. This must match the Command that is
   *     referred to from the input parameter Action.
   * @param workingPath The path that is to be the working directory that the Action is to be
   *     executed in.
   */
  public static String getDockerCommand(Command command, String workingPath) {
    String container = dockerContainer(command);
    if (container == null) {
      throw new IllegalArgumentException("No docker image specified in given Command.");
    }
    List<String> commandElements = new ArrayList<>();
    commandElements.add("docker");
    commandElements.add("run");

    long uid = uidGetter.getUid();
    if (uid >= 0) {
      commandElements.add("-u");
      commandElements.add(Long.toString(uid));
    }

    String dockerPathString = workingPath + "-docker";
    commandElements.add("-v");
    commandElements.add(workingPath + ":" + dockerPathString);
    commandElements.add("-w");
    commandElements.add(dockerPathString);

    for (EnvironmentVariable var : command.getEnvironmentVariablesList()) {
      commandElements.add("-e");
      commandElements.add(var.getName() + "=" + var.getValue());
    }

    commandElements.add(container);
    commandElements.addAll(command.getArgumentsList());

    return ShellEscaper.escapeJoinAll(commandElements);
  }
}
