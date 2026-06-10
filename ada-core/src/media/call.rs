//! Audio/Video call management via WebRTC
//!
//! Uses WebRTC for peer-to-peer media with:
//! - Opus audio (48kHz, stereo, variable bitrate up to 510kbps)
//! - H.264/VP8/VP9 video
//! - DTLS-SRTP for media encryption
//! - ICE/STUN/TURN for NAT traversal
//! - Adaptive bitrate based on network conditions

use crate::error::{ADAError, Result};
use crate::identity::PeerId;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{mpsc, RwLock};

/// Call identifier
pub type CallId = [u8; 16];

/// Ring timeout in seconds — auto-end if no answer.
const RING_TIMEOUT_SECS: u64 = 45;
/// ICE negotiation timeout in seconds after the call is answered.
const ICE_TIMEOUT_SECS: u64 = 30;

/// Direction of call
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub enum CallDirection {
    Outgoing,
    Incoming,
}

/// State of a call
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub enum CallState {
    /// Outgoing: waiting for peer to answer
    Ringing,
    /// Incoming: we've received an offer, not yet answered
    IncomingRinging,
    /// ICE/media negotiation in progress
    Connecting,
    /// Call is active
    Active { started_at: u64, has_video: bool },
    /// Call ended
    Ended {
        duration_secs: u64,
        reason: EndReason,
    },
    /// Call failed
    Failed(String),
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub enum EndReason {
    HungUp,
    Rejected,
    Busy,
    NetworkError,
    Timeout,
}

/// A single call
pub struct Call {
    pub id: CallId,
    pub peer: PeerId,
    pub direction: CallDirection,
    pub state: RwLock<CallState>,
    pub has_video: bool,
    pub is_group: bool,
    pub group_id: Option<[u8; 16]>,
    /// ICE candidates to send to peer
    pending_candidates: RwLock<Vec<IceCandidate>>,
    /// Current local SDP offer/answer
    pub local_sdp: RwLock<Option<String>>,
    /// Remote SDP
    pub remote_sdp: RwLock<Option<String>>,
    /// Unix timestamp when the call was created (for timeout checks)
    pub created_at: u64,
    /// Unix timestamp when the call became Active (for duration computation)
    pub connected_at: RwLock<Option<u64>>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct IceCandidate {
    pub candidate: String,
    pub sdp_mid: Option<String>,
    pub sdp_mline_index: Option<u16>,
}

impl Call {
    pub fn new_outgoing(peer: PeerId, has_video: bool) -> Self {
        let mut id = [0u8; 16];
        rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut id);
        Call {
            id,
            peer,
            direction: CallDirection::Outgoing,
            state: RwLock::new(CallState::Ringing),
            has_video,
            is_group: false,
            group_id: None,
            pending_candidates: RwLock::new(Vec::new()),
            local_sdp: RwLock::new(None),
            remote_sdp: RwLock::new(None),
            created_at: unix_now(),
            connected_at: RwLock::new(None),
        }
    }

    pub fn new_incoming(peer: PeerId, has_video: bool, offer_sdp: String) -> Self {
        let mut id = [0u8; 16];
        rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut id);
        Call {
            id,
            peer,
            direction: CallDirection::Incoming,
            state: RwLock::new(CallState::IncomingRinging),
            has_video,
            is_group: false,
            group_id: None,
            pending_candidates: RwLock::new(Vec::new()),
            local_sdp: RwLock::new(None),
            remote_sdp: RwLock::new(Some(offer_sdp)),
            created_at: unix_now(),
            connected_at: RwLock::new(None),
        }
    }

    pub async fn add_ice_candidate(&self, candidate: IceCandidate) {
        self.pending_candidates.write().await.push(candidate);
    }

    pub async fn drain_candidates(&self) -> Vec<IceCandidate> {
        let mut c = self.pending_candidates.write().await;
        std::mem::take(&mut *c)
    }

    pub async fn transition(&self, new_state: CallState) {
        *self.state.write().await = new_state;
    }

    pub async fn current_state(&self) -> CallState {
        self.state.read().await.clone()
    }

    /// Generate a minimal WebRTC SDP offer (real implementation would use webrtc crate)
    pub fn generate_offer(&self) -> String {
        // In a full implementation, this creates a real WebRTC PeerConnection
        // and generates an SDP offer with audio/video tracks.
        // The SDP below is a simplified placeholder structure.
        let video_section = if self.has_video {
            "\r\nm=video 9 UDP/TLS/RTP/SAVPF 96 97 98\r\na=rtpmap:96 VP9/90000\r\na=rtpmap:97 H264/90000\r\na=sendrecv"
        } else {
            ""
        };

        format!(
            "v=0\r\no=ada 0 0 IN IP4 0.0.0.0\r\ns=ADA Call\r\nt=0 0\r\n\
             a=group:BUNDLE 0{}\r\n\
             m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n\
             a=rtpmap:111 opus/48000/2\r\n\
             a=fmtp:111 minptime=10;useinbandfec=1;stereo=1;maxaveragebitrate=510000\r\n\
             a=sendrecv\r\n\
             a=rtcp-fb:111 transport-cc\r\n\
             {}\r\n",
            if self.has_video { " 1" } else { "" },
            video_section
        )
    }
}

