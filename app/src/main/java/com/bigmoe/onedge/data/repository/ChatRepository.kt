package com.bigmoe.onedge.data.repository

import com.bigmoe.onedge.core.MessageStore
import com.bigmoe.onedge.core.NewMessage
import com.bigmoe.onedge.data.local.dao.AttachmentDao
import com.bigmoe.onedge.data.local.dao.ChatMessageDao
import com.bigmoe.onedge.data.local.dao.ChatSessionDao
import com.bigmoe.onedge.data.local.entity.AttachmentEntity
import com.bigmoe.onedge.data.local.entity.AttachmentKind
import com.bigmoe.onedge.data.local.entity.ChatMessageEntity
import com.bigmoe.onedge.data.local.entity.ChatSessionEntity
import com.bigmoe.onedge.data.local.entity.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

data class ChatSessionDomainModel(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelPath: String? = null,
    val modelNames: List<String> = emptyList(),
    val generationState: String = "IDLE",
    val generationStartedAt: Long? = null,
    val generationElapsedMs: Long = 0L,
    val generationTokens: Int = 0,
    val contextUsed: Int = 0,
    val contextTotal: Int = 0,
    val generationNote: String? = null
)

data class ChatMessageDomainModel(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val reasoning: String? = null,
    val tokensPerSecond: Double? = null,
    val prefillMs: Long? = null,
    val generatedTokens: Int? = null,
    val cacheHitPct: Double? = null,
    val generationElapsedMs: Long? = null,
    val contextUsed: Int? = null,
    val contextTotal: Int? = null,
    val note: String? = null,
    val prefillTokens: Int? = null,
    val thinkingMs: Long? = null,
    val toolMs: Long? = null,
    val runInfo: String? = null,
    val variantsJson: String? = null
)

data class AttachmentDomainModel(
    val id: String,
    val sessionId: String,
    val messageId: String,
    val kind: AttachmentKind,
    val name: String,
    val mime: String,
    val path: String,
    val sizeBytes: Long
)

