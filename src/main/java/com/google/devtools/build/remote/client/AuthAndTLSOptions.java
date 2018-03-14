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

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.util.List;

/** Common options for authentication and TLS. */
@Parameters(separators = "=")
public final class AuthAndTLSOptions {
  @Parameter(
    names = "--google_default_credentials",
    arity = 1,
    description =
        "Whether to use 'Google Application Default Credentials' for authentication."
            + " See https://cloud.google.com/docs/authentication for details. Disabled by default."
  )
  public boolean useGoogleDefaultCredentials = false;

  @Parameter(
    names = "--google_auth_scopes",
    listConverter = CommaSeparatedOptionListConverter.class,
    description = "A comma-separated list of Google Cloud authentication scopes."
  )
  public List<String> googleAuthScopes =
      ImmutableList.of("https://www.googleapis.com/auth/cloud-platform");

  @Parameter(
    names = "--google_credentials",
    description =
        "Specifies the file to get authentication credentials from. See "
            + "https://cloud.google.com/docs/authentication for details"
  )
  public String googleCredentials = null;

  @Parameter(names = "--tls_enabled", arity = 1, description = "Specifies whether to use TLS.")
  public boolean tlsEnabled = false;

  @Parameter(
    names = "--tls_certificate",
    description = "Specify the TLS client certificate to use."
  )
  public String tlsCertificate = null;

  @Parameter(
    names = "--tls_authority_override",
    description =
        "TESTING ONLY! Can be used with a self-signed certificate to consider the specified "
            + "value a valid TLS authority."
  )
  public String tlsAuthorityOverride = null;

  /** A converter for splitting comma-separated string inputs into lists of strings. */
  public static class CommaSeparatedOptionListConverter implements IStringConverter<List<String>> {
    @Override
    public List<String> convert(String input) {
      if (input.isEmpty()) {
        return ImmutableList.of();
      } else {
        return ImmutableList.copyOf(Splitter.on(',').split(input));
      }
    }
  }
}
