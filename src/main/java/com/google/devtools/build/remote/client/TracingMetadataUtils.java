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

import com.google.common.annotations.VisibleForTesting;
import com.google.devtools.remoteexecution.v1test.RequestMetadata;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.MetadataUtils;

/** Utility functions to handle Metadata for remote Grpc calls. */
public class TracingMetadataUtils {

  private TracingMetadataUtils() {}

  private static final Context.Key<RequestMetadata> CONTEXT_KEY =
      Context.key("remote-grpc-metadata");

  @VisibleForTesting
  public static final Metadata.Key<RequestMetadata> METADATA_KEY =
      ProtoUtils.keyForProto(RequestMetadata.getDefaultInstance());

  /**
   * Returns a new gRPC context derived from the current context, with {@link RequestMetadata}
   * accessible by the {@link fromCurrentContext()} method.
   */
  public static Context contextWithMetadata(RequestMetadata metadata) {
    return Context.current().withValue(CONTEXT_KEY, metadata);
  }

  /**
   * Fetches a {@link RequestMetadata} defined on the current context.
   *
   * @throws {@link IllegalStateException} when the metadata is not defined in the current context.
   */
  public static RequestMetadata fromCurrentContext() {
    RequestMetadata metadata = CONTEXT_KEY.get();
    if (metadata == null) {
      throw new IllegalStateException("RequestMetadata not set in current context.");
    }
    return metadata;
  }

  /**
   * Creates a {@link Metadata} containing the {@link RequestMetadata} defined on the current
   * context.
   *
   * @throws {@link IllegalStateException} when the metadata is not defined in the current context.
   */
  public static Metadata headersFromCurrentContext() {
    Metadata headers = new Metadata();
    headers.put(METADATA_KEY, fromCurrentContext());
    return headers;
  }

  public static ClientInterceptor attachMetadataFromContextInterceptor() {
    return MetadataUtils.newAttachHeadersInterceptor(headersFromCurrentContext());
  }
}
