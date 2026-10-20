//! Contact key verification.
//!
//! Two primitives live here, and they only make sense together:
//!
//! 1. **IK binding** ([`sign_ik`] / [`verify_ik_signature`]).  A contact card
//!    carries the peer's X25519 X3DH identity key (`ik_public`) next to a signed
//!    pre-key.  Only the pre-key was ever signed, so anyone able to rewrite a card
//!    in transit could swap `ik_public`, keep the valid `spk_signature`, complete
//!    X3DH against their own key and sit in the middle — with every signature check
//!    still passing.  This signature binds the key X3DH actually uses to the
//!    Ed25519 identity (`PeerId`).
//!
//! 2. **Safety numbers** ([`safety_number`]).  The Signal-style out-of-band
//!    comparison value.  It is derived from both `PeerId`s, so it authenticates
//!    *identities*; the IK binding above is what makes it cover the X3DH keys too.
//!    Verifying a safety number is the only way to detect a man-in-the-middle who
//!    was present at first contact (TOFU cannot: it has no earlier value to compare
//!    against), while pinning detects later key substitution automatically.

use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use sha2::{Digest, Sha512};

use crate::{
    error::{ADAError, Result},
    identity::PeerId,
};

/// Domain separation for the IK binding signature.
///
/// Never change this string: it is what previously issued contact cards are
/// verified against.
pub const IK_SIGN_PREFIX: &[u8] = b"ADA-IK-v1:";

/// Domain separation + scheme version for safety numbers.
const SAFETY_NUMBER_PREFIX: &[u8] = b"ada/safety-number/v1";
const SAFETY_NUMBER_VERSION: u8 = 1;

/// Number of digit groups in a safety number.  12 groups of 5 = 60 digits, the
/// same shape Signal uses, which stays readable when split over a phone screen.
pub const SAFETY_GROUP_COUNT: usize = 12;
const GROUP_DIGITS: usize = 5;

/// Sign `ik_public` with the identity signing key.
///
/// The signed message is `IK_SIGN_PREFIX || ik_public` — not the bare key — so the
/// signature cannot be replayed as a signature over some other statement.
pub fn sign_ik(signing_key: &SigningKey, ik_public: &[u8; 32]) -> Vec<u8> {
    let mut message = Vec::with_capacity(IK_SIGN_PREFIX.len() + 32);
    message.extend_from_slice(IK_SIGN_PREFIX);
    message.extend_from_slice(ik_public);
    signing_key.sign(&message).to_bytes().to_vec()
}

/// Verify that `ik_public` was signed by the owner of `signing_public`.
pub fn verify_ik_signature(
    ik_public: &[u8; 32],
    signing_public: &[u8; 32],
    signature: &[u8],
) -> Result<()> {
    let verifying_key =
        VerifyingKey::from_bytes(signing_public).map_err(|_| ADAError::InvalidSignature)?;
    let signature_bytes: [u8; 64] = signature
        .try_into()
        .map_err(|_| ADAError::InvalidSignature)?;
    let signature = Signature::from_bytes(&signature_bytes);

    let mut message = Vec::with_capacity(IK_SIGN_PREFIX.len() + 32);
    message.extend_from_slice(IK_SIGN_PREFIX);
    message.extend_from_slice(ik_public);
    verifying_key
        .verify(&message, &signature)
        .map_err(|_| ADAError::InvalidSignature)
}

/// A 60-digit comparison value for a pair of identities.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SafetyNumber {
    digits: [u8; SAFETY_GROUP_COUNT * GROUP_DIGITS],
}

impl SafetyNumber {
    /// The digits without separators.
    pub fn digits(&self) -> String {
        self.digits
            .iter()
            .map(|digit| char::from(b'0' + digit))
            .collect()
    }

    /// The digits as [`SAFETY_GROUP_COUNT`] groups of five.
    pub fn groups(&self) -> Vec<String> {
        self.digits
            .chunks(GROUP_DIGITS)
            .map(|chunk| chunk.iter().map(|digit| char::from(b'0' + digit)).collect())
            .collect()
    }

    /// Space-separated groups: one line the user can read out loud.
    pub fn formatted(&self) -> String {
        self.groups().join(" ")
    }

    /// Compare against anything the user may paste back, ignoring separators.
    pub fn matches(&self, other: &str) -> bool {
        let normalized: String = other.chars().filter(char::is_ascii_digit).collect();
        normalized.len() == self.digits.len() && normalized == self.digits()
    }
}

