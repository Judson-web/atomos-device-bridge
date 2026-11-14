//! Atomos native MLS bridge.
//!
//! OpenMLS owns the MLS protocol and cryptographic operations. This JNI layer
//! exposes runtime and key-lifecycle plumbing only; it is not a custom protocol.
//! The next stage adds a durable OpenMLS group/session engine over the encrypted
//! SQLite storage provider before enabling message composition.

pub mod storage;

use jni::objects::{JByteArray, JClass};
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use openmls_rust_crypto::OpenMlsRustCrypto;
use zeroize::Zeroize;

const OPENMLS_VERSION: &str = "0.9.2";
const MLS_PROTOCOL: &str = "RFC 9420";

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

#[no_mangle]
pub extern "system" fn Java_dev_atomos_bridge_messaging_NativeMlsBridge_nativeInitializeStorageKey(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    key: JByteArray<'_>,
) -> jboolean {
    let mut bytes = match env.convert_byte_array(key) {
        Ok(value) => value,
        Err(_) => return JNI_FALSE,
    };
    let result = storage::set_storage_key(&bytes).is_ok();
    bytes.zeroize();
    if result { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_dev_atomos_bridge_messaging_NativeMlsBridge_nativeClearStorageKey(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    storage::clear_storage_key();
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
