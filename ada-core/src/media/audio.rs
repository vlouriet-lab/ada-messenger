use crate::error::{ADAError, Result};
use opus::Application;
use serde::{Deserialize, Serialize};

/// Audio configuration
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct AudioConfig {
    /// Sample rate (48000 for Opus)
    pub sample_rate: u32,
    /// Number of channels (1=mono, 2=stereo)
    pub channels: u8,
    /// Frame duration in ms (2.5, 5, 10, 20, 40, 60)
    pub frame_duration_ms: u32,
    /// Target bitrate (bps), 0 = auto
    pub bitrate: u32,
    /// Enable Opus FEC (forward error correction)
    pub fec: bool,
    /// Enable DTX (discontinuous transmission for silence)
    pub dtx: bool,
}

impl Default for AudioConfig {
    fn default() -> Self {
        AudioConfig {
            sample_rate: 48000,
            channels: 2,
            frame_duration_ms: 20,
            bitrate: 0, // auto
            fec: true,
            dtx: true,
        }
    }
}

impl AudioConfig {
    /// High quality voice
    pub fn high_quality() -> Self {
        AudioConfig {
            sample_rate: 48000,
            channels: 2,
            frame_duration_ms: 20,
            bitrate: 128_000,
            fec: true,
            dtx: false,
        }
    }

    /// Low bandwidth mode
    pub fn low_bandwidth() -> Self {
        AudioConfig {
            sample_rate: 8000,
            channels: 1,
            frame_duration_ms: 40,
            bitrate: 8_000,
            fec: true,
            dtx: true,
        }
    }

    pub fn samples_per_frame(&self) -> usize {
        (self.sample_rate as usize * self.frame_duration_ms as usize) / 1000
    }
}

/// An audio frame
#[derive(Clone, Debug)]
pub struct AudioFrame {
    pub samples: Vec<i16>,
    pub channels: u8,
    pub sample_rate: u32,
    pub timestamp_ms: u64,
}

/// Audio track (abstraction over capture/playback)
pub struct AudioTrack {
    pub config: AudioConfig,
    pub track_id: String,
    pub is_muted: bool,
}

impl AudioTrack {
    pub fn new(config: AudioConfig) -> Self {
        AudioTrack {
            config,
            track_id: uuid::Uuid::new_v4().to_string(),
            is_muted: false,
        }
    }

    pub fn mute(&mut self) {
        self.is_muted = true;
    }
    pub fn unmute(&mut self) {
        self.is_muted = false;
    }
}

/// Opus encoder wrapper
pub struct OpusEncoder {
    config: AudioConfig,
    encoder: opus::Encoder,
    /// Accumulated audio samples not yet encoded
    buffer: Vec<i16>,
    /// Monotonically increasing sequence counter for encoded frames
    sequence: u16,
}

impl OpusEncoder {
    pub fn new(config: AudioConfig) -> Result<Self> {
        let channels = match config.channels {
            1 => opus::Channels::Mono,
            2 => opus::Channels::Stereo,
            _ => {
                return Err(ADAError::Media(format!(
                    "Unsupported channels: {}",
                    config.channels
                )))
            }
        };
        let mut encoder = opus::Encoder::new(config.sample_rate, channels, Application::Voip)
            .map_err(|e| ADAError::Media(format!("Failed to create Opus encoder: {}", e)))?;

        if config.bitrate > 0 {
            encoder
                .set_bitrate(opus::Bitrate::Bits(config.bitrate as i32))
                .map_err(|e| ADAError::Media(format!("Failed to set bitrate: {}", e)))?;
        }

        encoder
            .set_inband_fec(config.fec)
            .map_err(|e| ADAError::Media(format!("Failed to set FEC: {}", e)))?;

        encoder
            .set_dtx(config.dtx)
            .map_err(|e| ADAError::Media(format!("Failed to set DTX: {}", e)))?;

        Ok(OpusEncoder {
            config,
            encoder,
            buffer: Vec::new(),
            sequence: 0,
        })
    }

    /// Encode raw PCM samples to Opus frames
    /// Returns encoded frames (each is a complete Opus packet)
    pub fn encode(&mut self, samples: &[i16]) -> Result<Vec<EncodedAudio>> {
        self.buffer.extend_from_slice(samples);
        let frame_samples = self.config.samples_per_frame() * self.config.channels as usize;
        let mut frames = Vec::new();

        let mut output_buf = vec![0u8; 4000];

        while self.buffer.len() >= frame_samples {
            let chunk: Vec<i16> = self.buffer.drain(..frame_samples).collect();

            let len = self
                .encoder
                .encode(&chunk, &mut output_buf)
                .map_err(|e| ADAError::Media(format!("Opus encode error: {}", e)))?;

            let encoded = EncodedAudio {
                payload: output_buf[..len].to_vec(),
                timestamp_ms: unix_now_ms(),
                duration_ms: self.config.frame_duration_ms,
                sequence: self.sequence,
            };
            self.sequence = self.sequence.wrapping_add(1);
            frames.push(encoded);
        }

        Ok(frames)
    }
}

