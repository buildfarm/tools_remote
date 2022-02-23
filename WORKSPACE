workspace(name = "remote_client")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

RULES_JVM_EXTERNAL_TAG = "4.2"
RULES_JVM_EXTERNAL_SHA = "cd1a77b7b02e8e008439ca76fd34f5b07aecb8c752961f9640dea15e9e5ba1ca"

http_archive(
    name = "rules_jvm_external",
    strip_prefix = "rules_jvm_external-%s" % RULES_JVM_EXTERNAL_TAG,
    sha256 = RULES_JVM_EXTERNAL_SHA,
    url = "https://github.com/bazelbuild/rules_jvm_external/archive/%s.zip" % RULES_JVM_EXTERNAL_TAG,
)

http_archive(
    name = "bazel_skylib",
    sha256 = "9245b0549e88e356cd6a25bf79f97aa19332083890b7ac6481a2affb6ada9752",
    strip_prefix = "bazel-skylib-0.9.0",
    url = "https://github.com/bazelbuild/bazel-skylib/archive/0.9.0.tar.gz",
)

http_archive(
    name = "remoteapis",
    patch_args = ["-p1"],
    patches = ["@remote_client//third_party/remoteapis:remoteapis.patch"],
    build_file = "@remote_client//:BUILD.remoteapis",
    sha256 = "3c56ee449b95e35c95685d311711a3d306ed483396fb48f56cdd171f007148bd",
    strip_prefix = "remote-apis-3e385366f152e99adda5ab5e4857b1ab221ba2fe",
    url = "https://github.com/bazelbuild/remote-apis/archive/3e385366f152e99adda5ab5e4857b1ab221ba2fe.zip",
)

http_archive(
    name = "googleapis",
    build_file = "@remote_client//:BUILD.googleapis",
    sha256 = "7b6ea252f0b8fb5cd722f45feb83e115b689909bbb6a393a873b6cbad4ceae1d",
    strip_prefix = "googleapis-143084a2624b6591ee1f9d23e7f5241856642f4d",
    urls = ["https://github.com/googleapis/googleapis/archive/143084a2624b6591ee1f9d23e7f5241856642f4d.zip"],
)

http_archive(
    name = "io_grpc_grpc_java",
    sha256 = "48b8cb8adee4b2336e9f646e17a10107b1c8de495e1302d28a17b4816d6a20ca",
    strip_prefix = "grpc-java-1.44.1",
    urls = ["https://github.com/grpc/grpc-java/archive/v1.44.1.zip"],
)

# Bazel toolchains
http_archive(
  name = "bazel_toolchains",
  urls = [
    "https://mirror.bazel.build/github.com/bazelbuild/bazel-toolchains/archive/bc0091adceaf4642192a8dcfc46e3ae3e4560ea7.tar.gz",
    "https://github.com/bazelbuild/bazel-toolchains/archive/bc0091adceaf4642192a8dcfc46e3ae3e4560ea7.tar.gz",
  ],
  strip_prefix = "bazel-toolchains-bc0091adceaf4642192a8dcfc46e3ae3e4560ea7",
  sha256 = "7e85a14821536bc24e04610d309002056f278113c6cc82f1059a609361812431",
)

load("@rules_jvm_external//:defs.bzl", "maven_install")

load(
    "@io_grpc_grpc_java//:repositories.bzl",
    "IO_GRPC_GRPC_JAVA_ARTIFACTS",
    "IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS",
    "grpc_java_repositories",
)

maven_install(
    artifacts = [
        "com.beust:jcommander:1.72",
        "com.google.guava:guava:30.1.1-jre",
        "com.google.http-client:google-http-client:1.23.0",
        "com.google.jimfs:jimfs:1.1",
    ] + IO_GRPC_GRPC_JAVA_ARTIFACTS,
    repositories = [
        "https://repo.maven.apache.org/maven2",
    ],
    override_targets = IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS,
    generate_compat_repositories = True,
)

load("@maven//:compat.bzl", "compat_repositories")
compat_repositories()

grpc_java_repositories()

load("@com_google_protobuf//:protobuf_deps.bzl", "protobuf_deps")

protobuf_deps()

load("@remoteapis//:repository_rules.bzl", "switched_rules_by_language")

switched_rules_by_language(
    name = "bazel_remote_apis_imports",
    java = True,
)
