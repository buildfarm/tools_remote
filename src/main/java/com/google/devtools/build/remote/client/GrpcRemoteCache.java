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

import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamGrpc.ByteStreamBlockingStub;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.devtools.remoteexecution.v1test.Digest;
import io.grpc.CallCredentials;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/** A RemoteActionCache implementation that uses gRPC calls to a remote cache server. */
public class GrpcRemoteCache extends AbstractRemoteActionCache {

  private final RemoteOptions options;
  private final CallCredentials credentials;
  private final Channel channel;

  public GrpcRemoteCache(
      RemoteOptions options, AuthAndTLSOptions authAndTLSOptions, DigestUtil digestUtil)
      throws IOException {
    this(
        GoogleAuthUtils.newChannel(options.remoteCache, authAndTLSOptions),
        GoogleAuthUtils.newCallCredentials(authAndTLSOptions),
        options,
        digestUtil);
  }

  public GrpcRemoteCache(
      Channel channel, CallCredentials credentials, RemoteOptions options, DigestUtil digestUtil) {
    super(digestUtil);
    this.options = options;
    this.credentials = credentials;
    this.channel = channel;
  }

  public static boolean isRemoteCacheOptions(RemoteOptions options) {
    return options.remoteCache != null;
  }

  private ByteStreamBlockingStub bsBlockingStub() {
    return ByteStreamGrpc.newBlockingStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
        .withCallCredentials(credentials)
        .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
  }

  @Override
  public void downloadBlob(Digest digest, OutputStream stream) throws IOException {
    if (digest.getSizeBytes() == 0) {
      return;
    }
    readBlob(digest, stream);
  }

  private void readBlob(Digest digest, OutputStream stream) throws IOException {
    String resourceName = "";
    if (!options.remoteInstanceName.isEmpty()) {
      resourceName += options.remoteInstanceName + "/";
    }
    resourceName += "blobs/" + digest.getHash() + "/" + digest.getSizeBytes();
    try {
      Iterator<ReadResponse> replies =
          bsBlockingStub().read(ReadRequest.newBuilder().setResourceName(resourceName).build());
      while (replies.hasNext()) {
        replies.next().getData().writeTo(stream);
      }
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        throw new CacheNotFoundException(digest);
      }
      throw e;
    }
  }
}
