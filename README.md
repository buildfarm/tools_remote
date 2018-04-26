# Remote client tool
This tool is meant to provide various debugging functionality for users of remote caching and
execution in Bazel such as downloading blobs and directories by digest. Details on using remote
caching and execution in Bazel can be found [here](https://docs.bazel.build/versions/master/remote-caching.html).

## Installation

This tool is built using Bazel:

    $ bazel build //:remote_client

## Usage

The command line options for configuring a connection to a remote cache (cache address,
authentication, TLS, etc.) with this tool are identical to the options in Bazel.

For a full listing of configuration options and commands, see `remote_client --help`.

### Downloading cache blobs

This tool can download blobs from a CAS by digest with the `cat` command. For example, to download a
blob with digest 762670a6d50679e5495d3a489290bf2b3845172de3c048476668e91f6fc42b8e/18544 to the file
hello-world:

    $ bazel-bin/remote_client \
        --remote_cache=localhost:8080 \
        cat \
        --digest=762670a6d50679e5495d3a489290bf2b3845172de3c048476668e91f6fc42b8e/18544 \
        --file=/tmp/hello-world

Given the digest to a Directory which is in CAS, this tool can recursively list the files and
subdirectories which make up that directory with the `ls` command. For example, to list a Directory
with digest d1c2cad73bf385e1ebc7f7433781a9a5807d425de9426c11d770b5123e5c6a5b/82:

    $ bazel-bin/remote_client \
        --remote_cache=localhost:8080 \
        ls \
        --digest=d1c2cad73bf385e1ebc7f7433781a9a5807d425de9426c11d770b5123e5c6a5b/82
    examples [Directory digest: d7c6e933b3cd90bc75560418674c7e59284c829fb383e80aa7531217c12adbc9/78]
    examples/cpp [Directory digest: 51f83b4726027e59f982f49ffe5de1828a81e9d2135b379937a26c0840de6b20/175]
    examples/cpp/hello-lib.h [File content digest: fbc71c527a8d91d1b4414484811c20edc0369d0ccdfcfd562ebd01726141bf51/368]
    examples/cpp/hello-world.cc [File content digest: 6cd9f4d242f4441a375539146b0cdabb559c6184921aede45d5a0ed7b84d5253/747]

Similarily, a Directory can also be downloaded to a local path using the `getdir` command:
d1c2cad73bf385e1ebc7f7433781a9a5807d425de9426c11d770b5123e5c6a5b/82:

    $ bazel-bin/remote_client \
        --remote_cache=localhost:8080 \
        getdir \
        --digest=d1c2cad73bf385e1ebc7f7433781a9a5807d425de9426c11d770b5123e5c6a5b/82 \
        --path=/tmp/testdir
    $ ls -l /tmp/testdir
     total 4
     drwxr-xr-x 3 cdlee * 4096 Mar 12 17:22 examples

### <a name="readlog"></a>Printing gRPC log files

Bazel can dump a log of remote execution related gRPC calls made during a remote build by
running the remote build with the `--experimental_remote_grpc_log=PATH_TO_LOG` flag specified. This
creates a file consisting of serialized log entry protobufs which can be printed by this tool in a
human-readable way with the `printlog` command:

    $ bazel-bin/remote_client printlog -f PATH_TO_LOG

### Running Actions in Docker

Given an Action in protobuf text format that provides a `container-image` platform, this tool can
set up its inputs in a local directory and print a Docker command that will run this individual
action locally in that directory. Action protos in text format can be obtained by [printing a gRPC
log](#readlog) for a Bazel run that executed that Action remotely and then copying the Action proto
out of the printed log entry for the Execute call that executed that Action.

A given Action can be inspected with the `show_action` command:

    $ cat ~/my_action
    command_digest {
      hash: "ecd108198bd58dd643b604c55f3d2b1079b1251e68fbed2632c2b8f8afcff7fa"
      size_bytes: 2113
    }
    input_root_digest {
      hash: "8ecae53041d1be4ca1c65c006f82ec825110741fbf620f5d2bd401d2893029de"
      size_bytes: 165
    }
    output_files: "bazel-out/k8-fastbuild/testlogs/examples/cpp/hello-success_test/test.xml"
    platform {
      properties {
        name: "container-image"
        value: "gcr.io/cloud-marketplace/google/rbe-debian8@sha256:XXX"
      }
    }

    $ bazel-bin/remote_client \
        --remote_cache=localhost:8080 \
        show_action \
        --textproto=~/my_action
    Command [digest: ecd108198bd58dd643b604c55f3d2b1079b1251e68fbed2632c2b8f8afcff7fa/2113]:
    external/bazel_tools/tools/test/test-setup.sh examples/cpp/hello-success_test

    Input files [total: 3, root Directory digest: 8ecae53041d1be4ca1c65c006f82ec825110741fbf620f5d2bd401d2893029de/165]:
     ... (truncated)

    Output files:
    bazel-out/k8-fastbuild/testlogs/examples/cpp/hello-success_test/test.xml

    Output directories:
    (none)

    Platform:
    properties {
      name: "container-image"
      value: "gcr.io/cloud-marketplace/google/rbe-debian8@sha256:XXX"
    }

Note that this action has a `container-image` platform property which specifies a container that the
action is to run in.

Now, using the `run` command, the Action can be setup in a local directory and run with the provided
Docker command:

    $ bazel-bin/remote_client \
        --remote_cache=localhost:8080 \
        run \
        --textproto=~/my_action \
        --path=/tmp/run_here
     Setting up Action in directory /tmp/run_here...

     Successfully setup Action in directory /tmp/run_here.

     To run the Action locally, run:
       docker run -v /tmp/run_here:/tmp/run_here-docker -w /tmp/run_here-docker -e gcr.io/cloud-marketplace/google/rbe-debian8@sha256:XXX examples/cpp/hello-success_test
    $ docker run -v /tmp/run_here:/tmp/run_here-docker -w /tmp/run_here-docker -e gcr.io/cloud-marketplace/google/rbe-debian8@sha256:XXX examples/cpp/hello-success_test
    Hello world

## Developer Information

### Third-party Dependencies

Most third-party dependencies (e.g. protobuf, gRPC, ...) are managed automatically via
[bazel-deps](https://github.com/johnynek/bazel-deps). After changing the `dependencies.yaml` file,
just run this to regenerate the 3rdparty folder:

```bash
git clone https://github.com/johnynek/bazel-deps.git ../bazel-deps
cd ../bazel-deps
bazel build //src/scala/com/github/johnynek/bazel_deps:parseproject_deploy.jar
cd ../remote_client
../bazel-deps/gen_maven_deps.sh generate -r `pwd` -s 3rdparty/workspace.bzl -d dependencies.yaml
```

Things that aren't supported by bazel-deps are being imported as manually managed remote repos via
the `WORKSPACE` file.
