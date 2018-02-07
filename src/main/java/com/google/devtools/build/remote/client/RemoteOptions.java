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

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

/** Options for remote cache. */
@Parameters(separators = "=")
public final class RemoteOptions {
  @Parameter(
    names = "--remote_http_cache",
    description =
        "A base URL of a HTTP caching service. Both http:// and https:// are supported. BLOBs are "
            + "are stored with PUT and retrieved with GET. See remote/README.md for more"
            + "information."
  )
  public String remoteHttpCache = null;

  @Parameter(
    names = "--remote_cache",
    description = "HOST or HOST:PORT of a remote caching endpoint."
  )
  public String remoteCache = null;

  @Parameter(
    names = "--remote_timeout",
    description = "The maximum number of seconds to wait for remote execution and cache calls."
  )
  public int remoteTimeout = 60;

  @Parameter(
    names = "--remote_instance_name",
    description = "Value to pass as instance_name in the remote execution API."
  )
  public String remoteInstanceName = "";
}
