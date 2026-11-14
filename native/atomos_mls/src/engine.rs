//! Durable OpenMLS storage provider.
//!
//! Crypto and randomness are delegated to OpenMLS RustCrypto. Persistent entities
//! are serialized by OpenMLS SQLite storage and encrypted by EncryptedCodec before
//! they are written to SQLite. This module does not implement MLS protocol logic.

use crate::storage::EncryptedCodec;
use openmls_rust_crypto::RustCrypto;
use openmls_sqlite_storage::{Connection, SqliteStorageProvider};
use openmls_traits::OpenMlsProvider;
use std::error::Error;
use std::time::Duration;

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
             PRAGMA foreign_keys=ON;",
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
    use crate::storage::{clear_storage_key, set_storage_key};
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn encrypted_sqlite_provider_opens_and_runs_migrations() {
        set_storage_key(&[0x29; 32]).expect("set test storage key");
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
        clear_storage_key();
    }
}
