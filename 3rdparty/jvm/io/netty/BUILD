java_library(
    name = "netty_buffer",
    visibility = [
        "//3rdparty/jvm:__subpackages__",
    ],
    exports = [
        "//external:jar/io/netty/netty_buffer",
    ],
    runtime_deps = [
        ":netty_common",
    ],
)

java_library(
    name = "netty_codec",
    visibility = [
        "//3rdparty/jvm:__subpackages__",
    ],
    exports = [
        "//external:jar/io/netty/netty_codec",
    ],
    runtime_deps = [
        ":netty_buffer",
        ":netty_common",
        ":netty_transport",
    ],
)

java_library(
    name = "netty_codec_http",
    visibility = [
        "//3rdparty/jvm:__subpackages__",
    ],
    exports = [
        "//external:jar/io/netty/netty_codec_http",
    ],
    runtime_deps = [
        ":netty_buffer",
        ":netty_codec",
        ":netty_common",
        ":netty_handler",
        ":netty_transport",
    ],
)

java_library(
    name = "netty_codec_http2",
    visibility = [
        "//3rdparty/jvm:__subpackages__",
    ],
    exports = [
        "//external:jar/io/netty/netty_codec_http2",
    ],
    runtime_deps = [
        ":netty_buffer",
        ":netty_codec",
        ":netty_codec_http",
        ":netty_common",
        ":netty_handler",
        ":netty_transport",
    ],
)

java_library(
    name = "netty_codec_socks",
    visibility = [
        "//3rdparty/jvm:__subpackages__",
    ],
    exports = [
        "//external:jar/io/netty/netty_codec_socks",
    ],
    runtime_deps = [
        ":netty_buffer",
        ":netty_codec",
        ":netty_common",
        ":netty_transport",
    ],
)

java_library(
    name = "netty_common",
    visibility = [
        "//3rdparty/jvm:__subpackages__",
    ],
    exports = [
        "//external:jar/io/netty/netty_common",
    ],
)

java_library(
    name = "netty_handler",
    visibility = [
        "//visibility:public",
    ],
    exports = [
        "//external:jar/io/netty/netty_handler",
    ],
    runtime_deps = [
        ":netty_buffer",
        ":netty_codec",
        ":netty_transport",
    ],
)

java_library(
    name = "netty_handler_proxy",
    visibility = [
        "//3rdparty/jvm:__subpackages__",
    ],
    exports = [
        "//external:jar/io/netty/netty_handler_proxy",
    ],
    runtime_deps = [
        ":netty_buffer",
        ":netty_codec",
        ":netty_codec_http",
        ":netty_codec_socks",
        ":netty_common",
        ":netty_transport",
    ],
)

java_library(
    name = "netty_resolver",
    visibility = [
        "//3rdparty/jvm:__subpackages__",
    ],
    exports = [
        "//external:jar/io/netty/netty_resolver",
    ],
    runtime_deps = [
        ":netty_common",
    ],
)

java_library(
    name = "netty_tcnative_boringssl_static",
    visibility = [
        "//visibility:public",
    ],
    exports = [
        "//external:jar/io/netty/netty_tcnative_boringssl_static",
    ],
)

java_library(
    name = "netty_transport",
    visibility = [
        "//3rdparty/jvm:__subpackages__",
    ],
    exports = [
        "//external:jar/io/netty/netty_transport",
    ],
    runtime_deps = [
        ":netty_buffer",
        ":netty_common",
        ":netty_resolver",
    ],
)
