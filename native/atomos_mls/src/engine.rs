//! Durable OpenMLS storage provider.
//!
//! Crypto and randomness are delegated to OpenMLS RustCrypto. Persistent entities
//! are serialized by OpenMLS SQLite storage and encrypted by EncryptedCodec before
//! they are written to SQLite. This module does not implement MLS protocol logic.

use crate::storage::EncryptedCodec;
use openmls::prelude::{Ciphersuite, CredentialWithKey, KeyPackage};
use openmls_basic_credential::{BasicCredential, SignatureKeyPair};
use openmls_rust_crypto::RustCrypto;
use openmls_sqlite_storage::{Codec, SqliteStorageProvider};
use openmls_traits::OpenMlsProvider;
use rusqlite::{Connection, OptionalExtension};
use std::error::Error;
use std::time::Duration;
use tls_codec::Serialize;

pub type EngineResult<T> = Result<T, Box<dyn Error + Send + Sync>>;

pub struct AtomosProvider<'a> {
    crypto: &'a RustCrypto,
    storage: SqliteStorageProvider<'a, EncryptedCodec>,
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
    type StorageProvider = SqliteStorageProvider<'a, EncryptedCodec>;

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
            let mut storage = SqliteStorageProvider::<EncryptedCodec>::new(&mut connection);
            storage.run_migrations()?;
        }

        Ok(Self {
            connection,
            crypto: RustCrypto::default(),
        })
    }

    /// Creates a device-bound MLS KeyPackage. The long-term signing key is
    /// serialized into the encrypted metadata table and also stored in OpenMLS.
    /// KeyPackage private material is stored by OpenMLS through EncryptedCodec.
    pub fn create_key_package(
        &mut self,
        user_id: &str,
        device_id: &str,
    ) -> EngineResult<serde_json::Value> {
        if user_id.is_empty() || device_id.is_empty()
            || user_id.len() > 128 || device_id.len() > 128
        {
            return Err("invalid Atomos identity".into());
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

        let (serialized_package, public_signature_key) = {
            let provider = AtomosProvider::new(&self.crypto, &mut self.connection);
            signature_keys.store(provider.storage())?;

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
            (
                key_package.key_package().tls_serialize_detached()?,
                signature_keys.to_public_vec(),
            )
        };

        if is_new {
            let encoded_signer = EncryptedCodec::to_vec(&signature_keys)?;
            self.connection.execute(
                "INSERT INTO atomos_mls_metadata (key, value) VALUES (?1, ?2)",
                (&metadata_key, &encoded_signer),
            )?;
        }

        Ok(serde_json::json!({
            "credential_hex": hex::encode(identity.as_bytes()),
            "signature_public_key_hex": hex::encode(public_signature_key),
            "key_package_hex": hex::encode(serialized_package),
            "cipher_suite": "MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519"
        }))
    }

    /// Runs an operation with the durable provider. The provider borrows the SQLite
    /// connection for the operation, preventing accidental concurrent use of it.
    pub fn with_provider<T>(
        &mut self,
        operation: impl FnOnce(&AtomosProvider<'_>) -> EngineResult<T>,
    ) -> EngineResult<T> {
        let provider = AtomosProvider::new(&self.crypto, &mut self.connection);
        operation(&provider)
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

        let mut engine = AtomosMlsEngine::open(&path_string)
            .expect("encrypted SQLite storage migrations should succeed");
        engine
            .with_provider(|_| Ok::<(), Box<dyn Error + Send + Sync>>(()))
            .expect("provider should be available");

        drop(engine);
        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(format!("{path_string}-wal"));
        let _ = std::fs::remove_file(format!("{path_string}-shm"));
    }
}