/// Validate that an SDP string has the minimum required structure.
pub fn validate_sdp(sdp: &str) -> Result<()> {
    if !sdp.starts_with("v=0") {
        return Err(ADAError::Media("SDP missing version line (v=0)".into()));
    }
    if !sdp.contains("m=audio") && !sdp.contains("m=video") {
        return Err(ADAError::Media(
            "SDP missing media section (m=audio or m=video)".into(),
        ));
    }
    Ok(())
}

/// Manages all active calls
#[allow(dead_code)]
pub struct CallManager {
    calls: RwLock<HashMap<[u8; 16], Arc<Call>>>,
    /// Events sent to the API layer
    event_tx: mpsc::Sender<CallEvent>,
    /// STUN servers for ICE
    stun_servers: Vec<String>,
    /// TURN servers for fallback relay
    turn_servers: Vec<TurnServer>,
}

#[derive(Clone, Debug)]
pub struct TurnServer {
    pub url: String,
    pub username: String,
    pub credential: String,
}

/// Events emitted by the call manager
#[derive(Clone, Debug)]
pub enum CallEvent {
    IncomingCall {
        call_id: CallId,
        from: PeerId,
        has_video: bool,
        offer_sdp: String,
    },
    CallAnswered {
        call_id: CallId,
        answer_sdp: String,
    },
    IceCandidate {
        call_id: CallId,
        candidate: IceCandidate,
    },
    CallConnected {
        call_id: CallId,
    },
    CallEnded {
        call_id: CallId,
        reason: EndReason,
        duration_secs: u64,
    },
    CallFailed {
        call_id: CallId,
        reason: String,
    },
}

impl CallManager {
    pub fn new(
        stun_servers: Vec<String>,
        turn_servers: Vec<TurnServer>,
    ) -> (Self, mpsc::Receiver<CallEvent>) {
        let (tx, rx) = mpsc::channel(64);
        let mgr = CallManager {
            calls: RwLock::new(HashMap::new()),
            event_tx: tx,
            stun_servers,
            turn_servers,
        };
        (mgr, rx)
    }

    /// Initiate an outgoing call with a WebRTC offer SDP generated by the Android layer.
    pub async fn initiate_call(
        &self,
        peer: PeerId,
        offer_sdp: String,
        has_video: bool,
    ) -> Result<(CallId, String)> {
        validate_sdp(&offer_sdp)?;
        let call = Arc::new(Call::new_outgoing(peer.clone(), has_video));
        let call_id = call.id;
        *call.local_sdp.write().await = Some(offer_sdp.clone());

        self.calls.write().await.insert(call_id, call);
        Ok((call_id, offer_sdp))
    }