/// Compute the safety number for a pair of identities.
///
/// The two keys are sorted before hashing so both sides derive the same value
/// regardless of who asks.
pub fn safety_number(our: &PeerId, theirs: &PeerId) -> SafetyNumber {
    let (first, second) = if our.0 <= theirs.0 {
        (&our.0, &theirs.0)
    } else {
        (&theirs.0, &our.0)
    };

    let mut hasher = Sha512::new();
    hasher.update(SAFETY_NUMBER_PREFIX);
    hasher.update([SAFETY_NUMBER_VERSION]);
    hasher.update(first);
    hasher.update(second);
    let digest = hasher.finalize();

    // Fold five digest bytes into one group.  5 bytes = 40 bits, which fits a u64
    // with room to spare, and `% 100_000` yields exactly five decimal digits.
    let mut digits = [0u8; SAFETY_GROUP_COUNT * GROUP_DIGITS];
    for group in 0..SAFETY_GROUP_COUNT {
        let offset = group * GROUP_DIGITS;
        let mut accumulator: u64 = 0;
        for byte in &digest[offset..offset + GROUP_DIGITS] {
            accumulator = (accumulator << 8) | u64::from(*byte);
        }

        let mut value = accumulator % 100_000;
        for slot in (0..GROUP_DIGITS).rev() {
            digits[offset + slot] = (value % 10) as u8;
            value /= 10;
        }
    }

    SafetyNumber { digits }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn peer(byte: u8) -> PeerId {
        PeerId([byte; 32])
    }

    #[test]
    fn ik_signature_round_trips() {
        let signing_key = SigningKey::from_bytes(&[7u8; 32]);
        let ik_public = [9u8; 32];

        let signature = sign_ik(&signing_key, &ik_public);

        verify_ik_signature(
            &ik_public,
            &signing_key.verifying_key().to_bytes(),
            &signature,
        )
        .expect("signature produced by the same key must verify");
    }

    /// The whole point of the binding: a substituted IK must not verify.
    #[test]
    fn ik_signature_rejects_swapped_ik() {
        let signing_key = SigningKey::from_bytes(&[7u8; 32]);
        let signature = sign_ik(&signing_key, &[9u8; 32]);

        let err = verify_ik_signature(
            &[8u8; 32],
            &signing_key.verifying_key().to_bytes(),
            &signature,
        )
        .expect_err("a signature over a different IK must not verify");

        assert!(matches!(err, ADAError::InvalidSignature));
    }

    #[test]
    fn ik_signature_rejects_foreign_signer() {
        let signing_key = SigningKey::from_bytes(&[7u8; 32]);
        let other_key = SigningKey::from_bytes(&[8u8; 32]);
        let signature = sign_ik(&signing_key, &[9u8; 32]);

        let err = verify_ik_signature(
            &[9u8; 32],
            &other_key.verifying_key().to_bytes(),
            &signature,
        )
        .expect_err("a signature from another identity must not verify");

        assert!(matches!(err, ADAError::InvalidSignature));
    }

    #[test]
    fn ik_signature_rejects_malformed_signature() {
        let signing_key = SigningKey::from_bytes(&[7u8; 32]);

        let err = verify_ik_signature(
            &[9u8; 32],
            &signing_key.verifying_key().to_bytes(),
            &[0u8; 12],
        )
        .expect_err("a short signature must be rejected, not panic");

        assert!(matches!(err, ADAError::InvalidSignature));
    }

    #[test]
    fn safety_number_is_symmetric_and_well_formed() {
        let alice = peer(1);
        let bob = peer(2);

        let from_alice = safety_number(&alice, &bob);
        let from_bob = safety_number(&bob, &alice);

        assert_eq!(
            from_alice, from_bob,
            "both sides must derive the same safety number"
        );
        assert_eq!(from_alice.digits().len(), 60);
        assert_eq!(from_alice.groups().len(), SAFETY_GROUP_COUNT);
        assert!(from_alice.groups().iter().all(|group| group.len() == 5));
        assert!(from_alice
            .digits()
            .chars()
            .all(|character| character.is_ascii_digit()));
        assert_eq!(from_alice.formatted(), from_alice.groups().join(" "));
    }

    #[test]
    fn safety_number_distinguishes_identities() {
        let alice = peer(1);

        assert_ne!(
            safety_number(&alice, &peer(2)),
            safety_number(&alice, &peer(3)),
            "different peers must not share a safety number"
        );
    }

    #[test]
    fn safety_number_is_stable_for_the_same_pair() {
        let alice = peer(1);
        let bob = peer(2);

        assert_eq!(
            safety_number(&alice, &bob),
            safety_number(&alice, &bob),
            "the value must not change between calls"
        );
    }

    #[test]
    fn safety_number_matches_ignores_formatting() {
        let number = safety_number(&peer(1), &peer(2));
        let spaced = number.formatted();
        let dashed = number.groups().join("-");

        assert!(number.matches(&spaced));
        assert!(number.matches(&dashed));
        assert!(number.matches(&number.digits()));

        assert!(!number.matches("00000"), "a truncated value must not match");
        assert!(!number.matches(""), "an empty value must not match");
    }
}