class ChatRepository(
    private val sessionDao: ChatSessionDao,
    private val messageDao: ChatMessageDao,
    private val attachmentDao: AttachmentDao? = null
) : MessageStore {

    fun observeAttachments(sessionId: String): Flow<List<AttachmentDomainModel>> =
        (attachmentDao?.observeForSession(sessionId) ?: kotlinx.coroutines.flow.flowOf(emptyList()))
            .map { list -> list.map { it.toDomain() } }

    suspend fun addAttachment(
        sessionId: String, messageId: String, kind: AttachmentKind, name: String, mime: String, path: String, sizeBytes: Long
    ) {
        attachmentDao?.insert(
            AttachmentEntity(
                sessionId = sessionId, messageId = messageId, kind = kind.name, name = name, mime = mime,
                path = path, sizeBytes = sizeBytes
            )
        )
    }

    /** The attachments the person added to one message (used to rebuild its context when that message is edited). */
    suspend fun getAttachmentsForMessage(sessionId: String, messageId: String): List<AttachmentDomainModel> =
        attachmentDao?.getForMessage(sessionId, messageId)?.map { it.toDomain() } ?: emptyList()

    private fun AttachmentEntity.toDomain() = AttachmentDomainModel(
        id, sessionId, messageId,
        AttachmentKind.values().firstOrNull { it.name == kind } ?: AttachmentKind.FILE,
        name, mime, path, sizeBytes
    )


    fun observeAllSessions(): Flow<List<ChatSessionDomainModel>> =
        sessionDao.observeAllSessions().map { list -> list.map { it.toDomain() } }

    fun observeSessionById(sessionId: String): Flow<ChatSessionDomainModel?> =
        sessionDao.observeSessionById(sessionId).map { it?.toDomain() }

    fun observeMessagesForSession(sessionId: String): Flow<List<ChatMessageDomainModel>> =
        messageDao.observeMessagesForSession(sessionId).map { list -> list.map { it.toDomain() } }

    suspend fun getMessages(sessionId: String): List<ChatMessageDomainModel> =
        messageDao.getMessagesForSession(sessionId).map { it.toDomain() }

    suspend fun getSession(sessionId: String): ChatSessionDomainModel? =
        sessionDao.getSessionById(sessionId)?.toDomain()

    suspend fun createNewSession(title: String = DEFAULT_TITLE, modelPath: String? = null): ChatSessionDomainModel {
        val now = System.currentTimeMillis()
        val entity = ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now,
            modelPath = modelPath
        )
        sessionDao.insertSession(entity)
        return entity.toDomain()
    }

    suspend fun updateSessionTitle(sessionId: String, title: String) {
        sessionDao.updateSessionTitle(sessionId, title)
    }

    /** Persists one message, bumps the session's updatedAt, returns the new message id. */
    override suspend fun addMessage(sessionId: String, message: NewMessage): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        messageDao.insertMessage(
            ChatMessageEntity(
                id = id,
                sessionId = sessionId,
                role = message.role,
                content = message.content,
                timestamp = now,
                reasoning = message.reasoning,
                tokensPerSecond = message.stats?.tokensPerSecond?.takeIf { it > 0.0 },
                prefillMs = message.stats?.let { (it.prefillSeconds * 1000.0).toLong() },
                generatedTokens = message.stats?.nGenerated,
                cacheHitPct = message.stats?.cacheHitPct?.takeIf { it >= 0.0 },
                note = message.note,
                prefillTokens = message.prefillTokens,
                thinkingMs = message.thinkingMs,
                toolMs = message.toolMs,
                runInfo = message.runInfo
            )
        )
        sessionDao.touchSession(sessionId, now)
        return id
    }

    suspend fun updateUserMessageContent(sessionId: String, messageId: String, content: String): Boolean {
        val updated = messageDao.updateUserMessageContent(messageId, content)
        if (updated > 0) {
            sessionDao.touchSession(sessionId, System.currentTimeMillis())
            return true
        }
        return false
    }

    /**
     * Edit & regenerate, persistence half: removes the messages that depended on the old text ([removeIds]: the
     * assistant answers, tool results and hidden file context after it) together with the attachment rows that
     * belonged to them, then replaces the text of the edited user message in place. The message keeps its id and
     * its position, so the new answer is stored after it like any other turn. Files in the workspace are never
     * deleted. Returns false when [messageId] is not a user message of this chat.
     *
     * The dependants are removed first: if the app dies in between, the chat shows the old text without answers
     * (harmless) instead of the new text next to answers that were written for the old one.
     */
    suspend fun rewriteUserMessage(
        sessionId: String,
        messageId: String,
        content: String,
        removeIds: List<String>,
        keepAnswer: Boolean = false
    ): Boolean {
        // Regenerate keeps the answer being replaced as a variant; a changed prompt makes the old ones meaningless.
        if (keepAnswer) archiveCurrentTurn(sessionId, messageId) else messageDao.updateVariants(messageId, null)
        for (chunk in removeIds.chunked(REMOVE_CHUNK)) {
            attachmentDao?.deleteForMessages(sessionId, chunk)
            messageDao.deleteMessagesByIds(sessionId, chunk)
        }
        return updateUserMessageContent(sessionId, messageId, content)
    }

    /** The messages of the turn answering [userId]: everything after it up to the next user message. */
    private fun turnOf(all: List<ChatMessageEntity>, userId: String): Pair<ChatMessageEntity, List<ChatMessageEntity>>? {
        val index = all.indexOfFirst { it.id == userId && it.role == MessageRole.USER }
        if (index < 0) return null
        val rest = all.subList(index + 1, all.size)
        val end = rest.indexOfFirst { it.role == MessageRole.USER }.let { if (it < 0) rest.size else it }
        return all[index] to rest.subList(0, end).toList()
    }

    /** Stores the current answer of [userId] as a variant before Regenerate removes it. */
    private suspend fun archiveCurrentTurn(sessionId: String, userId: String) {
        val (user, turn) = turnOf(messageDao.getMessagesForSession(sessionId), userId) ?: return
        if (turn.none { it.role == MessageRole.ASSISTANT || it.role == MessageRole.TOOL }) return
        val variants = TurnVariants.decode(user.variantsJson) ?: TurnVariants(active = 0, archived = emptyList())
        messageDao.updateVariants(userId, variants.archiveActive(turn).encode())
    }

    /** The earlier answer [slot] of [userId] as messages for display (not stored as messages); null when unknown. */
    fun archivedTurn(sessionId: String, variantsJson: String?, slot: Int): List<ChatMessageDomainModel>? =
        TurnVariants.decode(variantsJson)?.archived?.firstOrNull { it.slot == slot }
            ?.messages?.map { it.copy(sessionId = sessionId).toDomain() }

    /**
     * Makes the earlier answer [slot] of [userId] the live one: the current answer becomes a variant and the chosen
     * one is stored again as messages (same ids, same times). Only for the last turn of the chat; false otherwise.
     * The new answer is written before the old one is removed, so a crash in between shows both, never neither.
     */
    suspend fun activateVariant(sessionId: String, userId: String, slot: Int): Boolean {
        val all = messageDao.getMessagesForSession(sessionId)
        val (user, turn) = turnOf(all, userId) ?: return false
        if (all.last().id != (turn.lastOrNull() ?: user).id) return false
        val variants = TurnVariants.decode(user.variantsJson) ?: return false
        val chosen = variants.archived.firstOrNull { it.slot == slot } ?: return false
        messageDao.updateVariants(userId, variants.swap(turn, chosen).encode())
        messageDao.insertMessages(chosen.messages.map { it.copy(sessionId = sessionId, variantsJson = null) })
        if (turn.isNotEmpty()) messageDao.deleteMessagesByIds(sessionId, turn.map { it.id })
        sessionDao.touchSession(sessionId, System.currentTimeMillis())
        return true
    }

    suspend fun updateGenerationTelemetry(
        messageId: String,
        elapsedMs: Long,
        contextUsed: Int,
        contextTotal: Int,
        generatedTokens: Int
    ) = messageDao.updateGenerationTelemetry(messageId, elapsedMs, contextUsed, contextTotal, generatedTokens)

    suspend fun addModelName(sessionId: String, modelName: String) {
        val session = sessionDao.getSessionById(sessionId) ?: return
        val names = runCatching {
            org.json.JSONArray(session.modelNamesJson).let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
        }.getOrDefault(emptyList()).toMutableList()
        // Most recent last: the chat header shows the last model this chat used.
        if (modelName.isNotBlank()) {
            names.remove(modelName)
            names += modelName
        }
        val json = org.json.JSONArray().apply { names.forEach(::put) }.toString()
        sessionDao.updateModelNames(sessionId, json)
    }

    suspend fun updateGenerationProgress(sessionId: String, startedAt: Long, elapsedMs: Long, tokens: Int, contextUsed: Int, contextTotal: Int) =
        sessionDao.updateGenerationProgress(sessionId, startedAt, elapsedMs, tokens, contextUsed, contextTotal)

    suspend fun finishGeneration(sessionId: String, elapsedMs: Long, tokens: Int, contextUsed: Int, contextTotal: Int, note: String?, state: String) =
        sessionDao.finishGeneration(sessionId, elapsedMs, tokens, contextUsed, contextTotal, note, state)

    suspend fun clearTransientGenerationStates() = sessionDao.clearTransientGenerationStates()

    suspend fun deleteSession(sessionId: String) {
        // chat_messages.session_id has ON DELETE CASCADE; deleting the messages first is harmless.
        messageDao.deleteMessagesForSession(sessionId)
        sessionDao.deleteSessionById(sessionId)
    }

    suspend fun clearAllSessions() {
        sessionDao.deleteAllSessions()
    }

    companion object {
        const val DEFAULT_TITLE = "New Chat"

        /** "New Chat" or "New Chat (2)" (the chat folder numbers equal titles): the title still comes from the first message. */
        fun isDefaultTitle(title: String): Boolean = title == DEFAULT_TITLE || Regex("^New Chat \\(\\d+\\)$").matches(title)
        private const val REMOVE_CHUNK = 500
    }

    private fun ChatSessionEntity.toDomain(): ChatSessionDomainModel {
        val names = runCatching {
            org.json.JSONArray(modelNamesJson).let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
        }.getOrDefault(emptyList())
        val resolvedNames = if (names.isEmpty() && !modelPath.isNullOrBlank()) listOf(java.io.File(modelPath).name) else names
        return ChatSessionDomainModel(id, title, createdAt, updatedAt, modelPath, resolvedNames, generationState, generationStartedAt, generationElapsedMs, generationTokens, contextUsed, contextTotal, generationNote)
    }

    private fun ChatMessageEntity.toDomain() = ChatMessageDomainModel(
        id = id, sessionId = sessionId, role = role, content = content, timestamp = timestamp,
        reasoning = reasoning, tokensPerSecond = tokensPerSecond, prefillMs = prefillMs,
        generatedTokens = generatedTokens, cacheHitPct = cacheHitPct,
        generationElapsedMs = generationElapsedMs, contextUsed = contextUsed, contextTotal = contextTotal, note = note,
        prefillTokens = prefillTokens, thinkingMs = thinkingMs, toolMs = toolMs,
        runInfo = runInfo, variantsJson = variantsJson
    )
}