    /// Handle incoming call offer
    pub async fn handle_incoming(
        &self,
        from: PeerId,
        call_id_hint: Option<CallId>,
        offer_sdp: String,
        has_video: bool,
    ) -> Result<CallId> {
        validate_sdp(&offer_sdp)?;
        let call = Arc::new(Call::new_incoming(
            from.clone(),
            has_video,
            offer_sdp.clone(),
        ));
        let call_id = call_id_hint.unwrap_or(call.id);

        let mut calls = self.calls.write().await;

        // Reject if already on a call — send Busy to the caller.
        for existing in calls.values() {
            let state = existing.state.read().await.clone();
            match state {
                CallState::Ringing
                | CallState::IncomingRinging
                | CallState::Connecting
                | CallState::Active { .. } => {
                    tracing::info!(
                        "handle_incoming: busy — already have an active call, rejecting {}",
                        hex::encode(call_id)
                    );
                    return Err(ADAError::Media(format!(
                        "busy: already on a call (rejecting {})",
                        hex::encode(call_id)
                    )));
                }
                _ => {}
            }
        }

        if calls.contains_key(&call_id) {
            // Duplicate / retransmitted invite for an already-tracked call.
            // Overwriting would reset state for a call that may already be Active,
            // breaking the live session and potentially triggering a spurious hangup.
            tracing::warn!(
                "handle_incoming: duplicate invite for call {}, ignoring",
                hex::encode(call_id)
            );
            return Err(ADAError::Media(format!(
                "duplicate invite for call {}",
                hex::encode(call_id)
            )));
        }
        calls.insert(call_id, call);
        drop(calls);

        let _ = self
            .event_tx
            .send(CallEvent::IncomingCall {
                call_id,
                from,
                has_video,
                offer_sdp,
            })
            .await;

        Ok(call_id)
    }

    /// Answer an incoming call with a WebRTC answer SDP generated by the Android layer.
    pub async fn answer_call(&self, call_id: CallId, answer_sdp: String) -> Result<String> {
        validate_sdp(&answer_sdp)?;
        let calls = self.calls.read().await;
        let call = calls
            .get(&call_id)
            .ok_or(ADAError::Media("Call not found".into()))?;

        call.transition(CallState::Connecting).await;
        *call.local_sdp.write().await = Some(answer_sdp.clone());

        let _ = self
            .event_tx
            .send(CallEvent::CallAnswered {
                call_id,
                answer_sdp: answer_sdp.clone(),
            })
            .await;

        Ok(answer_sdp)
    }

    /// Hang up a call
    pub async fn hangup(&self, call_id: CallId) -> Result<()> {
        let mut calls = self.calls.write().await;
        if let Some(call) = calls.get(&call_id) {
            let duration = match *call.connected_at.read().await {
                Some(t) => unix_now().saturating_sub(t),
                None => 0,
            };
            call.transition(CallState::Ended {
                duration_secs: duration,
                reason: EndReason::HungUp,
            })
            .await;
            let _ = self
                .event_tx
                .send(CallEvent::CallEnded {
                    call_id,
                    reason: EndReason::HungUp,
                    duration_secs: duration,
                })
                .await;
        }
        calls.remove(&call_id);
        Ok(())
    }

    /// Set remote SDP (answer received)
    pub async fn set_remote_sdp(&self, call_id: CallId, sdp: String) -> Result<()> {
        validate_sdp(&sdp)?;
        let calls = self.calls.read().await;
        let call = calls
            .get(&call_id)
            .ok_or(ADAError::Media("Call not found".into()))?;
        *call.remote_sdp.write().await = Some(sdp);
        *call.connected_at.write().await = Some(unix_now());
        call.transition(CallState::Active {
            started_at: unix_now(),
            has_video: call.has_video,
        })
        .await;
        let _ = self
            .event_tx
            .send(CallEvent::CallConnected { call_id })
            .await;
        Ok(())
    }

    /// Add ICE candidate from remote peer
    pub async fn add_ice_candidate(&self, call_id: CallId, candidate: IceCandidate) -> Result<()> {
        let calls = self.calls.read().await;
        if let Some(call) = calls.get(&call_id) {
            call.add_ice_candidate(candidate).await;
        }
        Ok(())
    }

    pub async fn active_calls(&self) -> Vec<CallId> {
        self.calls.read().await.keys().copied().collect()
    }

