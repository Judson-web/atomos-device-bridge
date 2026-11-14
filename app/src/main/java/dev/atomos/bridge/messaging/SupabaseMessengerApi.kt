package dev.atomos.bridge.messaging

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.eq
import io.github.jan.supabase.realtime.Realtime
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
