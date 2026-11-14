//! Durable OpenMLS storage provider.
//!
//! Crypto and randomness are delegated to OpenMLS RustCrypto. Persistent entities
//! are serialized by OpenMLS SQLite storage and encrypted by EncryptedCodec before
//! they are written to SQLite. This module does not implement MLS protocol logic.

use crate::storage::EncryptedCodec;
use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use openmls::prelude::{
    Ciphersuite, CredentialWithKey, GroupId, KeyPackage, KeyPackageIn,
    MlsGroup, MlsGroupJoinConfig, MlsMessageBodyIn, MlsMessageIn,
    BasicCredential, ProcessedMessageContent, ProtocolVersion, StagedWelcome, WelcomeError,
};
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::RustCrypto;
use openmls_sqlite_storage::{Codec, SqliteStorageProvider};
use openmls_traits::OpenMlsProvider;
use rusqlite::{Connection, OptionalExtension};
use serde::{de::DeserializeOwned, Serialize as SerdeSerialize};
use std::error::Error;
use std::time::Duration;
use tls_codec::{Deserialize, Serialize};

pub type EngineResult<T> = Result<T, Box<dyn Error + Send + Sync>>;

pub struct AtomosProvider<'a> {
    crypto: &'a RustCrypto,
    storage: SqliteStorageProvider<EncryptedCodec, &'a mut Connection>,
}

impl<'a> AtomosProvider<'a> {
    fn new(crypto: &'a RustCrypto, connection: &'a mut Connection) -> Self {
        Self {
            crypto,
            storage: SqliteStorageProvider::new(connection),
        }
    }
}

impl<'a> OpenMlsProvider for AtomosProvider<'a> {
    type CryptoProvider = RustCrypto;
    type RandProvider = RustCrypto;
    type StorageProvider = SqliteStorageProvider<EncryptedCodec, &'a mut Connection>;

    fn storage(&self) -> &Self::StorageProvider {
        &self.storage
    }

    fn crypto(&self) -> &Self::CryptoProvider {
        self.crypto
    }

    fn rand(&self) -> &Self::RandProvider {
        self.crypto
    }
}

pub struct AtomosMlsEngine {
    connection: Connection,
    crypto: RustCrypto,
}

impl AtomosMlsEngine {
    pub fn open(path: &str) -> EngineResult<Self> {
        if path.is_empty() || path.len() > 4096 {
            return Err("invalid MLS database path".into());
        }

        let mut connection = Connection::open(path)?;
        connection.busy_timeout(Duration::from_secs(5))?;
        connection.execute_batch(
            "PRAGMA journal_mode=WAL;
             PRAGMA synchronous=FULL;
             PRAGMA foreign_keys=ON;
             CREATE TABLE IF NOT EXISTS atomos_mls_metadata (
               key TEXT PRIMARY KEY NOT NULL,
               value BLOB NOT NULL
             );",
        )?;

        {
            let mut storage = SqliteStorageProvider::<EncryptedCodec, &mut Connection>::new(&mut connection);
            storage.run_migrations()?;
        }

        Ok(Self {
            connection,
            crypto: RustCrypto::default(),
        })
    }

    fn metadata_get<T: DeserializeOwned>(&self, key: &str) -> EngineResult<Option<T>> {
        let encoded: Option<Vec<u8>> = self.connection
            .query_row(
                "SELECT value FROM atomos_mls_metadata WHERE key = ?1",
                [key],
                |row| row.get(0),
            )
            .optional()?;
        match encoded {
            Some(value) => Ok(Some(EncryptedCodec::from_slice(&value)?)),
            None => Ok(None),
        }
    }

    fn metadata_put<T: SerdeSerialize>(&mut self, key: &str, value: &T) -> EngineResult<()> {
        let encoded = EncryptedCodec::to_vec(value)?;
        self.connection.execute(
            "INSERT INTO atomos_mls_metadata (key, value) VALUES (?1, ?2)
             ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (key, &encoded),
        )?;
        Ok(())
    }

