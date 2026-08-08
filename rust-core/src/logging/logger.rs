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
            let subscriber = tracing_subscriber::registry().with(android_layer);
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
