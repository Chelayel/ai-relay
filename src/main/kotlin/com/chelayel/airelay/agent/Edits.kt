package com.chelayel.airelay.agent

import java.io.File

/**
 * Every file an agent changed this session, with what it held before, so a
 * change can be shown as a diff and put back with one command. Kept in memory
 * and bounded: this is an undo for the last few things, not a history.
 *
 * Shared by all backends — Gemini and Copilot record from `Tools`, Claude from
 * the `Edit`/`Write` calls its CLI reports — so `/revert` and the panel's
 * button mean the same thing everywhere.
 */
object Edits {
    class Change(val id: String, val path: String, val file: File, val before: String?, val after: String, val diff: String)

    private const val KEEP = 60
    private val changes = LinkedHashMap<String, Change>()
    private var counter = 0

    @Synchronized
    fun record(file: File, label: String, before: String?, after: String): Change {
        val id = "e${++counter}"
        val change = Change(id, label, file, before, after, Diff.unified(before ?: "", after, label))
        changes[id] = change
        while (changes.size > KEEP) changes.remove(changes.keys.first())
        return change
    }

    @Synchronized fun get(id: String): Change? = changes[id]

    /** Every file touched this session, as one unified diff from its first "before" to its current content. */
    @Synchronized
    fun sessionPatch(): String {
        val first = LinkedHashMap<String, Change>()
        for (c in changes.values) first.putIfAbsent(c.file.canonicalPath, c)
        return first.values.joinToString("") { c ->
            val now = if (c.file.isFile) c.file.readText() else ""
            if (now == (c.before ?: "")) "" else Diff.unified(c.before ?: "", now, c.path)
        }
    }

    @Synchronized fun touchedFiles(): List<File> = changes.values.map { it.file }.distinctBy { it.canonicalPath }
    @Synchronized fun lastId(): String? = changes.keys.lastOrNull()

    /**
     * Put the file back as it was before [id]. The revert is itself recorded,
     * so it can be reverted in turn. Refuses when the file has moved on since,
     * rather than stamping over a later change.
     */
    @Synchronized
    fun revert(id: String?): Result<Change> {
        val target = id ?: lastId() ?: return Result.failure(IllegalStateException("Nothing to revert."))
        val change = changes[target] ?: return Result.failure(IllegalArgumentException("No change $target."))
        val current = if (change.file.isFile) change.file.readText() else null
        // A reverted creation leaves no file; that is what its "after" recorded as "".
        if ((current ?: "") != change.after) {
            return Result.failure(IllegalStateException("${change.path} changed again after $target; revert the later change first."))
        }
        return runCatching {
            if (change.before == null) change.file.delete() else change.file.writeText(change.before)
            record(change.file, change.path, change.after, change.before ?: "")
        }
    }
}
