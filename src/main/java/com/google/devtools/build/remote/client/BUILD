licenses(["notice"])  # Apache 2.0

java_library(
    name = "client",
    srcs = glob(["*.java"]),
    visibility = ["//:__subpackages__"],
    runtime_deps = [
        # Needed for tls client.
        "@maven//:io_netty_netty_tcnative_boringssl_static",
    ],
    deps = [
        "@maven//:com_beust_jcommander",
        "@maven//:com_google_auth_google_auth_library_credentials",
        "@maven//:com_google_auth_google_auth_library_oauth2_http",
        "@maven//:com_google_code_findbugs_jsr305",
        "@maven//:com_google_guava_guava",
        "@maven//:com_google_protobuf_protobuf_java",
        "@maven//:com_google_protobuf_protobuf_java_util",
        "@maven//:io_grpc_grpc_api",
        "@maven//:io_grpc_grpc_auth",
        "@maven//:io_grpc_grpc_context",
        "@maven//:io_grpc_grpc_core",
        "@maven//:io_grpc_grpc_netty",
        "@maven//:io_grpc_grpc_protobuf",
        "@maven//:io_grpc_grpc_stub",
        "@maven//:io_netty_netty_handler",
        "//src/main/proto:remote_execution_log_java_proto",
        "@googleapis//:google_bytestream_bytestream_java_grpc",
        "@googleapis//:google_bytestream_bytestream_java_proto",
        "@googleapis//:google_devtools_remoteexecution_v1test_remote_execution_java_grpc",
        "@googleapis//:google_devtools_remoteexecution_v1test_remote_execution_java_proto",
        "@googleapis//:google_longrunning_operations_java_proto",
        "@googleapis//:google_watch_v1_java_proto",
        "@remoteapis//:remote_execution_java_grpc",
        "@remoteapis//build/bazel/remote/execution/v2:remote_execution_java_proto",
        "@remoteapis//build/bazel/semver:semver_java_proto",
    ],
)
