use std::sync::OnceLock;

static INITIALIZED: OnceLock<()> = OnceLock::new();

pub fn init() {
    INITIALIZED.get_or_init(|| {
        let subscriber = tracing_subscriber::fmt()
            .with_env_filter("landrop=debug,tauri=info")
            .with_target(false)
            .compact()
            .finish();

        let _ = tracing::subscriber::set_global_default(subscriber);
    });
}
