package dev.lmchat.core.tools

import java.nio.file.Path

/** Where the file tools read and write. Implemented for the local disk and for the IDE. */
interface WorkspaceFiles {
    fun readText(path: String): String

    /** Entry names; directories end with "/". */
    fun listDirectory(path: String): List<String>

    /** Matches as "relative/path:line: text". */
    fun searchText(query: String, maxMatches: Int): List<String>
    fun writeText(path: String, content: String)
}

/** Asked before a file is changed. Returns true to allow it. May block while waiting for the user. */
fun interface ChangeApprover {
    fun approve(summary: String): Boolean
}

object PathSafety {
    /** Resolves [relative] against [root] and refuses anything that escapes it. */
    fun resolveInside(root: Path, relative: String): Path {
        val resolved = root.resolve(relative.ifBlank { "." }).normalize()
        require(resolved.startsWith(root)) { "Path is outside the project: $relative" }
        return resolved
    }
}

object TextEdits {
    /** Replaces the single occurrence of [old] in [original]. Fails if it is missing or ambiguous. */
    fun replaceUnique(original: String, old: String, replacement: String): String {
        require(old.isNotEmpty()) { "old_text is empty" }
        val first = original.indexOf(old)
        require(first >= 0) { "old_text not found" }
        require(original.indexOf(old, first + old.length) < 0) { "old_text occurs more than once; add more context" }
        return original.replaceRange(first, first + old.length, replacement)
    }
}

object TextLimits {
    fun truncate(text: String, maxLength: Int = 60_000): String =
        if (text.length > maxLength) text.take(maxLength) + "\n...[truncated, ${text.length} chars total]" else text
}