use crate::error::{ADAError, Result};
use crate::group::sender_keys::{SenderKeyDistribution, SenderKeyMessage, SenderKeySession};
use crate::group::types::Group;
use crate::identity::{Identity, PeerId};
use parking_lot::RwLock;
use std::collections::HashMap;
use std::sync::Arc;

/// Manages all group chats
pub struct GroupManager {
    groups: RwLock<HashMap<[u8; 16], Group>>,
    sessions: RwLock<HashMap<[u8; 16], SenderKeySession>>,
    identity: Arc<Identity>,
}

impl GroupManager {
    pub fn new(identity: Arc<Identity>) -> Self {
        GroupManager {
            groups: RwLock::new(HashMap::new()),
            sessions: RwLock::new(HashMap::new()),
            identity,
        }
    }

    fn ensure_local_membership(&self, group: &mut Group) {
        if let Some(member) = group
            .members
            .iter_mut()
            .find(|member| member.peer_id == self.identity.peer_id)
        {
            member.has_sender_key = true;
            return;
        }

        group.add_member(self.identity.peer_id.clone(), String::new());
        if let Some(member) = group
            .members
            .iter_mut()
            .find(|member| member.peer_id == self.identity.peer_id)
        {
            member.has_sender_key = true;
        }
    }

    fn mark_member_has_sender_key(&self, group_id: [u8; 16], peer: &PeerId) {
        let mut groups = self.groups.write();
        let Some(group) = groups.get_mut(&group_id) else {
            return;
        };
        if let Some(member) = group
            .members
            .iter_mut()
            .find(|member| member.peer_id == *peer)
        {
            member.has_sender_key = true;
        }
    }

    /// Create a new group
    pub fn create_group(&self, name: impl Into<String>) -> ([u8; 16], SenderKeyDistribution) {
        let group = Group::new(name, self.identity.peer_id.clone());
        let id = group.id;

        self.groups.write().insert(id, group);

        let mut sessions = self.sessions.write();
        let session = sessions
            .entry(id)
            .or_insert_with(|| SenderKeySession::new(id));
        let dist = session.init_sender_key(self.identity.peer_id.clone());

        (id, dist)
    }

    /// Join an existing group
    pub fn join_group(&self, mut group: Group, our_dist: SenderKeyDistribution) {
        self.ensure_local_membership(&mut group);
        let id = group.id;
        self.groups.write().insert(id, group);

        let mut sessions = self.sessions.write();
        let session = sessions
            .entry(id)
            .or_insert_with(|| SenderKeySession::new(id));
        // Install our own key distribution
        session.install_sender_key(our_dist);
    }

    /// Join a group from an invite: install inviter's key, generate and return our own key.
    pub fn join_group_and_init(
        &self,
        mut group: Group,
        inviter_dist: SenderKeyDistribution,
    ) -> SenderKeyDistribution {
        self.ensure_local_membership(&mut group);
        let id = group.id;
        self.groups.write().insert(id, group);

        let mut sessions = self.sessions.write();
        let session = sessions
            .entry(id)
            .or_insert_with(|| SenderKeySession::new(id));
        session.install_sender_key(inviter_dist);
        session.init_sender_key(self.identity.peer_id.clone())
    }

    /// Install a peer's sender key for a group
    pub fn install_peer_key(&self, group_id: [u8; 16], dist: SenderKeyDistribution) -> Result<()> {
        let sender = dist.sender.clone();
        let mut sessions = self.sessions.write();
        let session = sessions
            .get_mut(&group_id)
            .ok_or(ADAError::Group("Group not found".into()))?;
        session.install_sender_key(dist);
        drop(sessions);
        self.mark_member_has_sender_key(group_id, &sender);
        Ok(())
    }

    /// Encrypt a message for a group
    pub fn encrypt_group_message(
        &self,
        group_id: [u8; 16],
        plaintext: &[u8],
    ) -> Result<SenderKeyMessage> {
        let aad = group_id.as_slice();
        let mut sessions = self.sessions.write();
        let session = sessions
            .get_mut(&group_id)
            .ok_or(ADAError::Group("No session for group".into()))?;
        session.encrypt(plaintext, aad)
    }

    /// Decrypt a group message
    pub fn decrypt_group_message(&self, msg: &SenderKeyMessage) -> Result<Vec<u8>> {
        let aad = msg.group_id.as_slice();
        let mut sessions = self.sessions.write();
        let session = sessions
            .get_mut(&msg.group_id)
            .ok_or(ADAError::Group("No session for group".into()))?;
        session.decrypt(msg, aad)
    }

    /// Add a member to a group (admin only)
    pub fn add_member(
        &self,
        group_id: [u8; 16],
        peer: PeerId,
        display_name: String,
        requestor: &PeerId,
    ) -> Result<()> {
        let mut groups = self.groups.write();
        let group = groups
            .get_mut(&group_id)
            .ok_or(ADAError::Group("Group not found".into()))?;

        if !group.is_admin(requestor) {
            return Err(ADAError::Group("Not authorized to add members".into()));
        }

        group.add_member(peer, display_name);
        Ok(())
    }

    /// Remove a member (admin only, or self-leave)
    pub fn remove_member(
        &self,
        group_id: [u8; 16],
        peer: &PeerId,
        requestor: &PeerId,
    ) -> Result<SenderKeyDistribution> {
        {
            let mut groups = self.groups.write();
            let group = groups
                .get_mut(&group_id)
                .ok_or(ADAError::Group("Group not found".into()))?;

            if peer != requestor && !group.is_admin(requestor) {
                return Err(ADAError::Group("Not authorized to remove members".into()));
            }

            group.remove_member(peer);
        }

        // Rotate sender key after membership change
        let mut sessions = self.sessions.write();
        let session = sessions
            .get_mut(&group_id)
            .ok_or(ADAError::Group("No session for group".into()))?;
        let dist = session.rotate_sender_key(self.identity.peer_id.clone());
        Ok(dist)
    }

    pub fn get_group(&self, id: &[u8; 16]) -> Option<Group> {
        self.groups.read().get(id).cloned()
    }

    pub fn list_groups(&self) -> Vec<Group> {
        self.groups.read().values().cloned().collect()
    }

    pub fn group_topic(&self, id: &[u8; 16]) -> Option<String> {
        self.groups.read().get(id).map(|g| g.topic.clone())
    }

    pub fn our_distribution(&self, group_id: [u8; 16]) -> Option<SenderKeyDistribution> {
        self.sessions.read().get(&group_id)?.our_distribution()
    }
}
