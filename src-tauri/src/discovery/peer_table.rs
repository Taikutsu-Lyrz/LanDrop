use crate::types::PeerDevice;

#[derive(Debug, Default, Clone)]
pub struct PeerTable {
    peers: Vec<PeerDevice>,
}

impl PeerTable {
    pub fn replace(&mut self, peers: Vec<PeerDevice>) {
        self.peers = peers;
    }

    pub fn list(&self) -> &[PeerDevice] {
        &self.peers
    }
}
