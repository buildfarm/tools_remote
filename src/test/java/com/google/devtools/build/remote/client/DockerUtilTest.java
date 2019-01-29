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

import static com.google.common.truth.Truth.assertThat;

import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Command.EnvironmentVariable;
import build.bazel.remote.execution.v2.Platform;
import build.bazel.remote.execution.v2.Platform.Property;
import com.google.common.hash.Hashing;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link DockerUtil}. */
@RunWith(JUnit4.class)
public class DockerUtilTest {
  private static final DigestUtil DIGEST_UTIL = new DigestUtil(Hashing.sha256());

  private static class MockUidGetter extends DockerUtil.UidGetter {
    long uid;

    public MockUidGetter (long uid) {
      this.uid = uid;
    }

    @Override
    public long getUid() {
      return uid;
    }
  }

  static final Command command =
        Command.newBuilder()
            .addArguments("/bin/echo")
            .addArguments("hello")
            .addArguments("escape<'>")
            .addEnvironmentVariables(
                EnvironmentVariable.newBuilder().setName("PATH").setValue("/home/test"))
            .setPlatform(
                Platform.newBuilder()
                    .addProperties(
                        Property.newBuilder()
                            .setName("container-image")
                            .setValue("docker://gcr.io/image")))
            .build();

  @Test
  public void testGetDockerCommandNoUid() {
    DockerUtil util = new DockerUtil(new MockUidGetter(-1));
    String commandLine = util.getDockerCommand(command, "/tmp/test");

    assertThat(commandLine)
          .isEqualTo(
              "docker run -v /tmp/test:/tmp/test-docker -w /tmp/test-docker -e 'PATH=/home/test' "
                  + "gcr.io/image /bin/echo hello 'escape<'\\''>'");
  }

  @Test
  public void testGetDockerCommandUid() {
    DockerUtil util = new DockerUtil(new MockUidGetter(14242));
    String commandLine = util.getDockerCommand(command, "/tmp/test");

    if (System.getProperty("os.name").startsWith("Windows")) {
      assertThat(commandLine)
          .isEqualTo(
              "docker run -v /tmp/test:/tmp/test-docker "
                  + "-w /tmp/test-docker -e 'PATH=/home/test' "
                  + "gcr.io/image /bin/echo hello 'escape<'\\''>'");
    } else {
      assertThat(commandLine)
          .isEqualTo(
              "docker run -u 14242 -v /tmp/test:/tmp/test-docker "
                  + "-w /tmp/test-docker -e 'PATH=/home/test' "
                  + "gcr.io/image /bin/echo hello 'escape<'\\''>'");
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void testGetDockerCommandNoPlatformFail() {
    Command command =
        Command.newBuilder()
            .addArguments("/bin/echo")
            .addArguments("hello")
            .addArguments("escape<'>")
            .addEnvironmentVariables(
                EnvironmentVariable.newBuilder().setName("PATH").setValue("/home/test"))
            .build();
    DockerUtil util = new DockerUtil(new MockUidGetter(-1));
    util.getDockerCommand(command, "/tmp/test");
  }
}
