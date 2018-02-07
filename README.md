# Remote client tool
This tool is meant to provide various debugging functionality for users of remote caching and
execution in Bazel such as downloading blobs and directories by digest. Details on using remote
caching and execution in Bazel can be found [here](https://docs.bazel.build/versions/master/remote-caching.html).

## Usage

The command line options for connecting to a remote cache (cache address, authentication, TLS, etc.)
with this tool are identical to the options in Bazel. Currently only downloading blobs by
digest through the gRPC protocol is supported by this tool.

Example for downloading a blob with digest
762670a6d50679e5495d3a489290bf2b3845172de3c048476668e91f6fc42b8e/18544 to file hello-world:

    $ bazel-bin/remote_client \
        --remote_cache localhost:8080 \
        --digest 762670a6d50679e5495d3a489290bf2b3845172de3c048476668e91f6fc42b8e/18544 \
        --output hello-world

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