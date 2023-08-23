workspace(name = "remote_client")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

RULES_JVM_EXTERNAL_TAG = "5.2"
RULES_JVM_EXTERNAL_SHA = "3824ac95d9edf8465c7a42b7fcb88a5c6b85d2bac0e98b941ba13f235216f313"

http_archive(
    name = "rules_jvm_external",
    strip_prefix = "rules_jvm_external-%s" % RULES_JVM_EXTERNAL_TAG,
    sha256 = RULES_JVM_EXTERNAL_SHA,
    url = "https://github.com/bazelbuild/rules_jvm_external/archive/%s.zip" % RULES_JVM_EXTERNAL_TAG,
)

http_archive(
    name = "bazel_skylib",
    sha256 = "060426b186670beede4104095324a72bd7494d8b4e785bf0d84a612978285908",
    strip_prefix = "bazel-skylib-1.4.1",
    url = "https://github.com/bazelbuild/bazel-skylib/archive/1.4.1.tar.gz",
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
    sha256 = "b1d2db800d3cce5a219ce75433eff3f195245902fd67b15a59e35f459c2ee90a",
    strip_prefix = "grpc-java-1.55.1",
    urls = ["https://github.com/grpc/grpc-java/archive/refs/tags/v1.55.1.zip"],
)

http_archive(
    name = "com_google_protobuf",
    sha256 = "8bc8612df03c5148f828f762215ee87c9aecca05ed20ef6dffc72ac90bf2c63e",
    strip_prefix = "protobuf-24.1",
    urls = ["https://github.com/protocolbuffers/protobuf/releases/download/v24.1/protobuf-24.1.zip"],
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

# filter grpc dependencies to specify our own guava version
GUAVA_PACKAGE_PREFIX = "com.google.guava:guava:"

maven_install(
    artifacts = [
        "com.beust:jcommander:1.72",
        "com.google.guava:guava:31.0.1-jre",
        "com.google.http-client:google-http-client:1.23.0",
        "com.google.jimfs:jimfs:1.1",
        "com.googlecode.json-simple:json-simple:1.1.1",
    ] + [artifact for artifact in IO_GRPC_GRPC_JAVA_ARTIFACTS if not artifact.startswith(GUAVA_PACKAGE_PREFIX)],
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
