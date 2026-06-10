//! `relay_reputation.rs` — локальная модель репутации relay-узлов.
//!
//! Borrowed from Plex project. All functions are pure (no I/O, no state),
//! so persistence is left to the caller (store scores in KeyValueStore).
//!
//! ## Scoring model
//! - Default neutral score: 50 (range 0..=100)
//! - Each successful relay operation: +3
//! - Each failed relay operation: −8
//! - Every 6 hours without events: score drifts 1 step toward 50 (decay)
//!
//! ## Usage
//! Load score from KV, call `score_after_event` / `apply_decay`, save back.

pub const DEFAULT_REPUTATION: i64 = 50;
pub const MIN_REPUTATION: i64 = 0;
pub const MAX_REPUTATION: i64 = 100;

const SUCCESS_BONUS: i64 = 3;
const FAILURE_PENALTY: i64 = 8;
/// One decay step every 6 hours.
const DECAY_STEP_SECS: i64 = 6 * 60 * 60;

fn clamp_score(score: i64) -> i64 {
    score.clamp(MIN_REPUTATION, MAX_REPUTATION)
}

/// Smoothly returns `score` toward `DEFAULT_REPUTATION` over time.
/// Call this whenever you load a stored score to account for elapsed time
/// since it was last updated.
pub fn apply_decay(score: i64, elapsed_secs: i64) -> i64 {
    if elapsed_secs <= 0 {
        return clamp_score(score);
    }
    let steps = elapsed_secs / DECAY_STEP_SECS;
    if steps <= 0 {
        return clamp_score(score);
    }
    if score > DEFAULT_REPUTATION {
        clamp_score((score - steps).max(DEFAULT_REPUTATION))
    } else if score < DEFAULT_REPUTATION {
        clamp_score((score + steps).min(DEFAULT_REPUTATION))
    } else {
        DEFAULT_REPUTATION
    }
}

/// Update score after a relay operation result.
/// `success = true` → bonus; `success = false` → penalty.
pub fn score_after_event(current_score: i64, success: bool) -> i64 {
    if success {
        clamp_score(current_score + SUCCESS_BONUS)
    } else {
        clamp_score(current_score - FAILURE_PENALTY)
    }
}

/// Returns uptime percentage given success and total operation counts.
pub fn uptime_percent(success_count: i64, total_count: i64) -> f64 {
    if total_count <= 0 {
        return 0.0;
    }
    ((success_count.max(0) as f64) * 100.0 / (total_count as f64)).clamp(0.0, 100.0)
}

/// Returns `true` if this relay should be preferred over peers with lower scores.
/// Threshold: above 60 = preferred; below 30 = avoid.
pub fn is_preferred(score: i64) -> bool {
    score >= 60
}

pub fn should_avoid(score: i64) -> bool {
    score < 30
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decay_moves_score_towards_default() {
        let elapsed = 12 * 60 * 60; // 12 hours = 2 steps
        assert_eq!(apply_decay(70, elapsed), 68);
        assert_eq!(apply_decay(30, elapsed), 32);
        assert_eq!(apply_decay(50, elapsed), 50); // already at default
    }

    #[test]
    fn decay_does_not_overshoot_default() {
        assert_eq!(apply_decay(51, 6 * 60 * 60), 50); // exactly 1 step
        assert_eq!(apply_decay(49, 6 * 60 * 60), 50);
    }

    #[test]
    fn score_after_event_applies_bonus_and_penalty() {
        assert_eq!(score_after_event(50, true), 53);
        assert_eq!(score_after_event(50, false), 42);
    }

    #[test]
    fn clamping() {
        assert_eq!(score_after_event(99, true), 100);
        assert_eq!(score_after_event(3, false), 0);
    }

    #[test]
    fn uptime_percent_is_bounded() {
        assert_eq!(uptime_percent(0, 0), 0.0);
        assert_eq!(uptime_percent(7, 10), 70.0);
        assert_eq!(uptime_percent(10, 10), 100.0);
    }

    #[test]
    fn preferred_and_avoid_thresholds() {
        assert!(is_preferred(60));
        assert!(!is_preferred(59));
        assert!(should_avoid(29));
        assert!(!should_avoid(30));
    }
}