    fn metadata_delete(&mut self, key: &str) -> EngineResult<()> {
        self.connection.execute(
            "DELETE FROM atomos_mls_metadata WHERE key = ?1",
            [key],
        )?;
        Ok(())
    }

    fn load_signing_key(&self, device_id: &str) -> EngineResult<SignatureKeyPair> {
        let metadata_key = format!("device-signing-key:{device_id}");
        self.metadata_get::<SignatureKeyPair>(&metadata_key)?
            .ok_or_else(|| "device signing key is not enrolled in this local MLS store".into())
    }

    /// Creates a batch of device-bound MLS KeyPackages. The long-term signing key
    /// is encrypted in the metadata table; each KeyPackage private bundle is persisted
    /// by OpenMLS through the authenticated EncryptedCodec.
    pub fn create_key_packages(
        &mut self,
        user_id: &str,
        device_id: &str,
        count: usize,
    ) -> EngineResult<serde_json::Value> {
        if user_id.is_empty() || device_id.is_empty()
            || user_id.len() > 128 || device_id.len() > 128
            || count == 0 || count > 20
        {
            return Err("invalid Atomos key package request".into());
        }

        let identity = format!("atomos-v1:{user_id}:{device_id}");
        let metadata_key = format!("device-signing-key:{device_id}");
        let ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

        let stored_signer: Option<Vec<u8>> = self.connection
            .query_row(
                "SELECT value FROM atomos_mls_metadata WHERE key = ?1",
                [&metadata_key],
                |row| row.get(0),
            )
            .optional()?;

        let (signature_keys, is_new) = match stored_signer {
            Some(encoded) => (EncryptedCodec::from_slice::<SignatureKeyPair>(&encoded)?, false),
            None => (SignatureKeyPair::new(ciphersuite.signature_algorithm())?, true),
        };

        let public_signature_key = signature_keys.to_public_vec();
        let mut packages = Vec::with_capacity(count);
        {
            let provider = AtomosProvider::new(&self.crypto, &mut self.connection);
            signature_keys.store(provider.storage())?;

            for _ in 0..count {
                let credential = BasicCredential::new(identity.as_bytes().to_vec());
                let credential_with_key = CredentialWithKey {
                    credential: credential.into(),
                    signature_key: signature_keys.public().into(),
                };
                let key_package = KeyPackage::builder().build(
                    ciphersuite,
                    &provider,
                    &signature_keys,
                    credential_with_key,
                )?;
                let serialized_package = key_package.key_package().tls_serialize_detached()?;
                packages.push(serde_json::json!({
                    "credential_hex": hex::encode(identity.as_bytes()),
                    "signature_public_key_hex": hex::encode(&public_signature_key),
                    "key_package_hex": hex::encode(serialized_package),
                    "cipher_suite": "MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519"
                }));
            }
        }

        if is_new {
            let encoded_signer = EncryptedCodec::to_vec(&signature_keys)?;
            self.connection.execute(
                "INSERT INTO atomos_mls_metadata (key, value) VALUES (?1, ?2)",
                (&metadata_key, &encoded_signer),
            )?;
        }

        Ok(serde_json::Value::Array(packages))
    }

