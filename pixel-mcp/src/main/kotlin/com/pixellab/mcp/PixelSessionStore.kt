package com.pixellab.mcp

import com.pixellab.core.model.Palette
import com.pixellab.core.model.SpriteFactory
import com.pixellab.core.model.SpriteProject
import com.pixellab.core.palette.BuiltInPalettes
import com.pixellab.mcp.json.JsonObject
import com.pixellab.mcp.json.jsonobj
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory session registry mapping MCP session ids to
 * [SpriteProject] working copies.
 *
 * Every mutating tool writes its resulting project back through [update], so
 * a session always holds the latest editor state. Access is serialized by a
 * monitor for the compound operations (eviction, creation) and uses a
 * [ConcurrentHashMap] for plain lookups. When the number of live sessions
 * exceeds [maxSessions] (LRU cap, default 32) the least-recently-used session
 * is evicted — `lastUsed` is refreshed by every [get]/[update], making idle
 * agent conversations the first to go.
 *
 * Memory discipline: engine undo stacks key by *project id*, so a discarded
 * project's history would otherwise live forever. The optional
 * [onProjectDiscarded] listener fires for every project that leaves the
 * store's ownership — LRU eviction, [remove] and id swaps during [update] —
 * letting the host drop the matching undo/redo state.
 */
class PixelSessionStore(
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val onProjectDiscarded: ((projectId: String) -> Unit)? = null,
    private val onSessionDiscarded: ((sessionId: String) -> Unit)? = null,
) {

    /** One live MCP editing session. */
    class SessionState(
        /** Session id (the value clients pass as `session_id`). */
        val id: String,
        /** Wall-clock creation time (ms). */
        val createdAtMs: Long,
        /** Current working project; swapped atomically by [update]. */
        var project: SpriteProject,
        /** Wall-clock time of the last [get]/[update] (ms), for LRU eviction. */
        var lastUsedMs: Long,
    ) {
        internal fun touch() {
            lastUsedMs = System.currentTimeMillis()
        }
    }

    private val sessions = ConcurrentHashMap<String, SessionState>()

    /** Serializes the compound operations (create / touch / evict). */
    private val evictionLock = Any()

    /** Number of live sessions. */
    val sessionCount: Int get() = sessions.size

    /**
     * Creates a fresh session holding a blank `width x height` project on
     * [palette], registers it and returns it. Evicts the least-recently-used
     * sessions when the cap would be exceeded.
     */
    fun newSession(
        palette: Palette = BuiltInPalettes.PICO8,
        width: Int = DEFAULT_SIZE,
        height: Int = DEFAULT_SIZE,
        name: String = DEFAULT_NAME,
        id: String = defaultId(),
    ): SessionState {
        val session = SessionState(
            id = id,
            createdAtMs = System.currentTimeMillis(),
            project = SpriteFactory.create(name, width, height, palette),
            lastUsedMs = System.currentTimeMillis(),
        )
        synchronized(evictionLock) {
            // Registering under an id that already exists replaces that
            // session — the displaced project leaves the store's ownership
            // and must fire the discard callbacks, or its engine undo
            // history leaks forever (histories key by project id).
            val displaced = sessions.put(id, session)
            if (displaced != null) {
                onProjectDiscarded?.invoke(displaced.project.id)
            }
            evictOverCap()
        }
        return session
    }

    /**
     * Looks up a session. When missing and [create] is true, a blank session
     * is registered under the requested [id] (agents that restart keep
     * working); with `create = false` the miss returns null.
     */
    fun get(id: String, create: Boolean = true): SessionState? {
        sessions[id]?.let { session ->
            synchronized(evictionLock) { session.touch() }
            return session
        }
        if (!create) return null
        return newSession(id = id)
    }

    /** Stores [project] back into the session and refreshes its LRU stamp. */
    fun update(id: String, project: SpriteProject): SessionState? = synchronized(evictionLock) {
        val session = sessions[id] ?: return null
        val previous = session.project
        if (previous.id != project.id) {
            // The session swapped to a different project lineage (template
            // apply, project load): the old project's history is orphaned.
            onProjectDiscarded?.invoke(previous.id)
        }
        session.project = project
        session.touch()
        session
    }

    /** Drops the session with [id]; true when it existed. Runs under the
     *  same lock as eviction so a concurrent evictOverCap cannot select this
     *  session and double-fire the discard callbacks. */
    fun remove(id: String): Boolean = synchronized(evictionLock) {
        val session = sessions.remove(id) ?: return false
        onProjectDiscarded?.invoke(session.project.id)
        onSessionDiscarded?.invoke(session.id)
        true
    }

    /** Drops every session. Locked against concurrent newSession so a
     *  clear() cannot race a registration into a zombie session that
     *  survives the clear (and its stop()-determinism guarantee). */
    fun clear() = synchronized(evictionLock) {
        for (session in sessions.values) {
            onProjectDiscarded?.invoke(session.project.id)
            onSessionDiscarded?.invoke(session.id)
        }
        sessions.clear()
    }

    /** Summaries (id, project id, name, size, frame/layer counts, stamps) of all sessions. */
    fun list(): List<JsonObject> = sessions.values
        .sortedBy { it.createdAtMs }
        .map { session ->
            jsonobj {
                put("session_id", session.id)
                put("project_id", session.project.id)
                put("name", session.project.name)
                put("width", session.project.width)
                put("height", session.project.height)
                put("frames", session.project.frameCount)
                put("layers", session.project.layerCount)
                put("palette_id", session.project.palette.id)
                put("created_at_ms", session.createdAtMs)
                put("last_used_ms", session.lastUsedMs)
            }
        }

    /** Removes least-recently-used sessions until the cap is satisfied
     *  again. Callers hold [evictionLock]; callbacks fire only for entries
     *  this loop actually removed (a concurrent remove() may have taken the
     *  same entry first — double-firing would violate the single-shot
     *  discard contract). */
    private fun evictOverCap() {
        while (sessions.size > maxSessions) {
            val oldest = sessions.values.minByOrNull { it.lastUsedMs } ?: break
            val removed = sessions.remove(oldest.id) ?: continue
            onProjectDiscarded?.invoke(removed.project.id)
            onSessionDiscarded?.invoke(removed.id)
        }
    }

    private companion object {
        /** Default LRU cap (32 sessions). */
        const val DEFAULT_MAX_SESSIONS: Int = 32

        /** Default canvas edge for auto-created sessions. */
        const val DEFAULT_SIZE: Int = 16

        /** Default project name for auto-created sessions. */
        const val DEFAULT_NAME: String = "untitled"
    }

    /** Fresh opaque session id. */
    internal fun defaultId(): String = "sess-" + java.lang.Long.toString(System.nanoTime(), 36)
}
