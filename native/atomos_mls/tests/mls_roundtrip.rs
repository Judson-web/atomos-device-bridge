use openmls::prelude::*;
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::OpenMlsRustCrypto;

/// Exercises a real two-client MLS group and application-message round trip.
/// This is a protocol-library test, not a substitute for Android ABI, storage,
/// authentication-service, delivery-service or independent security testing.
#[test]
fn two_clients_join_and_exchange_only_mls_application_data() {
    let alice_provider = OpenMlsRustCrypto::default();
    let bob_provider = OpenMlsRustCrypto::default();

    let ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

    let alice_credential = BasicCredential::new(b"atomos-test-alice".to_vec());
    let alice_signature_keys = SignatureKeyPair::new(ciphersuite.signature_algorithm())
        .expect("Alice signature key generation should succeed");
    alice_signature_keys
        .store(alice_provider.storage())
        .expect("Alice signing key should be stored in the test provider");
    let alice_credential_with_key = CredentialWithKey {
        credential: alice_credential.into(),
        signature_key: alice_signature_keys.public().into(),
    };

    let bob_credential = BasicCredential::new(b"atomos-test-bob".to_vec());
    let bob_signature_keys = SignatureKeyPair::new(ciphersuite.signature_algorithm())
        .expect("Bob signature key generation should succeed");
    bob_signature_keys
        .store(bob_provider.storage())
        .expect("Bob signing key should be stored in the test provider");
    let bob_credential_with_key = CredentialWithKey {
        credential: bob_credential.into(),
        signature_key: bob_signature_keys.public().into(),
    };

    let bob_key_package = KeyPackage::builder()
        .build(
            ciphersuite,
            &bob_provider,
            &bob_signature_keys,
            bob_credential_with_key,
        )
        .expect("Bob key package should be generated");

    let mut alice_group = MlsGroup::builder()
        .ciphersuite(ciphersuite)
        .use_ratchet_tree_extension(true)
        .build(
            &alice_provider,
            &alice_signature_keys,
            alice_credential_with_key,
        )
        .expect("Alice MLS group should be created");

    let (_commit, welcome, _group_info) = alice_group
        .add_members(
            &alice_provider,
            &alice_signature_keys,
            std::slice::from_ref(bob_key_package.key_package()),
        )
        .expect("Alice should be able to add Bob");
    alice_group
        .merge_pending_commit(&alice_provider)
        .expect("Alice should merge the add-member commit");

    let staged_welcome = StagedWelcome::new_from_welcome(
        &bob_provider,
        &MlsGroupJoinConfig::default(),
        welcome,
        None,
    )
    .expect("Bob should validate the Welcome message");
    let mut bob_group = staged_welcome
        .into_group(&bob_provider)
        .expect("Bob should join the MLS group");

    assert_eq!(alice_group.members().count(), 2);
    assert_eq!(bob_group.members().count(), 2);

    let plaintext = b"atomos mls integration test payload";
    let encrypted = alice_group
        .create_message(&alice_provider, &alice_signature_keys, plaintext)
        .expect("Alice should create an MLS application message");
    let protocol_message = encrypted
        .into_protocol_message()
        .expect("Application message should be a protocol message");
    let processed = bob_group
        .process_message(&bob_provider, protocol_message)
        .expect("Bob should authenticate and process Alice's message");

    match processed.into_content() {
        ProcessedMessageContent::ApplicationMessage(application_message) => {
            assert_eq!(application_message.into_bytes(), plaintext);
        }
        _ => panic!("Expected authenticated MLS application data"),
    }
}
