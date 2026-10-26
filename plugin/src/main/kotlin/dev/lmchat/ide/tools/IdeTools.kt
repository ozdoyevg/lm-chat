package dev.lmchat.ide.tools

import com.intellij.openapi.project.Project
import dev.lmchat.core.tools.ChangeApprover
import dev.lmchat.core.tools.ToolHost
import dev.lmchat.core.tools.ToolRegistry
import dev.lmchat.core.tools.WorkspaceTools

object IdeTools {
    /** The generic file tools on top of the IDE's file system, plus the IDE-only tools. */
    fun createHost(project: Project, approver: ChangeApprover): ToolHost =
        ToolRegistry(WorkspaceTools.create(IdeWorkspace(project), approver) + ActiveFileTool(project))
}