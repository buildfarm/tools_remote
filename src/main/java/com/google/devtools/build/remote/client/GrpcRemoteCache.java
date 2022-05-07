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

import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageBlockingStub;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.GetTreeRequest;
import build.bazel.remote.execution.v2.GetTreeResponse;
import build.bazel.remote.execution.v2.Tree;
import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamGrpc.ByteStreamBlockingStub;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import io.grpc.CallCredentials;
import io.grpc.ClientInterceptor;
import io.grpc.Channel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
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

  private static ClientInterceptor customHeadersInterceptor(Map<String, String> headers) {
    Metadata metadata = new Metadata();
    for (Map.Entry<String,String> entry : headers.entrySet()) {
      metadata.put(
        Metadata.Key.of(entry.getKey(), Metadata.ASCII_STRING_MARSHALLER),
        entry.getValue()
      );
    }
    return MetadataUtils.newAttachHeadersInterceptor(metadata);
  }

  private ContentAddressableStorageBlockingStub casBlockingStub() {
    return ContentAddressableStorageGrpc.newBlockingStub(channel)
        .withInterceptors(
          TracingMetadataUtils.attachMetadataFromContextInterceptor(),
          customHeadersInterceptor(options.remoteHeaders)
        )
        .withCallCredentials(credentials)
        .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
  }

  private ByteStreamBlockingStub bsBlockingStub() {
    return ByteStreamGrpc.newBlockingStub(channel)
        .withInterceptors(
          TracingMetadataUtils.attachMetadataFromContextInterceptor(),
          customHeadersInterceptor(options.remoteHeaders)
        )
        .withCallCredentials(credentials)
        .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
  }

  /**
   * Download a tree with the {@link Directory} given by digest as the root directory of the tree.
   * This method attempts to retrieve the {@link Tree} using the GetTree RPC.
   *
   * @param rootDigest The digest of the root {@link Directory} of the tree
   * @return A tree with the given directory as the root.
   * @throws IOException in the case that retrieving the blobs required to reconstruct the tree
   *     failed.
   */
  @Override
  public Tree getTree(Digest rootDigest) throws IOException {
    Directory dir;
    try {
      dir = Directory.parseFrom(downloadBlob(rootDigest));
    } catch (IOException e) {
      throw new IOException("Failed to download root Directory of tree.", e);
    }

    Tree.Builder result = Tree.newBuilder().setRoot(dir);

    GetTreeRequest.Builder requestBuilder =
        GetTreeRequest.newBuilder()
            .setRootDigest(rootDigest)
            .setInstanceName(options.remoteInstanceName);

    Iterator<GetTreeResponse> responses = casBlockingStub().getTree(requestBuilder.build());
    while (responses.hasNext()) {
      result.addAllChildren(responses.next().getDirectoriesList());
    }

    return result.build();
  }

  @Override
  protected void downloadBlob(Digest digest, Path dest) throws IOException {
    try (OutputStream out = Files.newOutputStream(dest)) {
      readBlob(digest, out);
    }
  }

  @Override
  protected byte[] downloadBlob(Digest digest) throws IOException {
    if (digest.getSizeBytes() == 0) {
      return new byte[0];
    }
    ByteArrayOutputStream stream = new ByteArrayOutputStream((int) digest.getSizeBytes());
    readBlob(digest, stream);
    return stream.toByteArray();
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
