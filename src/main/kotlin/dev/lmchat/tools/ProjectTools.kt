package dev.lmchat.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import dev.lmchat.api.str
import java.nio.file.Path
import java.nio.file.Paths

class ProjectTools(private val project: Project, private val approver: (String) -> Boolean) {

    val specs: JsonArray = JsonArray().apply {
        add(spec("get_active_file", "Get path, selected text and full content of the file open in the editor."))
        add(spec("list_dir", "List a directory. Path is relative to the project root; use '.' for the root.",
            mapOf("path" to "Relative directory path")))
        add(spec("read_file", "Read a text file from the project.",
            mapOf("path" to "Relative file path")))
        add(spec("search_text", "Case-insensitive text search across project files. Returns path:line: text.",
            mapOf("query" to "Text to search for")))
        add(spec("replace_in_file", "Replace one exact, unique snippet in a file. Requires user approval.",
            mapOf("path" to "Relative file path", "old_text" to "Exact text to replace (must occur once)",
                "new_text" to "Replacement text")))
        add(spec("write_file", "Create or overwrite a whole file. Requires user approval.",
            mapOf("path" to "Relative file path", "content" to "Full file content")))
    }

    /** Must be called from a background thread. */
    fun execute(name: String, argsJson: String): String = try {
        val a = if (argsJson.isBlank()) JsonObject() else JsonParser.parseString(argsJson).asJsonObject
        fun arg(k: String) = a.str(k) ?: error("Missing argument: $k")
        when (name) {
            "get_active_file" -> activeFile()
            "list_dir" -> listDir(a.str("path") ?: ".")
            "read_file" -> cap(readRaw(arg("path")))
            "search_text" -> searchText(arg("query"))
            "replace_in_file" -> replaceInFile(arg("path"), arg("old_text"), arg("new_text"))
            "write_file" -> applyWrite(arg("path"), arg("content"), "Write ${arg("path")}")
            else -> "Unknown tool: $name"
        }
    } catch (e: Exception) {
        "Error: ${e.message ?: e.toString()}"
    }

    // ---- tools ----

    private fun activeFile(): String {
        var result = "No file is open in the editor."
        ApplicationManager.getApplication().invokeAndWait {
            val ed = FileEditorManager.getInstance(project).selectedTextEditor ?: return@invokeAndWait
            val vf = FileDocumentManager.getInstance().getFile(ed.document)
            val sel = ed.selectionModel.selectedText ?: "(none)"
            result = cap("path: ${vf?.path}\nselection: $sel\n---\n${ed.document.text}")
        }
        return result
    }

    private fun listDir(rel: String): String {
        val dir = resolve(rel).toFile()
        require(dir.isDirectory) { "Not a directory: $rel" }
        return (dir.listFiles() ?: emptyArray()).sortedBy { it.name }.take(300)
            .joinToString("\n") { if (it.isDirectory) it.name + "/" else it.name }
            .ifEmpty { "(empty)" }
    }

    private fun readRaw(rel: String): String {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolve(rel))
            ?: error("File not found: $rel")
        require(!vf.isDirectory) { "$rel is a directory" }
        require(!vf.fileType.isBinary) { "$rel is a binary file" }
        return ReadAction.compute<String, Throwable> {
            FileDocumentManager.getInstance().getCachedDocument(vf)?.text
                ?: String(vf.contentsToByteArray(), vf.charset)
        }
    }

    private fun searchText(query: String): String {
        require(query.isNotBlank()) { "query is empty" }
        val prefix = (project.basePath ?: "") + "/"
        val hits = ArrayList<String>()
        var scanned = 0
        ReadAction.run<Throwable> {
            ProjectFileIndex.getInstance(project).iterateContent { vf ->
                if (vf.isDirectory || vf.length > 500_000 || vf.fileType.isBinary) return@iterateContent true
                if (scanned++ > 20_000) return@iterateContent false
                val text = String(vf.contentsToByteArray(), vf.charset)
                if (text.contains(query, ignoreCase = true)) {
                    text.lineSequence().forEachIndexed { i, line ->
                        if (hits.size < 60 && line.contains(query, ignoreCase = true)) {
                            hits.add("${vf.path.removePrefix(prefix)}:${i + 1}: ${line.trim().take(200)}")
                        }
                    }
                }
                hits.size < 60
            }
        }
        return if (hits.isEmpty()) "No matches" else hits.joinToString("\n")
    }

    private fun replaceInFile(rel: String, old: String, replacement: String): String {
        require(old.isNotEmpty()) { "old_text is empty" }
        val current = readRaw(rel)
        val first = current.indexOf(old)
        require(first >= 0) { "old_text not found in $rel" }
        require(current.indexOf(old, first + old.length) < 0) { "old_text occurs more than once; add more context" }
        val updated = current.replaceRange(first, first + old.length, replacement)
        return applyWrite(rel, current.replaceRange(first, first + old.length, replacement), "Edit $rel")
    }

    private fun applyWrite(rel: String, content: String, summary: String): String {
        val p = resolve(rel)
        if (!approver(summary)) return "The user denied this change."

        val fileName = p.fileName.toString()
        WriteCommandAction.writeCommandAction(project).withName("LM Chat: $rel").run<Throwable> {
            val dir = VfsUtil.createDirectoryIfMissing(p.parent.toString().replace('\\', '/'))
                ?: error("Cannot create directory")
            val file = dir.findChild(fileName) ?: dir.createChildData(this, fileName)
            VfsUtil.saveText(file, content)
        }
        return "OK: wrote ${content.length} chars to $rel"
    }

    // ---- helpers ----

    private fun resolve(rel: String): Path {
        val base = Paths.get(project.basePath ?: error("No project path")).normalize()
        val p = base.resolve(rel.ifBlank { "." }).normalize()
        require(p.startsWith(base)) { "Path is outside the project: $rel" }
        return p
    }

    private fun cap(s: String, n: Int = 60_000) =
        if (s.length > n) s.take(n) + "\n...[truncated, ${s.length} chars total]" else s

    private fun spec(name: String, desc: String, props: Map<String, String> = emptyMap()): JsonObject {
        val properties = JsonObject()
        props.forEach { (k, d) ->
            properties.add(k, JsonObject().apply { addProperty("type", "string"); addProperty("description", d) })
        }
        val required = JsonArray().also { arr -> props.keys.forEach { arr.add(it) } }
        val params = JsonObject().apply {
            addProperty("type", "object"); add("properties", properties); add("required", required)
        }
        val fn = JsonObject().apply {
            addProperty("name", name); addProperty("description", desc); add("parameters", params)
        }
        return JsonObject().apply { addProperty("type", "function"); add("function", fn) }
    }
}