    /// Remove a locally-prepared call that never reached signaling.
    pub async fn abort_call_setup(&self, call_id: CallId) {
        self.calls.write().await.remove(&call_id);
    }

    /// Get all active calls with full info (id, peer, has_video, is_outgoing, state).
    pub async fn active_calls_info(&self) -> Vec<(CallId, PeerId, bool, bool, CallState)> {
        let calls = self.calls.read().await;
        let mut result = Vec::new();
        for call in calls.values() {
            let state = call.state.read().await.clone();
            let outgoing = matches!(call.direction, CallDirection::Outgoing);
            result.push((call.id, call.peer.clone(), call.has_video, outgoing, state));
        }
        result
    }

    /// Reject an incoming call.
    pub async fn reject_call(&self, call_id: CallId) -> Result<()> {
        let mut calls = self.calls.write().await;
        if let Some(call) = calls.get(&call_id) {
            call.transition(CallState::Ended {
                duration_secs: 0,
                reason: EndReason::Rejected,
            })
            .await;
            let _ = self
                .event_tx
                .send(CallEvent::CallEnded {
                    call_id,
                    reason: EndReason::Rejected,
                    duration_secs: 0,
                })
                .await;
        }
        calls.remove(&call_id);
        Ok(())
    }

    /// Get call duration in seconds (0 if not yet active or ended).
    pub async fn call_duration(&self, call_id: CallId) -> u64 {
        let calls = self.calls.read().await;
        if let Some(call) = calls.get(&call_id) {
            if let Some(connected_at) = *call.connected_at.read().await {
                return unix_now().saturating_sub(connected_at);
            }
        }
        0
    }

    /// Check for timed-out ringing and connecting calls.
    ///
    /// Call periodically (e.g. every few seconds) to ensure unanswered calls
    /// don't linger indefinitely. Ringing calls are ended after
    /// [`RING_TIMEOUT_SECS`] and connecting calls after an additional
    /// [`ICE_TIMEOUT_SECS`].
    pub async fn check_timeouts(&self) -> usize {
        let now = unix_now();
        let calls = self.calls.read().await;
        let mut timed_out = Vec::new();

        for (&id, call) in calls.iter() {
            let state = call.state.read().await.clone();
            match state {
                CallState::Ringing | CallState::IncomingRinging => {
                    if now.saturating_sub(call.created_at) >= RING_TIMEOUT_SECS {
                        timed_out.push((id, "Ring timeout".to_string(), EndReason::Timeout));
                    }
                }
                CallState::Connecting => {
                    if now.saturating_sub(call.created_at) >= RING_TIMEOUT_SECS + ICE_TIMEOUT_SECS {
                        timed_out.push((
                            id,
                            "ICE negotiation timeout".to_string(),
                            EndReason::Timeout,
                        ));
                    }
                }
                _ => {}
            }
        }
        drop(calls);

        let count = timed_out.len();
        for (id, reason_str, reason) in timed_out {
            // Use a write lock so timed-out calls are removed from the map immediately.
            // Without removal, Ended calls accumulate and a late re-invite for the same
            // call_id would be rejected as a "duplicate" by handle_incoming.
            let mut calls = self.calls.write().await;
            if let Some(call) = calls.get(&id) {
                call.transition(CallState::Ended {
                    duration_secs: 0,
                    reason: reason.clone(),
                })
                .await;
            }
            calls.remove(&id);
            drop(calls);
            let _ = self
                .event_tx
                .send(CallEvent::CallEnded {
                    call_id: id,
                    reason,
                    duration_secs: 0,
                })
                .await;
            tracing::info!("[call] {} for call {:?}", reason_str, hex::encode(id));
        }
        count
    }
}

