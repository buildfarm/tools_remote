workspace(name = "remote_client")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

RULES_JVM_EXTERNAL_TAG = "3.0"
RULES_JVM_EXTERNAL_SHA = "62133c125bf4109dfd9d2af64830208356ce4ef8b165a6ef15bbff7460b35c3a"

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

# Needed for "well-known protos" and @com_google_protobuf//:protoc.
http_archive(
    name = "com_google_protobuf",
    sha256 = "c90d9e13564c0af85fd2912545ee47b57deded6e5a97de80395b6d2d9be64854",
    strip_prefix = "protobuf-3.9.1",
    urls = ["https://github.com/protocolbuffers/protobuf/archive/v3.9.1.zip"],
)

# Needed for @grpc_java//compiler:grpc_java_plugin.
http_archive(
    name = "grpc_java",
    sha256 = "000a6f8579f1b93e5d1b085c29d89dbc1ea8b5a0c16d7427f42715f0d7f0b247",
    strip_prefix = "grpc-java-d792a72ea15156254e3b3735668e9c4539837fd3",
    urls = ["https://github.com/grpc/grpc-java/archive/d792a72ea15156254e3b3735668e9c4539837fd3.zip"],
)

http_archive(
    name = "remoteapis",
    patch_args = ["-p1"],
    patches = ["@remote_client//third_party/remoteapis:remoteapis.patch"],
    build_file = "@remote_client//:BUILD.remoteapis",
    sha256 = "e779aa544c5fc94365c07023974d8fc5894f24df04d901bdf6033ebe01a5a5e5",
    strip_prefix = "remote-apis-1b16ed76965afa8bb229ba22b90745c546416443",
    url = "https://github.com/bazelbuild/remote-apis/archive/1b16ed76965afa8bb229ba22b90745c546416443.zip",
)

http_archive(
    name = "googleapis",
    build_file = "@remote_client//:BUILD.googleapis",
    sha256 = "7b6ea252f0b8fb5cd722f45feb83e115b689909bbb6a393a873b6cbad4ceae1d",
    strip_prefix = "googleapis-143084a2624b6591ee1f9d23e7f5241856642f4d",
    urls = ["https://github.com/googleapis/googleapis/archive/143084a2624b6591ee1f9d23e7f5241856642f4d.zip"],
)

http_archive(
    name = "com_github_grpc_grpc",
    sha256 = "bad0de89c09137704821818f5d25566ee4c58698e99e3df67e878ef5da221159",
    strip_prefix = "grpc-1.27.3",
    urls = ["https://github.com/grpc/grpc/archive/v1.27.3.zip"],
)

http_archive(
    name = "io_grpc_grpc_java",
    sha256 = "e274597cc4de351b4f79e4c290de8175c51a403dc39f83f1dfc50a1d1c9e9a4f",
    strip_prefix = "grpc-java-1.28.0",
    urls = ["https://github.com/grpc/grpc-java/archive/v1.28.0.zip"],
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

IO_GRPC_MODULES = [
    "auth",
    "core",
    "context",
    "netty",
    "stub",
    "protobuf",
]

maven_install(
    artifacts = [
        "com.beust:jcommander:1.72",
        "com.google.auth:google-auth-library-credentials:0.9.1",
        "com.google.auth:google-auth-library-oauth2-http:0.9.1",
        "com.google.guava:guava:28.2-jre",
        "com.google.guava:failureaccess:1.0.1",
        "com.google.http-client:google-http-client:1.23.0",
        "com.google.jimfs:jimfs:1.1",
        "com.google.code.findbugs:jsr305:3.0.1",
        "com.google.protobuf:protobuf-java:3.10.0",
        "com.google.protobuf:protobuf-java-util:3.10.0",
        "com.google.truth:truth:0.44",
    ] + ["io.grpc:grpc-%s:1.26.0" % module for module in IO_GRPC_MODULES]
      + [
        "io.netty:netty-handler:4.1.38.Final",
        "io.netty:netty-tcnative-boringssl-static:2.0.5.Final",
    ],
    repositories = [
        "https://repo.maven.apache.org/maven2",
    ],
)

load("@io_grpc_grpc_java//:repositories.bzl", "grpc_java_repositories")

grpc_java_repositories()

load("@com_google_protobuf//:protobuf_deps.bzl", "protobuf_deps")

protobuf_deps()

load("@remoteapis//:repository_rules.bzl", "switched_rules_by_language")

switched_rules_by_language(
    name = "bazel_remote_apis_imports",
    java = True,
)
