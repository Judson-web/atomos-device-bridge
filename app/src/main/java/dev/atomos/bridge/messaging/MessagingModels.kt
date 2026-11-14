package dev.atomos.bridge.messaging

import java.time.Instant

@JvmInline
value class ConversationId(val value: String) {
    init { require(value.isNotBlank() && value.length <= 128) }
}
@JvmInline
value class MessageId(val value: String) {
    init { require(value.isNotBlank() && value.length <= 128) }
}
@JvmInline
value class DeviceId(val value: String) {
    init { require(value.isNotBlank() && value.length <= 128) }
}

enum class ConversationType { DIRECT, GROUP }
enum class MessageKind { TEXT, IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, PACKAGE }
enum class DeliveryState { QUEUED, SENT, DELIVERED, READ, FAILED }

/** Opaque protocol output. The byte array is defensively copied on input and output. */
class CiphertextEnvelope(
    val protocolSuite: String,
    val protocolVersion: Int,
    val senderDeviceId: DeviceId,
    ciphertext: ByteArray,
    val associatedDataVersion: Int
) {
    private val payload = ciphertext.copyOf()
    val ciphertext: ByteArray get() = payload.copyOf()

    init {
        require(protocolSuite.isNotBlank() && protocolSuite.length <= 96)
        require(protocolVersion > 0)
        require(associatedDataVersion > 0)
        require(payload.isNotEmpty() && payload.size <= MAX_MESSAGE_CIPHERTEXT_BYTES)
    }

    companion object {
        const val MAX_MESSAGE_CIPHERTEXT_BYTES = 256 * 1024
    }
}

/**
 * Server-visible attachment metadata is intentionally opaque. MIME type, filename,
 * captions and content keys belong inside the encrypted message payload.
 */
data class EncryptedAttachmentDescriptor(
    val objectId: String,
    val ciphertextSizeBytes: Long,
    val chunkSizeBytes: Int,
    val chunkCount: Int
) {
    init {
        require(objectId.isNotBlank() && objectId.length <= 160)
        require(ciphertextSizeBytes > 0 && chunkSizeBytes > 0 && chunkCount > 0)
    }
}

data class EncryptedMessage(
    val id: MessageId,
    val conversationId: ConversationId,
    val senderDeviceId: DeviceId,
    val envelope: CiphertextEnvelope,
    val serverReceivedAt: Instant?,
    val deliveryState: DeliveryState
)

data class ConversationSummary(
    val id: ConversationId,
    val type: ConversationType,
    val displayTitle: String,
    val lastActivityAt: Instant?,
    val unreadCount: Int
) {
    init {
        require(displayTitle.length <= 160)
        require(unreadCount >= 0)
    }
}

data class LocalDeviceIdentity(
    val deviceId: DeviceId,
    val publicIdentityKey: ByteArray,
    val createdAt: Instant
) {
    init { require(publicIdentityKey.isNotEmpty()) }
}

/**
 * Implementations must delegate cryptographic operations to a vetted protocol library.
 * Do not implement a home-grown key exchange, ratchet, cipher or nonce scheme here.
 */
interface MessageCryptoEngine {
    suspend fun encrypt(
        conversationId: ConversationId,
        plaintext: ByteArray,
        associatedData: ByteArray
    ): CiphertextEnvelope

    suspend fun decrypt(
        conversationId: ConversationId,
        envelope: CiphertextEnvelope,
        associatedData: ByteArray
    ): ByteArray
}

/** Owns device key generation and protected local key material. */
interface DeviceIdentityStore {
    suspend fun getOrCreateIdentity(): LocalDeviceIdentity
    suspend fun clearIdentityAfterExplicitAccountRemoval()
}
