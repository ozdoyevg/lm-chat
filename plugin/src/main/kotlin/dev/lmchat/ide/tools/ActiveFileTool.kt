package dev.lmchat.ide.tools

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import dev.lmchat.core.tools.Tool
import dev.lmchat.core.tools.ToolParameter

/** IDE-only tool: the file currently open in the editor, including its selection and unsaved changes. */
class ActiveFileTool(private val project: Project) : Tool {
    override val name = "get_active_file"
    override val description = "Get path, selected text and full content of the file open in the editor."
    override val parameters: List<ToolParameter> = emptyList()

    override fun execute(arguments: JsonObject): String {
        var result = "No file is open in the editor."
        ApplicationManager.getApplication().invokeAndWait {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@invokeAndWait
            val file = FileDocumentManager.getInstance().getFile(editor.document)
            val selection = editor.selectionModel.selectedText ?: "(none)"
            val text = "path: ${file?.path}\nselection: $selection\n---\n${editor.document.text}"
            result = if (text.length > MAX_LENGTH) text.take(MAX_LENGTH) + "\n...[truncated]" else text
        }
        return result
    }

    private companion object {
        const val MAX_LENGTH = 60_000
    }
}