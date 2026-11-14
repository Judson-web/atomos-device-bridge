package dev.atomos.bridge.messaging

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.eq
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.selectAsFlow
import kotlinx.coroutines.flow.Flow
import io.github.jan.supabase.storage.Storage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class MessengerProfile(
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("display_name") val displayName: String
)

@Serializable
data class MessengerContactRequest(
    val id: String,
    @SerialName("requester_id") val requesterId: String,
    @SerialName("target_id") val targetId: String,
    val status: String,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class MessengerConversationRow(
    val id: String,
    val kind: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class MessengerConversationSummary(
    val id: String,
    val kind: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("participant_names") val participantNames: String,
    @SerialName("unread_count") val unreadCount: Int
)

@Serializable
data class MessengerCiphertextRow(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_user_id") val senderUserId: String,
    @SerialName("sender_device_id") val senderDeviceId: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    val ciphertext: String,
    @SerialName("created_at") val createdAt: String
)

@Serializable
private data class DeviceUpsert(
    val id: String,
    @SerialName("user_id") val userId: String,
    val label: String,
    val credential: String,
    @SerialName("signature_public_key") val signaturePublicKey: String,
    @SerialName("last_seen_at") val lastSeenAt: String
)

@Serializable
private data class DeviceIdRow(
    val id: String,
    @SerialName("revoked_at") val revokedAt: String? = null
)

@Serializable
private data class KeyPackageIdRow(val id: String)

@Serializable
private data class KeyPackageInsert(
    @SerialName("device_id") val deviceId: String,
    @SerialName("package") val packageBytes: String,
    @SerialName("cipher_suite") val cipherSuite: String,
    @SerialName("expires_at") val expiresAt: String
)

@Serializable
private data class ContactRequestInsert(
    @SerialName("requester_id") val requesterId: String,
    @SerialName("target_id") val targetId: String
)

/**
 * Supabase client for the isolated messenger schema.
 *
 * Authentication tokens are imported from Atomos SecureStore after the existing
 * Discord PKCE flow completes. No service-role credential is present in the app.
 */
class SupabaseMessengerApi {
    private val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = dev.atomos.bridge.BuildConfig.SUPABASE_URL,
        supabaseKey = dev.atomos.bridge.BuildConfig.SUPABASE_PUBLISHABLE_KEY
    ) {
        install(Auth) {
            alwaysAutoRefresh = false
            autoLoadFromStorage = false
            autoSaveToStorage = false
        }
        install(Postgrest) {
            defaultSchema = "messenger"
        }
        install(Realtime)
        install(Storage)
    }

    suspend fun importSession(accessToken: String, refreshToken: String) {
        require(accessToken.isNotBlank())
        client.auth.importAuthToken(
            accessToken = accessToken,
            refreshToken = refreshToken,
            retrieveUser = false,
            autoRefresh = false
        )
    }

    suspend fun signOut() {
        runCatching { client.auth.signOut() }
        client.realtime.disconnect()
    }

    suspend fun searchProfile(username: String): MessengerProfile? {
        val normalized = username.trim().removePrefix("@").lowercase()
        require(normalized.matches(Regex("[a-z0-9_]{3,32}"))) {
            "Enter a valid username (3–32 letters, numbers or underscores)."
        }
        return client.from("profiles")
            .select {
                filter { eq("username", normalized) }
            }
            .decodeList<MessengerProfile>()
            .firstOrNull()
    }

    suspend fun profileById(userId: String): MessengerProfile? {
        return client.from("profiles")
            .select {
                filter { eq("user_id", userId) }
            }
            .decodeList<MessengerProfile>()
            .firstOrNull()
    }

    suspend fun acceptedContactRequests(userId: String): List<MessengerContactRequest> {
        val sent = client.from("contact_requests")
            .select {
                filter {
                    eq("requester_id", userId)
                    eq("status", "accepted")
                }
            }
            .decodeList<MessengerContactRequest>()
        val received = client.from("contact_requests")
            .select {
                filter {
                    eq("target_id", userId)
                    eq("status", "accepted")
                }
            }
            .decodeList<MessengerContactRequest>()
        return (sent + received).distinctBy { it.id }
    }

    suspend fun deviceExists(deviceId: String): Boolean {
        return client.from("devices")
            .select {
                filter { eq("id", deviceId) }
                limit(1L)
            }
            .decodeList<DeviceIdRow>()
            .isNotEmpty()
    }

    suspend fun deviceIsRevoked(deviceId: String): Boolean {
        return client.from("devices")
            .select {
                filter { eq("id", deviceId) }
                limit(1L)
            }
            .decodeList<DeviceIdRow>()
            .firstOrNull()
            ?.revokedAt != null
    }

    suspend fun hasActiveKeyPackage(deviceId: String): Boolean {
        val now = java.time.Instant.now().toString()
        return client.from("key_packages")
            .select {
                filter {
                    eq("device_id", deviceId)
                    io.github.jan.supabase.postgrest.query.filter.isNull("consumed_at")
                    io.github.jan.supabase.postgrest.query.filter.gt("expires_at", now)
                }
                limit(1)
            }
            .decodeList<KeyPackageIdRow>()
            .isNotEmpty()
    }

    suspend fun registerDevice(
        userId: String,
        deviceId: String,
        label: String,
        bundle: DeviceEnrollmentBundle
    ) {
        client.from("devices").upsert(
            DeviceUpsert(
                id = deviceId,
                userId = userId,
                label = label.take(80),
                credential = bytea(bundle.credentialHex),
                signaturePublicKey = bytea(bundle.signaturePublicKeyHex),
                lastSeenAt = java.time.Instant.now().toString()
            ),
            onConflict = "id"
        )
        publishKeyPackage(deviceId, bundle)
    }

    suspend fun publishKeyPackage(deviceId: String, bundle: DeviceEnrollmentBundle) {
        client.from("key_packages").delete {
            filter {
                eq("device_id", deviceId)
                io.github.jan.supabase.postgrest.query.filter.isNull("consumed_at")
            }
        }
        client.from("key_packages").insert(
            KeyPackageInsert(
                deviceId = deviceId,
                packageBytes = bytea(bundle.keyPackageHex),
                cipherSuite = bundle.cipherSuite,
                expiresAt = java.time.Instant.now().plusSeconds(7 * 24 * 60 * 60).toString()
            )
        )
    }

    private fun bytea(hex: String): String {
        require(hex.isNotEmpty() && hex.length % 2 == 0 && hex.all { it in "0123456789abcdefABCDEF" })
        return String(charArrayOf(92.toChar(), 'x')) + hex
    }

    suspend fun sendContactRequest(requesterId: String, targetId: String) {
        require(requesterId.isNotBlank() && targetId.isNotBlank() && requesterId != targetId)
        client.from("contact_requests").insert(
            ContactRequestInsert(requesterId = requesterId, targetId = targetId)
        )
    }

    suspend fun incomingContactRequests(userId: String): List<MessengerContactRequest> {
        return client.from("contact_requests")
            .select {
                filter {
                    eq("target_id", userId)
                    eq("status", "pending")
                }
            }
            .decodeList()
    }

    suspend fun respondToContactRequest(requestId: String, accept: Boolean) {
        client.postgrest.rpc(
            function = "respond_contact_request",
            parameters = buildJsonObject {
                put("p_request_id", requestId)
                put("p_status", if (accept) "accepted" else "declined")
            }
        )
    }

    suspend fun createDirectConversation(otherUserId: String): String {
        val response = client.postgrest.rpc(
            function = "create_conversation",
            parameters = buildJsonObject {
                put("p_kind", "direct")
                put("p_member_ids", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(otherUserId)) })
            }
        )
        return response.decodeSingle<String>()
    }

    fun observeConversationRows(): Flow<List<MessengerConversationRow>> {
        return client.from("conversations").selectAsFlow(MessengerConversationRow::id)
    }

    fun observeContactRequests(): Flow<List<MessengerContactRequest>> {
        return client.from("contact_requests").selectAsFlow(MessengerContactRequest::id)
    }

    suspend fun conversationSummaries(): List<MessengerConversationSummary> {
        return client.from("conversation_summaries")
            .select {
                order("updated_at", Order.DESCENDING)
            }
            .decodeList()
    }

    suspend fun encryptedMessages(conversationId: String, limit: Int = 100): List<MessengerCiphertextRow> {
        require(limit in 1..200)
        return client.from("messages")
            .select {
                filter { eq("conversation_id", conversationId) }
                order("created_at", Order.ASCENDING)
                limit(limit.toLong())
            }
            .decodeList()
    }
}
