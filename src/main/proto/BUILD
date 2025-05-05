package(default_visibility = ["//visibility:public"])

proto_library(
    name = "remote_execution_log_proto",
    srcs = ["remote_execution_log.proto"],
    deps = [
        "@com_google_protobuf//:timestamp_proto",
        "@googleapis//google/bytestream:bytestream_proto",
        "@googleapis//google/longrunning:operations_proto",
        "@googleapis//google/rpc:status_proto",
        "@bazel_remote_apis//build/bazel/remote/execution/v2:remote_execution_proto",
    ],
)

java_proto_library(
    name = "remote_execution_log_java_proto",
    deps = [":remote_execution_log_proto"],
)