fn unix_now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn peer(byte: u8) -> PeerId {
        PeerId([byte; 32])
    }

    fn valid_audio_sdp() -> String {
        "v=0\r\no=ada 0 0 IN IP4 0.0.0.0\r\ns=ADA Call\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\na=sendrecv\r\n".to_string()
    }

    #[tokio::test]
    async fn answer_and_remote_sdp_transition_call_to_active() {
        let (manager, mut rx) = CallManager::new(Vec::new(), Vec::new());
        let offer_sdp = valid_audio_sdp();
        let answer_sdp = valid_audio_sdp();

        let (call_id, _) = manager
            .initiate_call(peer(7), offer_sdp.clone(), false)
            .await
            .expect("outgoing call should start");
        manager
            .answer_call(call_id, answer_sdp.clone())
            .await
            .expect("answer should be accepted");
        manager
            .set_remote_sdp(call_id, offer_sdp)
            .await
            .expect("remote SDP should activate the call");

        match rx.recv().await.expect("call answered event") {
            CallEvent::CallAnswered {
                call_id: answered_id,
                answer_sdp: emitted_sdp,
            } => {
                assert_eq!(answered_id, call_id);
                assert_eq!(emitted_sdp, answer_sdp);
            }
            other => panic!("unexpected event after answer: {:?}", other),
        }
        match rx.recv().await.expect("call connected event") {
            CallEvent::CallConnected {
                call_id: connected_id,
            } => {
                assert_eq!(connected_id, call_id);
            }
            other => panic!("unexpected event after remote SDP: {:?}", other),
        }

        let active_calls = manager.active_calls_info().await;
        assert_eq!(active_calls.len(), 1);
        let (active_id, active_peer, has_video, is_outgoing, state) = &active_calls[0];
        assert_eq!(*active_id, call_id);
        assert_eq!(active_peer, &peer(7));
        assert!(!has_video);
        assert!(*is_outgoing);
        assert!(matches!(
            state,
            CallState::Active {
                has_video: false,
                ..
            }
        ));
    }

    #[tokio::test]
    async fn duplicate_invite_for_same_call_id_is_rejected() {
        let (manager, mut rx) = CallManager::new(Vec::new(), Vec::new());
        let call_id = [9u8; 16];
        let offer_sdp = valid_audio_sdp();

        manager
            .handle_incoming(peer(11), Some(call_id), offer_sdp.clone(), true)
            .await
            .expect("first invite should be accepted");
        let duplicate = manager
            .handle_incoming(peer(11), Some(call_id), offer_sdp, true)
            .await;

        assert!(duplicate.is_err());
        match rx.recv().await.expect("incoming call event") {
            CallEvent::IncomingCall {
                call_id: event_call_id,
                from,
                has_video,
                ..
            } => {
                assert_eq!(event_call_id, call_id);
                assert_eq!(from, peer(11));
                assert!(has_video);
            }
            other => panic!("unexpected incoming call event: {:?}", other),
        }
    }

    #[tokio::test]
    async fn check_timeouts_removes_expired_connecting_calls() {
        let (manager, mut rx) = CallManager::new(Vec::new(), Vec::new());
        let call_id = [5u8; 16];
        let expired_call = Arc::new(Call {
            id: call_id,
            peer: peer(13),
            direction: CallDirection::Outgoing,
            state: RwLock::new(CallState::Connecting),
            has_video: false,
            is_group: false,
            group_id: None,
            pending_candidates: RwLock::new(Vec::new()),
            local_sdp: RwLock::new(Some(valid_audio_sdp())),
            remote_sdp: RwLock::new(None),
            created_at: unix_now().saturating_sub(RING_TIMEOUT_SECS + ICE_TIMEOUT_SECS + 1),
            connected_at: RwLock::new(None),
        });
        manager.calls.write().await.insert(call_id, expired_call);

        manager.check_timeouts().await;

        assert!(manager.active_calls().await.is_empty());
        match tokio::time::timeout(std::time::Duration::from_secs(1), rx.recv())
            .await
            .expect("timeout event should arrive")
            .expect("call ended event should exist")
        {
            CallEvent::CallEnded {
                call_id: ended_id,
                reason,
                duration_secs,
            } => {
                assert_eq!(ended_id, call_id);
                assert_eq!(reason, EndReason::Timeout);
                assert_eq!(duration_secs, 0);
            }
            other => panic!("unexpected timeout event: {:?}", other),
        }
    }
}
