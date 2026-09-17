package io.github.brrenat.seekervault.feeds.storage

import android.util.AtomicFile
import io.github.brrenat.seekervault.connections.isConnectionId
import io.github.brrenat.seekervault.feeds.FeedCursor
import java.io.File
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/**
 * Where a feed's listener left off, one atomic JSON document per feed in `filesDir/feeds/`
 * (SEE-91).
 *
 * Two numbers and a string, and nothing else: the broker's position in the channel, and the
 * sequence the last completed snapshot walk was taken at. They are what makes coming back cheap —
 * the broker replays what was missed when it still can, and the gateway answers "unchanged" when
 * nothing has moved — so what is kept here is progress, never content. The documents live in the
 * proposal store and the connection record, and the settings revision lives in the connection
 * record too, because a second copy of it here could disagree with the manifest the phone actually
 * validated. Losing this file costs one snapshot and nothing else, which is why nothing in it is
 * worth protecting.
 *
 * It is keyed by the publisher's server ID rather than by the channel, because a channel is
 * `server/<server_id>` and a file name cannot hold the slash. The channel is derived, never stored.
 */
class FeedCursorStore(private val dir: File) {

    /** What is known about one feed's progress. */
    data class Progress(
        val serverId: String,
        /** The broker's position, or null when none is held — a new feed, or one that was reset. */
        val cursor: FeedCursor? = null,
        /** The sequence the last completed snapshot walk was taken at. */
        val sequence: Long = 0L,
    )

    fun get(serverId: String): Progress? {
        if (!isConnectionId(serverId)) return null
        return try {
            decode(String(file(serverId).readFully(), Charsets.UTF_8))?.takeIf {
                it.serverId == serverId
            }
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun put(progress: Progress) {
        require(isConnectionId(progress.serverId)) { "not a server ID" }
        require(progress.sequence >= 0) { "negative progress" }
        require(progress.cursor == null || progress.cursor.offset >= 0) { "negative offset" }
        // An offset without an epoch is not a position: the epoch is what says which history the
        // offset counts in, so a cursor missing one would ask the broker to recover from a stream
        // it cannot name.
        require(progress.cursor == null || progress.cursor.epoch.isNotEmpty()) { "no epoch" }
        val target = file(progress.serverId)
        dir.mkdirs()
        val stream = target.startWrite()
        try {
            stream.write(encode(progress).toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (e: IOException) {
            target.failWrite(stream)
            throw e
        }
    }

    fun delete(serverId: String) {
        if (!isConnectionId(serverId)) return
        file(serverId).delete()
    }

    /** The feeds progress is held for, so removing a connection can take its cursor with it. */
    fun serverIds(): Set<String> =
        dir.listFiles { file -> file.name.endsWith(SUFFIX) }
            .orEmpty()
            .map { it.name.removeSuffix(SUFFIX) }
            .filter(::isConnectionId)
            .toSet()

    private fun file(serverId: String): AtomicFile {
        require(isConnectionId(serverId)) { "not a server ID" }
        return AtomicFile(File(dir, "$serverId$SUFFIX"))
    }

    private companion object {
        const val SUFFIX = ".json"
        const val VERSION = 1

        fun encode(progress: Progress): String =
            JSONObject()
                .put("version", VERSION)
                .put("serverId", progress.serverId)
                .put("epoch", progress.cursor?.epoch ?: "")
                .put("offset", progress.cursor?.offset ?: 0L)
                .put("sequence", progress.sequence)
                .toString()

        /**
         * A document from a newer version of the app is refused rather than guessed at, as
         * everywhere else on this phone. Refusing costs one snapshot: the listener starts without a
         * cursor, the broker cannot prove continuity, and the authoritative read fills it in.
         */
        fun decode(text: String): Progress? {
            val json = JSONObject(text)
            if (json.getInt("version") != VERSION) return null
            val epoch = json.optString("epoch")
            val offset = json.optLong("offset")
            return Progress(
                serverId = json.getString("serverId"),
                cursor = if (epoch.isEmpty()) null else FeedCursor(epoch, offset),
                sequence = json.optLong("sequence"),
            )
        }
    }
}
