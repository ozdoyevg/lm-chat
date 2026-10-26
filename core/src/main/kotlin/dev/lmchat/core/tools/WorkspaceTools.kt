package dev.lmchat.core.tools

import dev.lmchat.core.llm.stringOrNull

/** The file tools offered to the model, built on top of any [WorkspaceFiles]. */
object WorkspaceTools {

    fun create(workspace: WorkspaceFiles, approver: ChangeApprover): List<Tool> = listOf(
        FunctionTool(
            "list_dir", "List a directory. Path is relative to the project root; use '.' for the root.",
            listOf(ToolParameter("path", "Relative directory path"))
        ) { arguments ->
            workspace.listDirectory(arguments.stringOrNull("path") ?: ".").joinToString("\n").ifEmpty { "(empty)" }
        },
        FunctionTool(
            "read_file", "Read a text file from the project.",
            listOf(ToolParameter("path", "Relative file path"))
        ) { arguments ->
            TextLimits.truncate(workspace.readText(arguments.requireString("path")))
        },
        FunctionTool(
            "search_text", "Case-insensitive text search across project files. Returns path:line: text.",
            listOf(ToolParameter("query", "Text to search for"))
        ) { arguments ->
            val matches = workspace.searchText(arguments.requireString("query"), MAX_MATCHES)
            if (matches.isEmpty()) "No matches" else matches.joinToString("\n")
        },
        FunctionTool(
            "replace_in_file", "Replace one exact, unique snippet in a file. Requires user approval.",
            listOf(
                ToolParameter("path", "Relative file path"),
                ToolParameter("old_text", "Exact text to replace (must occur once)"),
                ToolParameter("new_text", "Replacement text")
            )
        ) { arguments ->
            val path = arguments.requireString("path")
            val updated = TextEdits.replaceUnique(
                workspace.readText(path), arguments.requireString("old_text"), arguments.requireString("new_text")
            )
            writeWithApproval(workspace, approver, path, updated, "Edit $path")
        },
        FunctionTool(
            "write_file", "Create or overwrite a whole file. Requires user approval.",
            listOf(ToolParameter("path", "Relative file path"), ToolParameter("content", "Full file content"))
        ) { arguments ->
            val path = arguments.requireString("path")
            writeWithApproval(workspace, approver, path, arguments.requireString("content"), "Write $path")
        }
    )

    private fun writeWithApproval(
        workspace: WorkspaceFiles, approver: ChangeApprover, path: String, content: String, summary: String
    ): String {
        if (!approver.approve(summary)) return ToolResults.DENIED
        workspace.writeText(path, content)
        return "OK: wrote ${content.length} chars to $path"
    }

    private const val MAX_MATCHES = 60
}