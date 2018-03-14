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

import com.google.devtools.remoteexecution.v1test.Digest;
import com.google.devtools.remoteexecution.v1test.Directory;
import com.google.devtools.remoteexecution.v1test.DirectoryNode;
import com.google.devtools.remoteexecution.v1test.FileNode;
import com.google.devtools.remoteexecution.v1test.Tree;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/** A cache for storing artifacts (input and output) as well as the output of running an action. */
public abstract class AbstractRemoteActionCache {

  public DigestUtil getDigestUtil() {
    return digestUtil;
  }

  protected final DigestUtil digestUtil;

  public AbstractRemoteActionCache(DigestUtil digestUtil) {
    this.digestUtil = digestUtil;
  }

  /**
   * Download a tree with the {@link Directory} given by digest as the root directory of the tree.
   * This default implementation requires a round trip for every subdirectory.
   *
   * @param rootDigest The digest of the root {@link Directory} of the tree
   * @return A tree with the given directory as the root.
   * @throws IOException in the case that retrieving the blobs required to reconstruct the tree
   *     failed.
   */
  public Tree getTree(Digest rootDigest) throws IOException {
    Directory rootDir = Directory.parseFrom(downloadBlob(rootDigest));
    List<Directory> children = new ArrayList<>();
    addChildDirectories(rootDir, children);
    return Tree.newBuilder().setRoot(rootDir).addAllChildren(children).build();
  }

  /**
   * Recursively add all child directories of the given directory to the given list of directories.
   */
  private void addChildDirectories(Directory dir, List<Directory> directories) throws IOException {
    for (DirectoryNode childNode : dir.getDirectoriesList()) {
      Directory childDir = Directory.parseFrom(downloadBlob(childNode.getDigest()));
      directories.add(childDir);
      addChildDirectories(childDir, directories);
    }
  }

  /**
   * Download the full contents of a Directory to a local path given its digest.
   *
   * @param downloadPath The path to download the directory contents to.
   * @param directoryDigest The digest of the Directory to download.
   * @throws IOException
   */
  public void downloadDirectory(Path downloadPath, Digest directoryDigest) throws IOException {
    Tree tree = getTree(directoryDigest);
    Map<Digest, Directory> childrenMap = new HashMap<>();
    for (Directory child : tree.getChildrenList()) {
      childrenMap.put(digestUtil.compute(child), child);
    }
    downloadDirectory(downloadPath, tree.getRoot(), childrenMap);
  }

  /**
   * Download a directory recursively. The directory is represented by a {@link Directory} protobuf
   * message, and the descendant directories are in {@code childrenMap}, accessible through their
   * digest.
   *
   * <p>Failure can result in partially downloaded directory contents.
   *
   * @throws IOException in case of a cache miss or if the remote cache is unavailable.
   */
  private void downloadDirectory(Path path, Directory dir, Map<Digest, Directory> childrenMap)
      throws IOException {
    // Ensure that the directory is created here even though the directory might be empty
    Files.createDirectories(path);
    for (FileNode child : dir.getFilesList()) {
      Path childPath = path.resolve(child.getName());
      downloadFile(childPath, child.getDigest(), child.getIsExecutable(), null);
    }
    for (DirectoryNode child : dir.getDirectoriesList()) {
      Path childPath = path.resolve(child.getName());
      Digest childDigest = child.getDigest();
      Directory childDir = childrenMap.get(childDigest);
      if (childDir == null) {
        throw new IOException(
            "could not find subdirectory "
                + child.getName()
                + " of directory "
                + path
                + " for download: digest "
                + childDigest
                + "not found");
      }
      downloadDirectory(childPath, childDir, childrenMap);
    }
  }

  /** Sets owner executable permission depending on isExecutable. */
  private void setExecutable(Path path, boolean isExecutable) throws IOException {
    Set<PosixFilePermission> originalPerms = Files.getPosixFilePermissions(path); // Immutable.
    Set<PosixFilePermission> perms = EnumSet.copyOf(originalPerms);
    if (!isExecutable) {
      perms.remove(PosixFilePermission.OWNER_EXECUTE);
    } else {
      perms.add(PosixFilePermission.OWNER_EXECUTE);
    }
    Files.setPosixFilePermissions(path, perms);
  }

  /**
   * Download a file (that is not a directory). If the {@code content} is not given, the content is
   * fetched from the digest.
   */
  protected void downloadFile(
      Path path, Digest digest, boolean isExecutable, @Nullable ByteString content)
      throws IOException {
    Files.createDirectories(path.getParent());
    if (digest.getSizeBytes() == 0) {
      // Handle empty file locally.
      Files.createFile(path);
    } else {
      if (content != null && !content.isEmpty()) {
        try (OutputStream stream = Files.newOutputStream(path)) {
          content.writeTo(stream);
        }
      } else {
        downloadBlob(digest, path);
        Digest receivedDigest = digestUtil.compute(path);
        if (!receivedDigest.equals(digest)) {
          throw new IOException("Digest does not match " + receivedDigest + " != " + digest);
        }
      }
    }
    setExecutable(path, isExecutable);
  }

  /**
   * Download a remote blob to a local destination.
   *
   * @param digest The digest of the remote blob.
   * @param dest The path to the local file.
   * @throws IOException if download failed.
   */
  protected abstract void downloadBlob(Digest digest, Path dest) throws IOException;

  /**
   * Download a remote blob and store it in memory.
   *
   * @param digest The digest of the remote blob.
   * @return The remote blob.
   * @throws IOException if download failed.
   */
  protected abstract byte[] downloadBlob(Digest digest) throws IOException;

  /**
   * Download a single blob with the specified digest into an output stream.
   *
   * @throws CacheNotFoundException in case of a cache miss.
   */
  public abstract void downloadBlob(Digest digest, OutputStream dest) throws IOException;
}