    /// Establishes a direct MLS group from the current device and the recipient's
    /// authenticated KeyPackages. The local group state and Welcome retry payload are
    /// encrypted at rest before this method returns.
    pub fn create_direct_group(
        &mut self,
        conversation_id: &str,
        user_id: &str,
        device_id: &str,
        key_package_hex: &[String],
    ) -> EngineResult<serde_json::Value> {
        if conversation_id.is_empty() || user_id.is_empty() || device_id.is_empty()
            || key_package_hex.is_empty() || key_package_hex.len() > 64
        {
            return Err("invalid direct MLS group request".into());
        }

        let group_key = format!("conversation-group:{conversation_id}");
        let welcome_key = format!("conversation-welcome:{conversation_id}");
        let published_key = format!("conversation-welcome-published:{conversation_id}");

        if self.metadata_get::<Vec<u8>>(&group_key)?.is_some() {
            let published = self.metadata_get::<bool>(&published_key)?.unwrap_or(false);
            if published {
                return Ok(serde_json::json!({ "already_initialized": true }));
            }
            let welcome = self.metadata_get::<Vec<u8>>(&welcome_key)?
                .ok_or("group exists but its Welcome retry payload is missing")?;
            return Ok(serde_json::json!({
                "already_initialized": false,
                "welcome_hex": hex::encode(welcome)
            }));
        }

        let identity = format!("atomos-v1:{user_id}:{device_id}");
        let signature_keys = self.load_signing_key(device_id)?;
        let ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

        let (group_id_bytes, welcome_bytes) = {
            let provider = AtomosProvider::new(&self.crypto, &mut self.connection);
            signature_keys.store(provider.storage())?;

            let credential = BasicCredential::new(identity.as_bytes().to_vec());
            let credential_with_key = CredentialWithKey {
                credential: credential.into(),
                signature_key: signature_keys.public().into(),
            };

            let mut group = MlsGroup::builder()
                .ciphersuite(ciphersuite)
                .use_ratchet_tree_extension(true)
                .build(&provider, &signature_keys, credential_with_key)?;

            let mut key_packages = Vec::with_capacity(key_package_hex.len());
            for encoded in key_package_hex {
                let bytes = hex::decode(encoded)?;
                let key_package_in = KeyPackageIn::tls_deserialize_exact(bytes.as_slice())?;
                let key_package = key_package_in.validate(provider.crypto(), ProtocolVersion::Mls10)?;
                key_packages.push(key_package);
            }

            let (_commit, welcome, _group_info) =
                group.add_members(&provider, &signature_keys, &key_packages)?;
            group.merge_pending_commit(&provider)?;

            (
                group.group_id().tls_serialize_detached()?,
                welcome.to_bytes()?,
            )
        };

        self.metadata_put(&group_key, &group_id_bytes)?;
        self.metadata_put(&welcome_key, &welcome_bytes)?;
        self.metadata_put(&published_key, &false)?;

        Ok(serde_json::json!({
            "already_initialized": false,
            "welcome_hex": hex::encode(welcome_bytes)
        }))
    }

    pub fn has_group(&self, conversation_id: &str) -> EngineResult<bool> {
        Ok(self.metadata_get::<Vec<u8>>(&format!("conversation-group:{conversation_id}"))?.is_some())
    }

    pub fn mark_welcome_published(&mut self, conversation_id: &str) -> EngineResult<()> {
        let published_key = format!("conversation-welcome-published:{conversation_id}");
        let welcome_key = format!("conversation-welcome:{conversation_id}");
        self.metadata_put(&published_key, &true)?;
        self.metadata_delete(&welcome_key)?;
        Ok(())
    }

    pub fn create_application_message(
        &mut self,
        conversation_id: &str,
        device_id: &str,
        message_id: &str,
        plaintext: &[u8],
    ) -> EngineResult<Vec<u8>> {
        if plaintext.is_empty() || plaintext.len() > 64 * 1024
            || message_id.is_empty() || message_id.len() > 128
        {
            return Err("message size or identifier is outside the supported limit".into());
        }
        let group_key = format!("conversation-group:{conversation_id}");
        let group_id_bytes = self.metadata_get::<Vec<u8>>(&group_key)?
            .ok_or("secure conversation has not been joined on this device")?;
        let group_id = GroupId::tls_deserialize_exact(group_id_bytes.as_slice())?;
        let signature_keys = self.load_signing_key(device_id)?;

        let message_bytes = {
            let provider = AtomosProvider::new(&self.crypto, &mut self.connection);
            let mut group = MlsGroup::load(provider.storage(), &group_id)?
                .ok_or("MLS group state is missing from encrypted local storage")?;
            let message = group.create_message(&provider, &signature_keys, plaintext)?;
            message.to_bytes()?
        };

        self.metadata_put(
            &format!("local-message:{conversation_id}:{message_id}"),
            &plaintext.to_vec(),
        )?;
        self.metadata_put(
            &format!("pending-outgoing:{conversation_id}:{message_id}"),
            &message_bytes,
        )?;
        Ok(message_bytes)
    }

