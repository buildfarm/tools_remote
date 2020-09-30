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
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.bytestream.ByteStreamProto.WriteRequest;
import com.google.bytestream.ByteStreamProto.WriteResponse;
import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import io.grpc.CallCredentials;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
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

  private ContentAddressableStorageBlockingStub casBlockingStub() {
    return ContentAddressableStorageGrpc.newBlockingStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
        .withCallCredentials(credentials)
        .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
  }

  private ByteStreamBlockingStub bsBlockingStub() {
    return ByteStreamGrpc.newBlockingStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
        .withCallCredentials(credentials)
        .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
  }


  private ByteStreamStub bsAsyncStub() {
    return ByteStreamGrpc.newStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
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

  private String getResourceNameForRead(Digest digest) {
    // For reads, `resource_name` must be of form
    //   `[<instance_name>/]blobs/<hash>/<size>`.
    // https://github.com/bazelbuild/remote-apis/blob/f54876595da9f2c2d66c98c318d00b60fd64900b/build/bazel/remote/execution/v2/remote_execution.proto#L232
    StringBuilder resourceName = new StringBuilder();
    if (!options.remoteInstanceName.isEmpty()) {
      resourceName.append(options.remoteInstanceName).append('/');
    }
    resourceName
        .append("blobs")
        .append('/')
        .append(digest.getHash())
        .append('/')
        .append(digest.getSizeBytes());
    return resourceName.toString();
  }

  private void readBlob(Digest digest, OutputStream stream) throws IOException {
    String resourceName = getResourceNameForRead(digest);
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

  private String getResourceNameForWrite(Digest digest) {
    // For writes, `resource_name` must be of form
    //   `[<instance_name>/]uploads/<uuid>/blobs/<hash>/<size>[/<metadata>]`.
    // https://github.com/bazelbuild/remote-apis/blob/f54876595da9f2c2d66c98c318d00b60fd64900b/build/bazel/remote/execution/v2/remote_execution.proto#L194
    StringBuilder resourceName = new StringBuilder();
    if (!options.remoteInstanceName.isEmpty()) {
      resourceName.append(options.remoteInstanceName).append('/');
    }
    resourceName
        .append("uploads")
        .append('/')
        .append(UUID.randomUUID().toString())
        .append('/')
        .append("blobs")
        .append('/')
        .append(digest.getHash())
        .append('/')
        .append(digest.getSizeBytes());
    return resourceName.toString();
  }

  @Override
  public void uploadBlob(Digest digest, InputStream source) throws IOException {
    if (digest.getSizeBytes() < 1) {
      // The empty file is always available in the CAS.
      return;
    }

    SettableFuture<WriteResponse> result = SettableFuture.create();
    StreamObserver<WriteRequest> streamObserver =
        bsAsyncStub().write(new StreamObserver<WriteResponse>() {
          private WriteResponse writeResponse = null;
          @Override
          public void onNext(WriteResponse writeResponse) {
            Preconditions.checkState(this.writeResponse == null);
            this.writeResponse = writeResponse;
          }

          @Override
          public void onCompleted() {
            Preconditions.checkState(this.writeResponse != null);
            result.set(this.writeResponse);
          }

          @Override
          public void onError(Throwable throwable) {
            Preconditions.checkState(this.writeResponse == null);
            result.setException(throwable);
          }
        });
    try (OutputStream dest =
             new ByteStreamServiceOutputStream(streamObserver, digest)) {
      ByteStreams.copy(source, dest);
    }

    try {
      WriteResponse response = result.get();
      if (response.getCommittedSize() != digest.getSizeBytes()) {
        throw new IOException(
            String.format(
                "Committed size of %d is different from digest size of %d",
                response.getCommittedSize(),
                digest.getSizeBytes()));
      }
    } catch (InterruptedException e) {
      throw new IOException(e);
    } catch (ExecutionException e) {
      throw new IOException(e);
    }
  }

  private class ByteStreamServiceOutputStream extends OutputStream {
    private final StreamObserver<WriteRequest> streamObserver;
    private final Digest digest;
    private final String resourceName;

    private long writeOffset = 0;

    public ByteStreamServiceOutputStream(
        StreamObserver<WriteRequest> streamObserver, Digest digest) {
      this.streamObserver = streamObserver;
      this.digest = digest;
      this.resourceName = getResourceNameForWrite(digest);
    }

    @Override
    public void close() {
      Preconditions.checkState(writeOffset == digest.getSizeBytes());
      WriteRequest request =
          WriteRequest.newBuilder()
              .setResourceName(resourceName)
              .setWriteOffset(writeOffset)
              .setFinishWrite(true)
              .build();
      streamObserver.onNext(request);
      streamObserver.onCompleted();
    }

    @Override
    public void flush() {
      // Nothing to do.
    }

    private void write(ByteString data) {
      WriteRequest request =
          WriteRequest.newBuilder()
              .setResourceName(resourceName)
              .setWriteOffset(writeOffset)
              .setData(data)
              .build();
      streamObserver.onNext(request);
      writeOffset += data.size();
    }

    @Override
    public void write(byte[] b) {
      write(ByteString.copyFrom(b));
    }

    @Override
    public void write(byte[] b, int off, int len) {
      Preconditions.checkArgument(off >= 0 && off < b.length);
      Preconditions.checkArgument(len > 0 && len <= b.length);
      Preconditions.checkArgument((off + len) <= b.length);

      write(ByteString.copyFrom(b, off, len));
    }

    @Override
    public void write(int b) {
      Preconditions.checkArgument(b >= 0 && b < 256);

      byte data[] = { (byte)b };
      write(data);
    }
  }
}
