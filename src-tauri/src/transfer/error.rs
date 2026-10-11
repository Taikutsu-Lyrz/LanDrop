pub const PEER_CANCELED: &str = "Transfer canceled by peer.";
pub const USER_CANCELED: &str = "Transfer canceled by user.";
pub const PEER_DISCONNECTED: &str = "Peer disconnected before the transfer finished.";

pub fn is_cancellation_error(error: &str) -> bool {
    let lower = error.to_ascii_lowercase();
    lower.contains("canceled") || lower.contains("cancelled")
}

pub fn transfer_error_message(error: String, locally_canceled: bool) -> String {
    if locally_canceled {
        return USER_CANCELED.to_string();
    }
    if is_cancellation_error(&error) {
        return error;
    }
    let lower = error.to_ascii_lowercase();
    if [
        "early eof",
        "unexpected eof",
        "connection reset",
        "connection aborted",
        "forcibly closed",
        "broken pipe",
        "peer closed the connection",
    ]
    .iter()
    .any(|message| lower.contains(message))
    {
        PEER_DISCONNECTED.to_string()
    } else {
        error
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn eof_is_a_disconnect_unless_the_user_canceled() {
        assert_eq!(
            transfer_error_message("early eof".into(), false),
            PEER_DISCONNECTED
        );
        assert_eq!(
            transfer_error_message("early eof".into(), true),
            USER_CANCELED
        );
        assert_eq!(
            transfer_error_message(PEER_CANCELED.into(), false),
            PEER_CANCELED
        );
        assert_eq!(
            transfer_error_message("disk full".into(), false),
            "disk full"
        );
    }
}
