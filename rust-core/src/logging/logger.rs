//! Инициализация системы логирования.
//! Android → logcat через tracing-android.
//! Desktop/тесты → stderr через tracing-subscriber::fmt.

use std::sync::Once;

static INIT: Once = Once::new();

pub fn init_logger() {
    INIT.call_once(|| {
        #[cfg(target_os = "android")]
        {
            use tracing_subscriber::prelude::*;
            let android_layer = match tracing_android::layer("p2p_core") {
                Ok(l) => l,
                Err(_) => return,
            };
            // QUIC libraries emit trace/debug records in packet hot paths.
            // Keep transport warnings and all existing APU diagnostics.
            let transport_filter = tracing_subscriber::filter::filter_fn(|meta| {
                let quic = matches!(meta.target().split("::").next(),
                    Some("quinn" | "quinn_proto" | "quinn_udp"));
                !quic || matches!(*meta.level(),
                    tracing::Level::ERROR | tracing::Level::WARN | tracing::Level::INFO)
            });
            let subscriber = tracing_subscriber::registry().with(android_layer.with_filter(transport_filter));
            let _ = tracing::subscriber::set_global_default(subscriber);
        }

        #[cfg(not(target_os = "android"))]
        {
            let _ = tracing_subscriber::fmt()
                .with_max_level(tracing::Level::DEBUG)
                .try_init();
        }
    });
}