/// Opus decoder wrapper
pub struct OpusDecoder {
    config: AudioConfig,
    decoder: opus::Decoder,
}

impl OpusDecoder {
    pub fn new(config: AudioConfig) -> Result<Self> {
        let channels = match config.channels {
            1 => opus::Channels::Mono,
            2 => opus::Channels::Stereo,
            _ => {
                return Err(ADAError::Media(format!(
                    "Unsupported channels: {}",
                    config.channels
                )))
            }
        };
        let decoder = opus::Decoder::new(config.sample_rate, channels)
            .map_err(|e| ADAError::Media(format!("Failed to create Opus decoder: {}", e)))?;

        Ok(OpusDecoder { config, decoder })
    }

    pub fn decode(&mut self, packet: &EncodedAudio) -> Result<Vec<i16>> {
        let max_samples = self.config.samples_per_frame() * self.config.channels as usize;
        let mut output_buf = vec![0i16; max_samples];

        let len = self
            .decoder
            .decode(&packet.payload, &mut output_buf, false)
            .map_err(|e| ADAError::Media(format!("Opus decode error: {}", e)))?;

        output_buf.truncate(len);
        Ok(output_buf)
    }

    pub fn decode_fec(&mut self, next_packet: &EncodedAudio) -> Result<Vec<i16>> {
        let max_samples = self.config.samples_per_frame() * self.config.channels as usize;
        let mut output_buf = vec![0i16; max_samples];

        let len = self
            .decoder
            .decode(&next_packet.payload, &mut output_buf, true)
            .map_err(|e| ADAError::Media(format!("Opus decode FEC error: {}", e)))?;

        output_buf.truncate(len);
        Ok(output_buf)
    }
}

/// An encoded audio packet ready for transmission
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct EncodedAudio {
    pub payload: Vec<u8>,
    pub timestamp_ms: u64,
    pub duration_ms: u32,
    pub sequence: u16,
}

fn unix_now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

// ─── Adaptive bitrate controller ─────────────────────────────────────────────

/// Network quality level used for bitrate selection.
///
/// Ordinal ordering: `VeryPoor(0) < Poor(1) < Good(2) < Excellent(3)`.
/// A _lower_ value means worse quality → requires downgrade.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum NetworkQuality {
    /// <30 kbps — ultra-low bandwidth, DTX aggressive
    VeryPoor,
    /// 30-100 kbps — narrowband mono
    Poor,
    /// 100-500 kbps — standard stereo voice
    Good,
    /// >500 kbps available — use highest quality
    Excellent,
}

/// Network statistics snapshot reported by the WebRTC layer.
#[derive(Clone, Debug)]
pub struct NetworkStats {
    /// Available send bandwidth estimate (bps), 0 = unknown.
    pub available_bps: u64,
    /// Round-trip time in milliseconds.
    pub rtt_ms: u32,
    /// Packet loss fraction 0.0–1.0.
    pub packet_loss: f32,
    /// Jitter in milliseconds.
    pub jitter_ms: u32,
}

/// Adaptive bitrate controller for Opus audio.
///
/// Consumes periodic `NetworkStats` updates and recommends an `AudioConfig`
/// that best matches current network conditions.  The controller uses
/// conservative hysteresis to avoid oscillating between quality levels.
pub struct BitrateController {
    current_quality: NetworkQuality,
    /// Monotonic timestamp (ms) of the last quality level change.
    last_change_ms: u64,
    /// Minimum interval between downgrades (ms) — avoids thrashing.
    downgrade_cooldown_ms: u64,
    /// Minimum interval between upgrades (ms) — allows stabilisation.
    upgrade_cooldown_ms: u64,
}

impl BitrateController {
    pub fn new() -> Self {
        BitrateController {
            current_quality: NetworkQuality::Good,
            last_change_ms: unix_now_ms(),
            downgrade_cooldown_ms: 3_000,
            upgrade_cooldown_ms: 8_000,
        }
    }

