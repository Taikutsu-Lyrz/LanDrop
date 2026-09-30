use thiserror::Error;

#[derive(Debug, Error)]
pub enum AppError {
    #[error("discovery service unavailable")]
    DiscoveryUnavailable,

    #[error("transfer service unavailable")]
    TransferUnavailable,

    #[error("internal state is unavailable")]
    StateUnavailable,
}

impl From<AppError> for String {
    fn from(error: AppError) -> Self {
        error.to_string()
    }
}
