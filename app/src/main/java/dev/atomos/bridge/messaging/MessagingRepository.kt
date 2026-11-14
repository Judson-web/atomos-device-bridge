package dev.atomos.bridge.messaging

import kotlinx.coroutines.flow.Flow

/**
 * Transport/persistence boundary. Implementations accept and return ciphertext envelopes
 * only. Realtime is a transport, never an authorization boundary; backend membership
 * checks and row-level security remain mandatory.
 */
interface MessagingRepository {
    fun observeConversations(): Flow<List<ConversationSummary>>
    fun observeMessages(conversationId: ConversationId): Flow<List<EncryptedMessage>>

    suspend fun sendMessage(
        conversationId: ConversationId,
        envelope: CiphertextEnvelope
    ): MessageId

    suspend fun markRead(
        conversationId: ConversationId,
        throughMessageId: MessageId
    )
}
