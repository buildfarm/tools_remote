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
import static java.nio.charset.StandardCharsets.UTF_8;

import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageImplBase;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.GetTreeRequest;
import build.bazel.remote.execution.v2.GetTreeResponse;
import build.bazel.remote.execution.v2.OutputDirectory;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.bazel.remote.execution.v2.ToolDetails;
import build.bazel.remote.execution.v2.Tree;
import com.google.api.client.json.GenericJson;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.bytestream.ByteStreamGrpc.ByteStreamImplBase;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.protobuf.ByteString;
import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.Context;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.util.MutableHandlerRegistry;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link GrpcRemoteCache}. */
@RunWith(JUnit4.class)
public class GrpcRemoteCacheTest {

  private static final DigestUtil DIGEST_UTIL = new DigestUtil(Hashing.sha256());

  private final String fakeServerName = "fake server for " + getClass();
  private final MutableHandlerRegistry serviceRegistry = new MutableHandlerRegistry();
  private final FileSystem fs =
      Jimfs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("posix").build());
  private Path execRoot;
  private Server fakeServer;

  @Before
  public final void setUp() throws Exception {
    // Use a mutable service registry for later registering the service impl for each test case.
    fakeServer =
        InProcessServerBuilder.forName(fakeServerName)
            .fallbackHandlerRegistry(serviceRegistry)
            .directExecutor()
            .build()
            .start();

    execRoot = fs.getPath("/exec/root/");
    RequestMetadata testMetadata =
        RequestMetadata.newBuilder()
            .setToolDetails(ToolDetails.newBuilder().setToolName("TEST"))
            .build();
    Context withEmptyMetaData = TracingMetadataUtils.contextWithMetadata(testMetadata);
    withEmptyMetaData.attach();
  }

  @After
  public void tearDown() throws Exception {
    fakeServer.shutdownNow();
    fakeServer.awaitTermination();
  }

  private static class CallCredentialsInterceptor implements ClientInterceptor {
    private final CallCredentials credentials;

    public CallCredentialsInterceptor(CallCredentials credentials) {
      this.credentials = credentials;
    }

    @Override
    public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> interceptCall(
        MethodDescriptor<RequestT, ResponseT> method, CallOptions callOptions, Channel next) {
      assertThat(callOptions.getCredentials()).isEqualTo(credentials);
      // Remove the call credentials to allow testing with dummy ones.
      return next.newCall(method, callOptions.withCallCredentials(null));
    }
  }

  private GrpcRemoteCache newClient() throws IOException {
    AuthAndTLSOptions authTlsOptions = new AuthAndTLSOptions();
    authTlsOptions.useGoogleDefaultCredentials = true;
    authTlsOptions.googleCredentials = "/exec/root/creds.json";
    authTlsOptions.googleAuthScopes = ImmutableList.of("dummy.scope");

    GenericJson json = new GenericJson();
    json.put("type", "authorized_user");
    json.put("client_id", "some_client");
    json.put("client_secret", "foo");
    json.put("refresh_token", "bar");
    FileSystem scratchFs = Jimfs.newFileSystem(Configuration.unix());
    Path credsPath = scratchFs.getPath(authTlsOptions.googleCredentials);
    Files.createDirectories(credsPath.getParent());
    Files.write(credsPath, new JacksonFactory().toString(json).getBytes());

    CallCredentials creds =
        GoogleAuthUtils.newCallCredentials(
            Files.newInputStream(credsPath), authTlsOptions.googleAuthScopes);

    RemoteOptions remoteOptions = new RemoteOptions();
    return new GrpcRemoteCache(
        ClientInterceptors.intercept(
            InProcessChannelBuilder.forName(fakeServerName).directExecutor().build(),
            ImmutableList.of(new CallCredentialsInterceptor(creds))),
        creds,
        remoteOptions,
        DIGEST_UTIL);
  }

  // Returns whether a path/file is executable or not.
  private boolean isExecutable(Path path) throws IOException {
    return Files.getPosixFilePermissions(path).contains(PosixFilePermission.OWNER_EXECUTE);
  }

  @Test
  public void testDownloadEmptyBlob() throws Exception {
    GrpcRemoteCache client = newClient();
    Digest emptyDigest = DIGEST_UTIL.compute(new byte[0]);
    // Will not call the mock Bytestream interface at all.
    assertThat(client.downloadBlob(emptyDigest)).isEmpty();
  }

  @Test
  public void testDownloadBlobSingleChunk() throws Exception {
    final GrpcRemoteCache client = newClient();
    final Digest digest = DIGEST_UTIL.computeAsUtf8("abcdefg");
    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
            assertThat(request.getResourceName().contains(digest.getHash())).isTrue();
            responseObserver.onNext(
                ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("abcdefg")).build());
            responseObserver.onCompleted();
          }
        });
    assertThat(new String(client.downloadBlob(digest), UTF_8)).isEqualTo("abcdefg");
  }

  @Test
  public void testDownloadBlobMultipleChunks() throws Exception {
    final GrpcRemoteCache client = newClient();
    final Digest digest = DIGEST_UTIL.computeAsUtf8("abcdefg");
    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
            assertThat(request.getResourceName().contains(digest.getHash())).isTrue();
            responseObserver.onNext(
                ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("abc")).build());
            responseObserver.onNext(
                ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("def")).build());
            responseObserver.onNext(
                ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("g")).build());
            responseObserver.onCompleted();
          }
        });
    assertThat(new String(client.downloadBlob(digest), UTF_8)).isEqualTo("abcdefg");
  }

  @Test
  public void testDownloadDirectoryEmpty() throws Exception {
    GrpcRemoteCache client = newClient();

    Directory dirMessage = Directory.getDefaultInstance();
    Digest dirDigest = DIGEST_UTIL.compute(dirMessage);
    serviceRegistry.addService(
        new FakeImmutableCacheByteStreamImpl(
            ImmutableMap.of(dirDigest, dirMessage.toByteString())));
    serviceRegistry.addService(
        new ContentAddressableStorageImplBase() {
          @Override
          public void getTree(
              GetTreeRequest request, StreamObserver<GetTreeResponse> responseObserver) {
            assertThat(request.getRootDigest()).isEqualTo(dirDigest);
            responseObserver.onNext(
                GetTreeResponse.newBuilder().addDirectories(dirMessage).build());
            responseObserver.onCompleted();
          }
        });
    client.downloadDirectory(execRoot.resolve("test"), dirDigest);
    assertThat(Files.exists(execRoot.resolve("test"))).isTrue();
  }

  @Test
  public void testDownloadDirectory() throws Exception {
    GrpcRemoteCache client = newClient();
    Digest fooDigest = DIGEST_UTIL.computeAsUtf8("foo-contents");

    Directory barMessage =
        Directory.newBuilder()
            .addFiles(
                FileNode.newBuilder().setDigest(fooDigest).setName("foo").setDigest(fooDigest))
            .build();
    Digest barDigest = DIGEST_UTIL.compute(barMessage);

    Directory dirMessage =
        Directory.newBuilder()
            .addDirectories(DirectoryNode.newBuilder().setDigest(barDigest).setName("bar"))
            .addFiles(
                FileNode.newBuilder().setDigest(fooDigest).setName("foo").setIsExecutable(true))
            .build();
    Digest dirDigest = DIGEST_UTIL.compute(dirMessage);
    serviceRegistry.addService(
        new FakeImmutableCacheByteStreamImpl(
            ImmutableMap.of(dirDigest, dirMessage.toByteString(), fooDigest, "foo-contents")));
    serviceRegistry.addService(
        new ContentAddressableStorageImplBase() {
          @Override
          public void getTree(
              GetTreeRequest request, StreamObserver<GetTreeResponse> responseObserver) {
            assertThat(request.getRootDigest()).isEqualTo(dirDigest);
            responseObserver.onNext(
                GetTreeResponse.newBuilder()
                    .addDirectories(dirMessage)
                    .addDirectories(barMessage)
                    .build());
            responseObserver.onCompleted();
          }
        });

    client.downloadDirectory(execRoot.resolve("test"), dirDigest);
    assertThat(Files.exists(execRoot.resolve("test"))).isTrue();
    assertThat(Files.exists(execRoot.resolve("test/foo"))).isTrue();
    assertThat(Files.exists(execRoot.resolve("test/bar"))).isTrue();
    assertThat(Files.exists(execRoot.resolve("test/bar/foo"))).isTrue();
    assertThat(Files.isRegularFile(execRoot.resolve("test/foo"))).isTrue();
    assertThat(Files.isDirectory(execRoot.resolve("test/bar"))).isTrue();
    assertThat(Files.isRegularFile(execRoot.resolve("test/bar/foo"))).isTrue();
    if (!System.getProperty("os.name").startsWith("Windows")) {
      assertThat(isExecutable(execRoot.resolve("test/foo"))).isTrue();
      assertThat(isExecutable(execRoot.resolve("test/bar/foo"))).isFalse();
    }
  }

  @Test
  public void testGetTree() throws Exception {
    GrpcRemoteCache client = newClient();
    Directory quxMessage = Directory.getDefaultInstance();
    Directory barMessage =
        Directory.newBuilder().addFiles(FileNode.newBuilder().setName("test")).build();
    Digest quxDigest = DIGEST_UTIL.compute(quxMessage);
    Digest barDigest = DIGEST_UTIL.compute(barMessage);
    Directory fooMessage =
        Directory.newBuilder()
            .addDirectories(DirectoryNode.newBuilder().setDigest(quxDigest).setName("qux"))
            .addDirectories(DirectoryNode.newBuilder().setDigest(barDigest).setName("bar"))
            .build();
    Digest fooDigest = DIGEST_UTIL.compute(fooMessage);
    serviceRegistry.addService(
        new FakeImmutableCacheByteStreamImpl(
            ImmutableMap.of(fooDigest, fooMessage.toByteString())));
    GetTreeResponse response1 =
        GetTreeResponse.newBuilder().addDirectories(quxMessage).setNextPageToken("token").build();
    GetTreeResponse response2 = GetTreeResponse.newBuilder().addDirectories(barMessage).build();
    serviceRegistry.addService(
        new ContentAddressableStorageImplBase() {
          @Override
          public void getTree(
              GetTreeRequest request, StreamObserver<GetTreeResponse> responseObserver) {
            responseObserver.onNext(response1);
            responseObserver.onNext(response2);
            responseObserver.onCompleted();
          }
        });
    Tree tree = client.getTree(fooDigest);
    assertThat(tree.getRoot()).isEqualTo(fooMessage);
    assertThat(tree.getChildrenList()).containsExactly(quxMessage, barMessage);
  }

  @Test
  public void testDownloadOutputDirectory() throws Exception {
    GrpcRemoteCache client = newClient();
    Digest fooDigest = DIGEST_UTIL.computeAsUtf8("foo-contents");
    Digest quxDigest = DIGEST_UTIL.computeAsUtf8("qux-contents");
    Tree barTreeMessage =
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(
                        FileNode.newBuilder()
                            .setName("qux")
                            .setDigest(quxDigest)
                            .setIsExecutable(true)))
            .build();
    Digest barTreeDigest = DIGEST_UTIL.compute(barTreeMessage);
    OutputDirectory barDirMessage =
        OutputDirectory.newBuilder().setPath("test/bar").setTreeDigest(barTreeDigest).build();
    Digest barDirDigest = DIGEST_UTIL.compute(barDirMessage);
    serviceRegistry.addService(
        new FakeImmutableCacheByteStreamImpl(
            ImmutableMap.of(
                fooDigest,
                "foo-contents",
                barTreeDigest,
                barTreeMessage.toByteString(),
                quxDigest,
                "qux-contents",
                barDirDigest,
                barDirMessage.toByteString())));

    client.downloadOutputDirectory(barDirMessage, execRoot.resolve("test/bar"));

    assertThat(Files.exists(execRoot.resolve("test/bar"))).isTrue();
    assertThat(Files.isDirectory(execRoot.resolve("test/bar"))).isTrue();
    assertThat(Files.exists(execRoot.resolve("test/bar/qux"))).isTrue();
    assertThat(Files.isRegularFile(execRoot.resolve("test/bar/qux"))).isTrue();
    if (!System.getProperty("os.name").startsWith("Windows")) {
      assertThat(isExecutable(execRoot.resolve("test/bar/qux"))).isTrue();
    }
  }

  @Test
  public void testDownloadOutputDirectoryEmpty() throws Exception {
    GrpcRemoteCache client = newClient();

    Tree barTreeMessage = Tree.newBuilder().setRoot(Directory.newBuilder()).build();
    Digest barTreeDigest = DIGEST_UTIL.compute(barTreeMessage);
    OutputDirectory barDirMessage =
        OutputDirectory.newBuilder().setPath("test/bar").setTreeDigest(barTreeDigest).build();
    Digest barDirDigest = DIGEST_UTIL.compute(barDirMessage);
    serviceRegistry.addService(
        new FakeImmutableCacheByteStreamImpl(
            ImmutableMap.of(
                barTreeDigest, barTreeMessage.toByteString(),
                barDirDigest, barDirMessage.toByteString())));

    client.downloadOutputDirectory(barDirMessage, execRoot.resolve("test/bar"));

    assertThat(Files.exists(execRoot.resolve("test/bar"))).isTrue();
    assertThat(Files.isDirectory(execRoot.resolve("test/bar"))).isTrue();
  }

  @Test
  public void testDownloadOutputDirectoryNested() throws Exception {
    GrpcRemoteCache client = newClient();
    Digest fooDigest = DIGEST_UTIL.computeAsUtf8("foo-contents");
    Digest quxDigest = DIGEST_UTIL.computeAsUtf8("qux-contents");
    Directory wobbleDirMessage =
        Directory.newBuilder()
            .addFiles(FileNode.newBuilder().setName("qux").setDigest(quxDigest))
            .build();
    Digest wobbleDigest = DIGEST_UTIL.compute(wobbleDirMessage);
    Tree barTreeMessage =
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(FileNode.newBuilder().setName("qux").setDigest(quxDigest))
                    .addDirectories(
                        DirectoryNode.newBuilder().setName("wobble").setDigest(wobbleDigest)))
            .addChildren(wobbleDirMessage)
            .build();
    Digest barTreeDigest = DIGEST_UTIL.compute(barTreeMessage);
    OutputDirectory barDirMessage =
        OutputDirectory.newBuilder().setPath("test/bar").setTreeDigest(barTreeDigest).build();
    Digest barDirDigest = DIGEST_UTIL.compute(barDirMessage);
    serviceRegistry.addService(
        new FakeImmutableCacheByteStreamImpl(
            ImmutableMap.of(
                fooDigest,
                "foo-contents",
                barTreeDigest,
                barTreeMessage.toByteString(),
                quxDigest,
                "qux-contents",
                barDirDigest,
                barDirMessage.toByteString())));

    client.downloadOutputDirectory(barDirMessage, execRoot.resolve("test/bar"));

    assertThat(Files.exists(execRoot.resolve("test/bar"))).isTrue();
    assertThat(Files.isDirectory(execRoot.resolve("test/bar"))).isTrue();

    assertThat(Files.exists(execRoot.resolve("test/bar/wobble"))).isTrue();
    assertThat(Files.isDirectory(execRoot.resolve("test/bar/wobble"))).isTrue();

    assertThat(Files.exists(execRoot.resolve("test/bar/wobble/qux"))).isTrue();
    assertThat(Files.isRegularFile(execRoot.resolve("test/bar/wobble/qux"))).isTrue();

    assertThat(Files.exists(execRoot.resolve("test/bar/qux"))).isTrue();
    assertThat(Files.isRegularFile(execRoot.resolve("test/bar/qux"))).isTrue();
    if (!System.getProperty("os.name").startsWith("Windows")) {
      assertThat(isExecutable(execRoot.resolve("test/bar/wobble/qux"))).isFalse();
      assertThat(isExecutable(execRoot.resolve("test/bar/qux"))).isFalse();
    }
  }
}
