package com.driftcourse.app.cache

import android.content.Context
import android.util.Log
import com.driftcourse.app.net.Character
import com.driftcourse.app.net.Conversation
import com.driftcourse.app.net.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * アプリ起動直後にサーバに届かない時でもキャラ/会話/メッセージを即座に表示するための
 * シンプルなファイルベースキャッシュ。
 * - filesDir/cache/characters.json
 * - filesDir/cache/conversations.json
 * - filesDir/cache/messages/<convId>.json
 *
 * フェッチ成功時に上書き保存し、失敗時は何も触らない (UI 側でキャッシュを読み続ければ
 * 壊さずに表示を続けられる)。
 */
class LocalCache(context: Context) {
    private val root = File(context.filesDir, "cache").apply { mkdirs() }
    private val messagesDir = File(root, "messages").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val charactersFile get() = File(root, "characters.json")
    private val conversationsFile get() = File(root, "conversations.json")
    private fun messagesFile(convid: String): File {
        // ファイル名に使える文字に制限。サーバ側 id は token_urlsafe なので大抵通るが保険。
        val safe = convid.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        return File(messagesDir, "$safe.json")
    }

    suspend fun readCharacters(): List<Character> = withContext(Dispatchers.IO) {
        runCatching {
            val text = charactersFile.takeIf { it.exists() }?.readText() ?: return@runCatching emptyList()
            json.decodeFromString(ListSerializer(Character.serializer()), text)
        }.getOrElse {
            Log.w(TAG, "readCharacters failed", it); emptyList()
        }
    }

    suspend fun writeCharacters(list: List<Character>) = withContext(Dispatchers.IO) {
        runCatching {
            charactersFile.writeText(json.encodeToString(ListSerializer(Character.serializer()), list))
        }.onFailure { Log.w(TAG, "writeCharacters failed", it) }
        Unit
    }

    suspend fun readConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        runCatching {
            val text = conversationsFile.takeIf { it.exists() }?.readText() ?: return@runCatching emptyList()
            json.decodeFromString(ListSerializer(Conversation.serializer()), text)
        }.getOrElse {
            Log.w(TAG, "readConversations failed", it); emptyList()
        }
    }

    suspend fun writeConversations(list: List<Conversation>) = withContext(Dispatchers.IO) {
        runCatching {
            conversationsFile.writeText(json.encodeToString(ListSerializer(Conversation.serializer()), list))
        }.onFailure { Log.w(TAG, "writeConversations failed", it) }
        Unit
    }

    suspend fun readMessages(convid: String): List<Message> = withContext(Dispatchers.IO) {
        runCatching {
            val f = messagesFile(convid)
            val text = f.takeIf { it.exists() }?.readText() ?: return@runCatching emptyList()
            json.decodeFromString(ListSerializer(Message.serializer()), text)
        }.getOrElse {
            Log.w(TAG, "readMessages failed", it); emptyList()
        }
    }

    suspend fun writeMessages(convid: String, list: List<Message>) = withContext(Dispatchers.IO) {
        runCatching {
            messagesFile(convid).writeText(json.encodeToString(ListSerializer(Message.serializer()), list))
        }.onFailure { Log.w(TAG, "writeMessages failed", it) }
        Unit
    }

    suspend fun deleteMessages(convid: String) = withContext(Dispatchers.IO) {
        runCatching { messagesFile(convid).delete() }
        Unit
    }

    companion object { private const val TAG = "LocalCache" }
}