    pub fn cached_plaintext(
        &self,
        conversation_id: &str,
        message_id: &str,
    ) -> EngineResult<Option<Vec<u8>>> {
        self.metadata_get(&format!("local-message:{conversation_id}:{message_id}"))
    }

    pub fn mark_message_sent(
        &mut self,
        conversation_id: &str,
        message_id: &str,
    ) -> EngineResult<()> {
        self.metadata_delete(&format!("pending-outgoing:{conversation_id}:{message_id}"))
    }

    /// Returns locally persisted, not-yet-acknowledged ciphertext messages for retry.
    /// Message bodies are never decrypted or returned by this recovery path.
    pub fn pending_outgoing(&self, conversation_id: &str) -> EngineResult<serde_json::Value> {
        if conversation_id.is_empty() || conversation_id.len() > 128 {
            return Err("invalid conversation identifier".into());
        }

        let prefix = format!("pending-outgoing:{conversation_id}:");
        let keys: Vec<String> = {
            let mut statement = self.connection.prepare(
                "SELECT key FROM atomos_mls_metadata
                 WHERE substr(key, 1, length(?1)) = ?1
                 ORDER BY key"
            )?;
            let rows = statement.query_map([&prefix], |row| row.get::<_, String>(0))?;
            rows.collect::<Result<Vec<_>, _>>()?
        };

        let mut pending = Vec::with_capacity(keys.len());
        for key in keys {
            let Some(message_id) = key.strip_prefix(&prefix) else { continue };
            if message_id.is_empty() || message_id.len() > 128 {
                continue;
            }
            let ciphertext = self.metadata_get::<Vec<u8>>(&key)?
                .ok_or("pending MLS ciphertext disappeared from local storage")?;
            pending.push(serde_json::json!({
                "message_id": message_id,
                "ciphertext_hex": hex::encode(ciphertext)
            }));
        }

        Ok(serde_json::Value::Array(pending))
    }

