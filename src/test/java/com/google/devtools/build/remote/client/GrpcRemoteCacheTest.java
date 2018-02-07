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

import com.google.api.client.json.GenericJson;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.bytestream.ByteStreamGrpc.ByteStreamImplBase;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.devtools.remoteexecution.v1test.Digest;
import com.google.devtools.remoteexecution.v1test.RequestMetadata;
import com.google.devtools.remoteexecution.v1test.ToolDetails;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
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

  @Test
  public void testDownloadEmptyBlob() throws Exception {
    GrpcRemoteCache client = newClient();
    Digest emptyDigest = DIGEST_UTIL.compute(new byte[0]);
    // Will not call the mock Bytestream interface at all.
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    client.downloadBlob(emptyDigest, output);
    assertThat(output.toByteArray()).isEmpty();
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
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    client.downloadBlob(digest, output);
    assertThat(new String(output.toByteArray(), UTF_8)).isEqualTo("abcdefg");
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
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    client.downloadBlob(digest, output);
    assertThat(new String(output.toByteArray(), UTF_8)).isEqualTo("abcdefg");
  }
}
