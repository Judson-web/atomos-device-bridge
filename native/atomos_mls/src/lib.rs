//! Atomos native MLS bridge bootstrap.
//!
//! This crate intentionally exposes only a runtime/build handshake at this stage.
//! It does not create identities, key packages, groups, encrypt or decrypt messages.
//! Do not implement custom cryptographic primitives or protocol state here.
//! The next implementation phase must use OpenMLS APIs and persist group operations
//! transactionally before any messaging feature is enabled.

use jni::objects::JClass;
use jni::sys::jstring;
use jni::JNIEnv;
use openmls_rust_crypto::OpenMlsRustCrypto;

const OPENMLS_VERSION: &str = "0.9.2";
const MLS_PROTOCOL: &str = "RFC 9420";

/// Compile-time type reference ensures the selected OpenMLS RustCrypto provider
/// is present in the dependency graph without performing cryptographic operations.
pub fn crypto_provider_type_name() -> &'static str {
    std::any::type_name::<OpenMlsRustCrypto>()
}

fn runtime_version() -> String {
    format!("OpenMLS {OPENMLS_VERSION} · MLS {MLS_PROTOCOL}")
}

#[no_mangle]
pub extern "system" fn Java_dev_atomos_bridge_messaging_NativeMlsBridge_nativeProtocolVersion(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    match env.new_string(runtime_version()) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reports_pinned_protocol_runtime() {
        assert_eq!(runtime_version(), "OpenMLS 0.9.2 · MLS RFC 9420");
        assert!(crypto_provider_type_name().contains("OpenMlsRustCrypto"));
    }
}