    /// Update with fresh network stats and return the recommended
    /// `AudioConfig` (and whether it changed from the previous config).
    pub fn update(&mut self, stats: &NetworkStats) -> (AudioConfig, bool) {
        let now = unix_now_ms();
        let target = Self::quality_for_stats(stats);

        let changed = if target < self.current_quality {
            // Downgrade path — apply with cooldown
            if now.saturating_sub(self.last_change_ms) >= self.downgrade_cooldown_ms {
                self.current_quality = target;
                self.last_change_ms = now;
                true
            } else {
                false
            }
        } else if target > self.current_quality {
            // Upgrade path — require longer cooldown for stability
            if now.saturating_sub(self.last_change_ms) >= self.upgrade_cooldown_ms {
                self.current_quality = target;
                self.last_change_ms = now;
                true
            } else {
                false
            }
        } else {
            false
        };

        (self.config_for_quality(), changed)
    }

    /// Current quality level.
    pub fn quality(&self) -> &NetworkQuality {
        &self.current_quality
    }

    /// Determine target quality from raw stats.
    fn quality_for_stats(s: &NetworkStats) -> NetworkQuality {
        // High packet loss or severe latency degrade quality regardless of bandwidth
        if s.packet_loss > 0.10 || s.rtt_ms > 500 || s.jitter_ms > 100 {
            return NetworkQuality::VeryPoor;
        }
        if s.packet_loss > 0.05 || s.rtt_ms > 300 || s.jitter_ms > 50 {
            return NetworkQuality::Poor;
        }

        match s.available_bps {
            bw if bw >= 500_000 => NetworkQuality::Excellent,
            bw if bw >= 100_000 => NetworkQuality::Good,
            bw if bw >= 30_000 => NetworkQuality::Poor,
            _ => NetworkQuality::VeryPoor,
        }
    }

    /// Map a quality level to a concrete `AudioConfig`.
    fn config_for_quality(&self) -> AudioConfig {
        match self.current_quality {
            NetworkQuality::Excellent => AudioConfig {
                sample_rate: 48_000,
                channels: 2,
                frame_duration_ms: 20,
                bitrate: 128_000,
                fec: true,
                dtx: false,
            },
            NetworkQuality::Good => AudioConfig {
                sample_rate: 48_000,
                channels: 2,
                frame_duration_ms: 20,
                bitrate: 64_000,
                fec: true,
                dtx: true,
            },
            NetworkQuality::Poor => AudioConfig {
                sample_rate: 16_000,
                channels: 1,
                frame_duration_ms: 40,
                bitrate: 24_000,
                fec: true,
                dtx: true,
            },
            NetworkQuality::VeryPoor => AudioConfig {
                sample_rate: 8_000,
                channels: 1,
                frame_duration_ms: 60,
                bitrate: 8_000,
                fec: true,
                dtx: true,
            },
        }
    }
}

impl Default for BitrateController {
    fn default() -> Self {
        Self::new()
    }
}

// ─── Tests ───────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bitrate_controller_degrades_on_packet_loss() {
        let mut ctrl = BitrateController {
            current_quality: NetworkQuality::Excellent,
            last_change_ms: 0,
            downgrade_cooldown_ms: 0,
            upgrade_cooldown_ms: 0,
        };
        let stats = NetworkStats {
            available_bps: 1_000_000,
            rtt_ms: 50,
            packet_loss: 0.15,
            jitter_ms: 10,
        };
        let (cfg, changed) = ctrl.update(&stats);
        assert!(changed);
        assert_eq!(ctrl.quality(), &NetworkQuality::VeryPoor);
        assert_eq!(cfg.channels, 1);
    }

    #[test]
    fn bitrate_controller_respects_upgrade_cooldown() {
        let mut ctrl = BitrateController {
            current_quality: NetworkQuality::Poor,
            last_change_ms: unix_now_ms(),
            downgrade_cooldown_ms: 3_000,
            upgrade_cooldown_ms: 8_000,
        };
        let good_stats = NetworkStats {
            available_bps: 1_000_000,
            rtt_ms: 10,
            packet_loss: 0.0,
            jitter_ms: 5,
        };
        let (_cfg, changed) = ctrl.update(&good_stats);
        // Still within cooldown — must NOT upgrade yet
        assert!(!changed);
        assert_eq!(ctrl.quality(), &NetworkQuality::Poor);
    }

    #[test]
    fn opus_encoder_buffers_samples_until_full_frame() {
        let mut enc = OpusEncoder::new(AudioConfig::default()).unwrap();
        // Feed less than one frame — should produce no output
        let frames = enc.encode(&vec![0i16; 100]).unwrap();
        assert!(frames.is_empty());
        // Feed enough for several frames
        let samples_per_frame = AudioConfig::default().samples_per_frame() * 2; // stereo
        let big = enc.encode(&vec![0i16; samples_per_frame * 3]).unwrap();
        assert_eq!(big.len(), 3);
    }
}
