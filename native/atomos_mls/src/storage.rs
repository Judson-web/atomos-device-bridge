//! Authenticated encryption codec for OpenMLS SQLite storage.
//!
//! The database stores only AEAD ciphertext for serialized OpenMLS entities.
//! The 256-bit storage key is created/wrapped by Android Keystore and supplied
//! to this process through the JNI boundary. This is storage encryption only;
//! MLS protocol operations remain exclusively OpenMLS-owned.

use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    XChaCha20Poly1305, XNonce,
};
use openmls_sqlite_storage::Codec;
use rand_core::{OsRng, RngCore};
use serde::{de::DeserializeOwned, Serialize};
use std::{
    error::Error,
    fmt::{Display, Formatter},
    sync::{OnceLock, RwLock},
};
use zeroize::Zeroizing;

const MAGIC: &[u8; 8] = b"ATMLS001";
const NONCE_BYTES: usize = 24;
const STORAGE_AAD: &[u8] = b"Atomos OpenMLS storage v1";

static STORAGE_KEY: OnceLock<RwLock<Option<Zeroizing<[u8; 32]>>>> = OnceLock::new();

#[cfg(test)]
pub(crate) static TEST_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

fn key_slot() -> &'static RwLock<Option<Zeroizing<[u8; 32]>>> {
    STORAGE_KEY.get_or_init(|| RwLock::new(None))
}

#[derive(Debug)]
pub enum EncryptedCodecError {
    MissingStorageKey,
    InvalidEnvelope,
    Serialization(serde_json::Error),
    AuthenticationFailed,
}

impl Display for EncryptedCodecError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::MissingStorageKey => formatter.write_str("MLS storage key is not initialized"),
            Self::InvalidEnvelope => formatter.write_str("invalid encrypted MLS storage envelope"),
            Self::Serialization(_) => formatter.write_str("MLS storage serialization failed"),
            Self::AuthenticationFailed => formatter.write_str("MLS storage authentication failed"),
        }
    }
}

impl Error for EncryptedCodecError {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        match self {
            Self::Serialization(error) => Some(error),
            _ => None,
        }
    }
}

fn copy_storage_key() -> Result<Zeroizing<[u8; 32]>, EncryptedCodecError> {
    let guard = key_slot().read().unwrap_or_else(|poisoned| poisoned.into_inner());
    let stored = guard.as_ref().ok_or(EncryptedCodecError::MissingStorageKey)?;
    Ok(Zeroizing::new(**stored))
}

/// Install the account's random 256-bit database key in native memory.
/// Kotlin obtains this key by unwrapping it with the Android Keystore.
pub fn set_storage_key(bytes: &[u8]) -> Result<(), EncryptedCodecError> {
    if bytes.len() != 32 {
        return Err(EncryptedCodecError::InvalidEnvelope);
    }
    let mut key = [0u8; 32];
    key.copy_from_slice(bytes);
    let mut guard = key_slot().write().unwrap_or_else(|poisoned| poisoned.into_inner());
    *guard = Some(Zeroizing::new(key));
    Ok(())
}

/// Clear native key material when the engine is closed or the account is removed.
pub fn clear_storage_key() {
    let mut guard = key_slot().write().unwrap_or_else(|poisoned| poisoned.into_inner());
    *guard = None;
}

#[derive(Default)]
pub struct EncryptedCodec;

impl Codec for EncryptedCodec {
    type Error = EncryptedCodecError;

    fn to_vec<T: Serialize>(value: &T) -> Result<Vec<u8>, Self::Error> {
        let plaintext = Zeroizing::new(
            serde_json::to_vec(value).map_err(EncryptedCodecError::Serialization)?
        );
        let key = copy_storage_key()?;
        let cipher = XChaCha20Poly1305::new_from_slice(&key[..])
            .map_err(|_| EncryptedCodecError::InvalidEnvelope)?;

        let mut nonce = [0u8; NONCE_BYTES];
        OsRng.fill_bytes(&mut nonce);
        let ciphertext = cipher
            .encrypt(
                XNonce::from_slice(&nonce),
                Payload {
                    msg: &plaintext[..],
                    aad: STORAGE_AAD,
                },
            )
            .map_err(|_| EncryptedCodecError::AuthenticationFailed)?;

        let mut envelope = Vec::with_capacity(MAGIC.len() + nonce.len() + ciphertext.len());
        envelope.extend_from_slice(MAGIC);
        envelope.extend_from_slice(&nonce);
        envelope.extend_from_slice(&ciphertext);
        Ok(envelope)
    }

    fn from_slice<T: DeserializeOwned>(slice: &[u8]) -> Result<T, Self::Error> {
        if slice.len() <= MAGIC.len() + NONCE_BYTES || !slice.starts_with(MAGIC) {
            return Err(EncryptedCodecError::InvalidEnvelope);
        }

        let nonce_start = MAGIC.len();
        let ciphertext_start = nonce_start + NONCE_BYTES;
        let nonce = &slice[nonce_start..ciphertext_start];
        let ciphertext = &slice[ciphertext_start..];

        let key = copy_storage_key()?;
        let cipher = XChaCha20Poly1305::new_from_slice(key.as_ref())
            .map_err(|_| EncryptedCodecError::InvalidEnvelope)?;
        let plaintext = Zeroizing::new(cipher
            .decrypt(
                XNonce::from_slice(nonce),
                Payload {
                    msg: ciphertext,
                    aad: STORAGE_AAD,
                },
            )
            .map_err(|_| EncryptedCodecError::AuthenticationFailed)?);

        serde_json::from_slice(&plaintext[..]).map_err(EncryptedCodecError::Serialization)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde::{Deserialize, Serialize};


    #[derive(Debug, PartialEq, Serialize, Deserialize)]
    struct StoredState {
        group_id: String,
        secret_material: Vec<u8>,
        epoch: u64,
    }

    #[test]
    fn codec_round_trips_and_authenticates_serialized_state() {
        let _guard = TEST_LOCK.lock().expect("test lock");
        set_storage_key(&[0x42; 32]).expect("valid storage key");
        let state = StoredState {
            group_id: "opaque-group".into(),
            secret_material: vec![7, 8, 9, 10, 11],
            epoch: 17,
        };

        let encoded = EncryptedCodec::to_vec(&state).expect("encrypt state");
        assert!(encoded.starts_with(MAGIC));
        assert!(!encoded.windows(state.secret_material.len()).any(|part| part == state.secret_material.as_slice()));
        let decoded: StoredState = EncryptedCodec::from_slice(&encoded).expect("decrypt state");
        assert_eq!(decoded, state);

        let mut tampered = encoded;
        let last = tampered.len() - 1;
        tampered[last] ^= 0x80;
        assert!(matches!(
            EncryptedCodec::from_slice::<StoredState>(&tampered),
            Err(EncryptedCodecError::AuthenticationFailed)
        ));
        clear_storage_key();
    }

    #[test]
    fn codec_fails_closed_without_key() {
        let _guard = TEST_LOCK.lock().expect("test lock");
        clear_storage_key();
        assert!(matches!(
            EncryptedCodec::to_vec(&"not stored"),
            Err(EncryptedCodecError::MissingStorageKey)
        ));
    }
}
