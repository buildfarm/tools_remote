java_test(
    name = "GrpcRemoteCacheTest",
    srcs = [
        "FakeImmutableCacheByteStreamImpl.java",
        "GrpcRemoteCacheTest.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/remote/client",
        "@googleapis//:google_bytestream_bytestream_java_grpc",
        "@googleapis//:google_bytestream_bytestream_java_proto",
        "@maven//:com_google_guava_guava",
        "@maven//:com_google_http_client_google_http_client",
        "@maven//:com_google_http_client_google_http_client_jackson2",
        "@maven//:com_google_jimfs_jimfs",
        "@maven//:com_google_protobuf_protobuf_java",
        "@maven//:com_google_truth_truth",
        "@maven//:io_grpc_grpc_api",
        "@maven//:io_grpc_grpc_context",
        "@maven//:io_grpc_grpc_core",
        "@maven//:io_grpc_grpc_stub",
        "@remoteapis//:remote_execution_java_grpc",
        "@remoteapis//build/bazel/remote/execution/v2:remote_execution_java_proto",
    ],
)

java_test(
    name = "ShellEscaperTest",
    srcs = ["ShellEscaperTest.java"],
    deps = [
        "@maven//:com_google_truth_truth",
        "//src/main/java/com/google/devtools/build/remote/client",
    ],
)

java_test(
    name = "ActionGroupingTest",
    srcs = ["ActionGroupingTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/remote/client",
        "//src/main/proto:remote_execution_log_java_proto",
        "@googleapis//:google_longrunning_operations_java_proto",
        "@maven//:com_google_protobuf_protobuf_java",
        "@maven//:com_google_protobuf_protobuf_java_util",
        "@maven//:com_google_truth_truth",
        "@maven//:io_grpc_grpc_api",
        "@remoteapis//build/bazel/remote/execution/v2:remote_execution_java_proto",
    ],
)

java_test(
    name = "DockerUtilTest",
    srcs = ["DockerUtilTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/remote/client",
        "@maven//:com_google_guava_guava",
        "@maven//:com_google_truth_truth",
        "@remoteapis//build/bazel/remote/execution/v2:remote_execution_java_proto",
    ],
)
