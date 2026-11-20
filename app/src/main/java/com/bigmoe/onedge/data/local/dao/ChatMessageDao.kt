package com.bigmoe.onedge.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bigmoe.onedge.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

/** Per chat: a cheap fingerprint of its messages (the chat sync uses it to spot local changes). */
data class MessageFingerprint(
    @androidx.room.ColumnInfo(name = "session_id") val sessionId: String,
    val cnt: Int,
    val len: Long,
    val maxTs: Long,
    val varLen: Long
)

@Dao
interface ChatMessageDao {

    @Query("SELECT session_id, COUNT(*) AS cnt, SUM(LENGTH(content)) AS len, MAX(timestamp) AS maxTs, SUM(LENGTH(IFNULL(variants_json, ''))) AS varLen FROM chat_messages GROUP BY session_id")
    suspend fun fingerprints(): List<MessageFingerprint>

    /** Insert or update in place (no delete, so nothing hanging off a message is cascaded away). */
    @androidx.room.Upsert
    suspend fun upsertMessages(messages: List<ChatMessageEntity>)


    // rowid breaks ties between messages saved within the same millisecond, keeping insertion order.
    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp ASC, rowid ASC")
    fun observeMessagesForSession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp ASC, rowid ASC")
    suspend fun getMessagesForSession(sessionId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    @Delete
    suspend fun deleteMessage(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE session_id = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    /** Removes the given messages of one chat (callers pass chunks well below SQLite's 999-variable limit). */
    @Query("DELETE FROM chat_messages WHERE session_id = :sessionId AND id IN (:ids)")
    suspend fun deleteMessagesByIds(sessionId: String, ids: List<String>): Int

    @Query("SELECT COUNT(*) FROM chat_messages WHERE session_id = :sessionId")
    suspend fun countForSession(sessionId: String): Int

    @Query("UPDATE chat_messages SET variants_json = :json WHERE id = :messageId")
    suspend fun updateVariants(messageId: String, json: String?): Int

    @Query("UPDATE chat_messages SET content = :content WHERE id = :messageId AND role = 'USER'")
    suspend fun updateUserMessageContent(messageId: String, content: String): Int

    @Query("UPDATE chat_messages SET generation_elapsed_ms = :elapsedMs, context_used = :contextUsed, context_total = :contextTotal, generated_tokens = :generatedTokens WHERE id = :messageId")
    suspend fun updateGenerationTelemetry(
        messageId: String,
        elapsedMs: Long,
        contextUsed: Int,
        contextTotal: Int,
        generatedTokens: Int
    )
}
