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

import static com.google.common.io.MoreFiles.asByteSource;
import static java.nio.charset.StandardCharsets.UTF_8;

import build.bazel.remote.execution.v2.Digest;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.protobuf.Message;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class DigestUtil {
  private final HashFunction hashFn;

  public DigestUtil(HashFunction hashFn) {
    this.hashFn = hashFn;
  }

  public Digest compute(byte[] blob) {
    return buildDigest(hashFn.hashBytes(blob).toString(), blob.length);
  }

  /**
   * Computes a digest of the given proto message. Currently, we simply rely on message output as
   * bytes, but this implementation relies on the stability of the proto encoding, in particular
   * between different platforms and languages.
   */
  public Digest compute(Message message) {
    return compute(message.toByteArray());
  }

  public Digest computeAsUtf8(String str) {
    return compute(str.getBytes(UTF_8));
  }

  public Digest compute(Path path) throws IOException {
    if (!Files.isRegularFile(path)) {
      throw new IOException("Only can compute hash for regular file.");
    }
    long fileSize = Files.size(path);
    return buildDigest(asByteSource(path).hash(hashFn).asBytes(), fileSize);
  }

  public static Digest buildDigest(byte[] hash, long size) {
    return buildDigest(HashCode.fromBytes(hash).toString(), size);
  }

  public static Digest buildDigest(String hexHash, long size) {
    return Digest.newBuilder().setHash(hexHash).setSizeBytes(size).build();
  }

  public String toString(Digest digest) {
    return digest.getHash() + "/" + digest.getSizeBytes();
  }
}
