# Remote client tool
This tool is meant to provide various debugging functionality for users of remote caching and
execution in Bazel such as downloading blobs and directories by digest. Details on using remote
caching and execution in Bazel can be found [here](https://docs.bazel.build/versions/master/remote-caching.html).

## Installation

This tool is built using Bazel:

    $ bazel build //:remote_client

## Usage

The command line options for configuring a connection to a remote cache (cache address,
authentication, TLS, etc.) with this tool are identical to the options in Bazel. This tool currently
supports downloading blobs, and recursively listing/downloading directories by digest.

Example for downloading a blob with digest
762670a6d50679e5495d3a489290bf2b3845172de3c048476668e91f6fc42b8e/18544 to file hello-world:

    $ bazel-bin/remote_client \
        --remote_cache=localhost:8080 \
        cat \
        --digest=762670a6d50679e5495d3a489290bf2b3845172de3c048476668e91f6fc42b8e/18544 \
        --file=/tmp/hello-world

Example for listing a Directory with digest
d1c2cad73bf385e1ebc7f7433781a9a5807d425de9426c11d770b5123e5c6a5b/82:

    $ bazel-bin/remote_client \
        --remote_cache=localhost:8080 \
        ls \
        --digest=d1c2cad73bf385e1ebc7f7433781a9a5807d425de9426c11d770b5123e5c6a5b/82
    examples [Directory digest: d7c6e933b3cd90bc75560418674c7e59284c829fb383e80aa7531217c12adbc9/78]
    examples/cpp [Directory digest: 51f83b4726027e59f982f49ffe5de1828a81e9d2135b379937a26c0840de6b20/175]
    examples/cpp/hello-lib.h [File content digest: fbc71c527a8d91d1b4414484811c20edc0369d0ccdfcfd562ebd01726141bf51/368]
    examples/cpp/hello-world.cc [File content digest: 6cd9f4d242f4441a375539146b0cdabb559c6184921aede45d5a0ed7b84d5253/747]
    
Example for downloading a Directory with digest
d1c2cad73bf385e1ebc7f7433781a9a5807d425de9426c11d770b5123e5c6a5b/82:

    $ bazel-bin/remote_client \
        --remote_cache=localhost:8080 \
        getdir \
        --digest=d1c2cad73bf385e1ebc7f7433781a9a5807d425de9426c11d770b5123e5c6a5b/82 \
        --path=/tmp/testdir
    $ ls -l /tmp/testdir
     total 4
     drwxr-xr-x 3 cdlee * 4096 Mar 12 17:22 examples


For a full listing of configuration options and commands, see `remote_client --help`.
## Developer Information

### Third-party Dependencies

Most third-party dependencies (e.g. protobuf, gRPC, ...) are managed automatically via
[bazel-deps](https://github.com/johnynek/bazel-deps). After changing the `dependencies.yaml` file,
just run this to regenerate the 3rdparty folder:

```bash
git clone https://github.com/johnynek/bazel-deps.git ../bazel-deps2
cd ../bazel-deps
bazel build //src/scala/com/github/johnynek/bazel_deps:parseproject_deploy.jar
cd ../remote_client
../bazel-deps/gen_maven_deps.sh generate -r `pwd` -s 3rdparty/workspace.bzl -d dependencies.yaml
```

Things that aren't supported by bazel-deps are being imported as manually managed remote repos via
the `WORKSPACE` file.