    /// Processes an MLS Welcome, Commit, Proposal or Application message. Plaintext
    /// is cached in the encrypted local metadata store so history can be rendered
    /// again after restart without replaying an already-consumed MLS message.
    pub fn process_incoming_message(
        &mut self,
        conversation_id: &str,
        message_id: &str,
        message_bytes: &[u8],
    ) -> EngineResult<serde_json::Value> {
        if message_id.is_empty() || message_id.len() > 128
            || message_bytes.is_empty() || message_bytes.len() > 512 * 1024
        {
            return Err("invalid MLS message size or identifier".into());
        }

        let cache_key = format!("local-message:{conversation_id}:{message_id}");
        let processed_key = format!("processed-message:{conversation_id}:{message_id}");
        if let Some(plaintext) = self.metadata_get::<Vec<u8>>(&cache_key)? {
            return Ok(serde_json::json!({
                "kind": "application",
                "plaintext_base64": BASE64.encode(plaintext)
            }));
        }
        if self.metadata_get::<bool>(&processed_key)?.unwrap_or(false) {
            return Ok(serde_json::json!({ "kind": "duplicate" }));
        }

        let group_key = format!("conversation-group:{conversation_id}");
        let message_in = MlsMessageIn::tls_deserialize_exact(message_bytes)?;

        match message_in.extract() {
            MlsMessageBodyIn::Welcome(welcome) => {
                let published_key = format!("conversation-welcome-published:{conversation_id}");
                if self.metadata_get::<Vec<u8>>(&group_key)?.is_some()
                    && self.metadata_get::<bool>(&published_key)?.unwrap_or(false)
                {
                    self.metadata_put(&processed_key, &true)?;
                    return Ok(serde_json::json!({ "kind": "duplicate_welcome" }));
                }

                let staged_result = {
                    let provider = AtomosProvider::new(&self.crypto, &mut self.connection);
                    match StagedWelcome::new_from_welcome(
                        &provider,
                        &MlsGroupJoinConfig::default(),
                        welcome,
                        None,
                    ) {
                        Ok(staged) => {
                            let group = staged.into_group(&provider)?;
                            Some(group.group_id().tls_serialize_detached()?)
                        }
                        Err(WelcomeError::NoMatchingKeyPackage | WelcomeError::JoinerSecretNotFound) => None,
                        Err(WelcomeError::GroupAlreadyExists) => None,
                        Err(error) => return Err(Box::new(error)),
                    }
                };

                if let Some(group_id) = staged_result {
                    self.metadata_put(&group_key, &group_id)?;
                    self.metadata_put(&published_key, &true)?;
                    self.metadata_delete(&format!("conversation-welcome:{conversation_id}"))?;
                    self.metadata_put(&processed_key, &true)?;
                    Ok(serde_json::json!({ "kind": "welcome" }))
                } else {
                    self.metadata_put(&processed_key, &true)?;
                    Ok(serde_json::json!({ "kind": "not_for_device" }))
                }
            }
            _ => {
                let group_id_bytes = self.metadata_get::<Vec<u8>>(&group_key)?
                    .ok_or("received an MLS message before the group Welcome")?;
                let group_id = GroupId::tls_deserialize_exact(group_id_bytes.as_slice())?;
                let message_in = MlsMessageIn::tls_deserialize_exact(message_bytes)?;
                let protocol_message = message_in.try_into_protocol_message()?;

                let outcome = {
                    let provider = AtomosProvider::new(&self.crypto, &mut self.connection);
                    let mut group = MlsGroup::load(provider.storage(), &group_id)?
                        .ok_or("MLS group state is missing from encrypted local storage")?;
                    let processed = group.process_message(&provider, protocol_message)?;

                    match processed.into_content() {
                        ProcessedMessageContent::ApplicationMessage(application) => {
                            Some(("application", application.into_bytes()))
                        }
                        ProcessedMessageContent::StagedCommitMessage(staged) => {
                            group.merge_staged_commit(&provider, *staged)?;
                            None
                        }
                        ProcessedMessageContent::ProposalMessage(proposal) => {
                            group.store_pending_proposal(provider.storage(), *proposal)?;
                            None
                        }
                        _ => None,
                    }
                };

                self.metadata_put(&processed_key, &true)?;
                if let Some(("application", plaintext)) = outcome {
                    self.metadata_put(&cache_key, &plaintext)?;
                    Ok(serde_json::json!({
                        "kind": "application",
                        "plaintext_base64": BASE64.encode(plaintext)
                    }))
                } else {
                    Ok(serde_json::json!({ "kind": "control" }))
                }
            }
        }
    }

}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn encrypted_sqlite_provider_opens_and_runs_migrations() {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("clock")
            .as_nanos();
        let path = std::env::temp_dir().join(format!(
            "atomos-mls-storage-{}-{unique}.db",
            std::process::id()
        ));
        let path_string = path.to_string_lossy().into_owned();

        let _guard = crate::storage::TEST_LOCK.lock().expect("storage test lock");
        crate::storage::set_storage_key(&[0x24; 32]).expect("install test storage key");
        let mut engine = AtomosMlsEngine::open(&path_string)
            .expect("encrypted SQLite storage migrations should succeed");

        let sentinel = "private MLS state must never be stored as plaintext";
        engine.metadata_put("test:encrypted-state", &sentinel)
            .expect("write encrypted metadata");
        let restored: Option<String> = engine.metadata_get("test:encrypted-state")
            .expect("read encrypted metadata");
        assert_eq!(restored.as_deref(), Some(sentinel));

        let raw: Vec<u8> = engine.connection.query_row(
            "SELECT value FROM atomos_mls_metadata WHERE key = ?1",
            ["test:encrypted-state"],
            |row| row.get(0),
        ).expect("read raw metadata envelope");
        assert!(!raw.windows(sentinel.len()).any(|part| part == sentinel.as_bytes()));

        drop(engine);
        crate::storage::clear_storage_key();
        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(format!("{path_string}-wal"));
        let _ = std::fs::remove_file(format!("{path_string}-shm"));
    }
}
