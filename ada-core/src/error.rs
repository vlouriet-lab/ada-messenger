use thiserror::Error;

#[derive(Debug, Error)]
pub enum ADAError {
    #[error("Network error: {0}")]
    Network(String),
    #[error("Crypto error: {0}")]
    Crypto(String),
    #[error("Storage error: {0}")]
    Storage(String),
    #[error("Message error: {0}")]
    Message(String),
    #[error("Group error: {0}")]
    Group(String),
    #[error("Bridge error: {0}")]
    Bridge(String),
    #[error("Identity error: {0}")]
    Identity(String),
    #[error("Call error: {0}")]
    Call(String),
    #[error("Media error: {0}")]
    Media(String),
    #[error("Transfer error: {0}")]
    Transfer(String),
    #[error("Serialization error: {0}")]
    Serialization(#[from] bincode::Error),
    #[error("JSON error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
    #[error("Invalid signature")]
    InvalidSignature,
    #[error("Pattern error: {0}")]
    Pattern(String),
    #[error("Unknown error: {0}")]
    Unknown(String),
}

pub type Result<T> = std::result::Result<T, ADAError>;
