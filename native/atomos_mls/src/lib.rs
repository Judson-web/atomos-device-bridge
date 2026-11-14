//! Atomos native MLS bridge.
//!
//! OpenMLS owns the MLS protocol and cryptographic operations. This JNI layer
//! exposes runtime and key-lifecycle plumbing only; it is not a custom protocol.
//! The next stage adds a durable OpenMLS group/session engine over the encrypted
//! SQLite storage provider before enabling message composition.

pub mod engine;
pub mod storage;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use openmls_rust_crypto::OpenMlsRustCrypto;
use std::sync::{Mutex, OnceLock};
use zeroize::Zeroize;

static ENGINE: OnceLock<Mutex<Option<engine::AtomosMlsEngine>>> = OnceLock::new();

fn engine_slot() -> &'static Mutex<Option<engine::AtomosMlsEngine>> {
    ENGINE.get_or_init(|| Mutex::new(None))
}

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
pub extern "system" fn Java_dev_atomos_bridge_messaging_NativeMlsBridge_nativeOpenDatabase(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
) -> jboolean {
    let path: String = match env.get_string(&path) {
        Ok(value) => value.into(),
        Err(_) => return JNI_FALSE,
    };
    let engine = match engine::AtomosMlsEngine::open(&path) {
        Ok(value) => value,
        Err(_) => return JNI_FALSE,
    };
    let mut slot = engine_slot().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    *slot = Some(engine);
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_dev_atomos_bridge_messaging_NativeMlsBridge_nativeCreateKeyPackages(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    user_id: JString<'_>,
    device_id: JString<'_>,
    count: jint,
) -> jstring {
    let user_id: String = match env.get_string(&user_id) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let device_id: String = match env.get_string(&device_id) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let mut slot = engine_slot().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let Some(engine) = slot.as_mut() else {
        return std::ptr::null_mut();
    };
    if count <= 0 || count > 20 {
        return std::ptr::null_mut();
    }
    let enrollment = match engine.create_key_packages(&user_id, &device_id, count as usize) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let json = match serde_json::to_string(&enrollment) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    match env.new_string(json) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_atomos_bridge_messaging_NativeMlsBridge_nativeCreateDirectGroup(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    conversation_id: JString<'_>,
    user_id: JString<'_>,
    device_id: JString<'_>,
    key_packages_json: JString<'_>,
) -> jstring {
    let conversation_id: String = match env.get_string(&conversation_id) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let user_id: String = match env.get_string(&user_id) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let device_id: String = match env.get_string(&device_id) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let key_packages_json: String = match env.get_string(&key_packages_json) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let key_packages: Vec<String> = match serde_json::from_str(&key_packages_json) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };

    let mut slot = engine_slot().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let Some(engine) = slot.as_mut() else {
        return std::ptr::null_mut();
    };
    let result = match engine.create_direct_group(
        &conversation_id,
        &user_id,
        &device_id,
        &key_packages,
    ) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    match env.new_string(result.to_string()) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_atomos_bridge_messaging_NativeMlsBridge_nativeMarkWelcomePublished(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    conversation_id: JString<'_>,
) -> jboolean {
    let conversation_id: String = match env.get_string(&conversation_id) {
        Ok(value) => value.into(),
        Err(_) => return JNI_FALSE,
    };
    let mut slot = engine_slot().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let Some(engine) = slot.as_mut() else {
        return JNI_FALSE;
    };
    if engine.mark_welcome_published(&conversation_id).is_ok() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_atomos_bridge_messaging_NativeMlsBridge_nativeProcessIncomingMessage(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    conversation_id: JString<'_>,
    message_id: JString<'_>,
    message_hex: JString<'_>,
) -> jstring {
    let conversation_id: String = match env.get_string(&conversation_id) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let message_id: String = match env.get_string(&message_id) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let message_hex: String = match env.get_string(&message_hex) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let message_bytes = match hex::decode(message_hex) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };

    let mut slot = engine_slot().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let Some(engine) = slot.as_mut() else {
        return std::ptr::null_mut();
    };
    let result = match engine.process_incoming_message(&conversation_id, &message_id, &message_bytes) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    match env.new_string(result.to_string()) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_atomos_bridge_messaging_NativeMlsBridge_nativeCreateApplicationMessage(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    conversation_id: JString<'_>,
    device_id: JString<'_>,
    message_id: JString<'_>,
    plaintext: JByteArray<'_>,
) -> jstring {
    let conversation_id: String = match env.get_string(&conversation_id) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let device_id: String = match env.get_string(&device_id) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let message_id: String = match env.get_string(&message_id) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let mut plaintext_bytes = match env.convert_byte_array(plaintext) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };

    let mut slot = engine_slot().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let Some(engine) = slot.as_mut() else {
        plaintext_bytes.zeroize();
        return std::ptr::null_mut();
    };
    let result = engine.create_application_message(
        &conversation_id,
        &device_id,
        &message_id,
        &plaintext_bytes,
    );
    plaintext_bytes.zeroize();
    let message = match result {
        Ok(value) => hex::encode(value),
        Err(_) => return std::ptr::null_mut(),
    };
    match env.new_string(message) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_atomos_bridge_messaging_NativeMlsBridge_nativeCachedPlaintext(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    conversation_id: JString<'_>,
    message_id: JString<'_>,
) -> jbyteArray {
    let conversation_id: String = match env.get_string(&conversation_id) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let message_id: String = match env.get_string(&message_id) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let slot = engine_slot().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let Some(engine) = slot.as_ref() else {
        return std::ptr::null_mut();
    };
    match engine.cached_plaintext(&conversation_id, &message_id) {
        Ok(Some(bytes)) => match env.byte_array_from_slice(&bytes) {
            Ok(array) => array.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_atomos_bridge_messaging_NativeMlsBridge_nativeMarkMessageSent(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    conversation_id: JString<'_>,
    message_id: JString<'_>,
) -> jboolean {
    let conversation_id: String = match env.get_string(&conversation_id) {
        Ok(value) => value.into(),
        Err(_) => return JNI_FALSE,
    };
    let message_id: String = match env.get_string(&message_id) {
        Ok(value) => value.into(),
        Err(_) => return JNI_FALSE,
    };
    let mut slot = engine_slot().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let Some(engine) = slot.as_mut() else {
        return JNI_FALSE;
    };
    if engine.mark_message_sent(&conversation_id, &message_id).is_ok() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_atomos_bridge_messaging_NativeMlsBridge_nativeCloseDatabase(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    let mut slot = engine_slot().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    *slot = None;
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
