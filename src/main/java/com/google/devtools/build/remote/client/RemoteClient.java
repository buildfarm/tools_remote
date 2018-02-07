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

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import com.google.common.hash.Hashing;
import com.google.devtools.remoteexecution.v1test.RequestMetadata;
import com.google.devtools.remoteexecution.v1test.ToolDetails;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** A standalone client for interacting with remote caches in Bazel. */
public class RemoteClient {
  public static void main(String[] args) throws Exception {
    AuthAndTLSOptions authAndTlsOptions = new AuthAndTLSOptions();
    RemoteOptions remoteOptions = new RemoteOptions();
    RemoteClientOptions remoteClientOptions = new RemoteClientOptions();
    JCommander optionsParser =
        JCommander.newBuilder()
            .addObject(authAndTlsOptions)
            .addObject(remoteOptions)
            .addObject(remoteClientOptions)
            .build();

    try {
      optionsParser.parse(args);
    } catch (ParameterException e) {
      System.err.println("Unable to parse options: " + e.getLocalizedMessage());
      optionsParser.usage();
      return;
    }

    if (remoteClientOptions.digest == null) {
      System.err.println("Please specify a digest for a blob to download.");
      return;
    }

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
      throw new IllegalAccessException("Only gRPC remote cache supported currently.");
    }

    OutputStream output;
    if (remoteClientOptions.output != null) {
      File file = new File(remoteClientOptions.output);
      output = new FileOutputStream(file);

      if (!file.exists()) {
        file.createNewFile();
      }
    } else {
      output = System.out;
    }

    try {
      cache.downloadBlob(remoteClientOptions.digest, output);
    } catch (CacheNotFoundException e) {
      System.err.println("Error: " + e);
    } finally {
      output.close();
    }
  }
}
