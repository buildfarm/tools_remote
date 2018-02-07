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
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import com.google.devtools.remoteexecution.v1test.Digest;

/** Options for operation of a remote client. */
@Parameters(separators = "=")
public final class RemoteClientOptions {
  @Parameter(names = "--help", description = "This message.", help = true)
  private boolean help;

  @Parameter(
    names = "--digest",
    converter = DigestConverter.class,
    description = "A blob digest to download in the format hex_hash/size_bytes."
  )
  public Digest digest = null;

  @Parameter(
    names = "--output",
    description =
        "If specified, a path to download the blob into. "
            + "Otherwise, contents will be printed to stdout."
  )
  public String output = null;

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
