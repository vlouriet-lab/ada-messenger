use crate::identity::PeerId;
use serde::{Deserialize, Serialize};

pub type GroupId = [u8; 16];

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum GroupRole {
    Owner,
    Admin,
    Member,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct GroupMember {
    pub peer_id: PeerId,
    pub role: GroupRole,
    pub joined_at: u64,
    pub display_name: String,
    pub has_sender_key: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Group {
    pub id: GroupId,
    pub name: String,
    pub description: String,
    pub avatar_file_id: Option<[u8; 16]>,
    pub members: Vec<GroupMember>,
    pub created_at: u64,
    pub created_by: PeerId,
    pub topic: String,
    pub version: u64,
    pub invite_link: Option<String>,
    pub max_members: u32,
}

impl Group {
    pub fn new(name: impl Into<String>, creator: PeerId) -> Self {
        let mut id = [0u8; 16];
        rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut id);
        let topic = format!("/ada/group/{}", hex::encode(id));
        Group {
            id,
            name: name.into(),
            description: String::new(),
            avatar_file_id: None,
            members: vec![GroupMember {
                peer_id: creator.clone(),
                role: GroupRole::Owner,
                joined_at: std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_secs(),
                display_name: String::new(),
                has_sender_key: true,
            }],
            created_at: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_secs(),
            created_by: creator,
            topic,
            version: 1,
            invite_link: None,
            max_members: 0,
        }
    }

    pub fn member_count(&self) -> usize {
        self.members.len()
    }

    pub fn is_admin(&self, peer: &PeerId) -> bool {
        self.members.iter().any(|m| {
            m.peer_id == *peer && (m.role == GroupRole::Owner || m.role == GroupRole::Admin)
        })
    }

    pub fn add_member(&mut self, peer: PeerId, display_name: String) {
        if !self.members.iter().any(|m| m.peer_id == peer) {
            self.members.push(GroupMember {
                peer_id: peer,
                role: GroupRole::Member,
                joined_at: std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_secs(),
                display_name,
                has_sender_key: false,
            });
            self.version += 1;
        }
    }

    pub fn remove_member(&mut self, peer: &PeerId) {
        self.members.retain(|m| m.peer_id != *peer);
        self.version += 1;
    }
}
