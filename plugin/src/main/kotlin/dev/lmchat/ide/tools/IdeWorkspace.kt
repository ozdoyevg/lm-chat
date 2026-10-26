package dev.lmchat.ide.tools

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.lmchat.core.tools.PathSafety
import dev.lmchat.core.tools.WorkspaceFiles
import java.nio.file.Path
import java.nio.file.Paths

/** Project files as the IDE sees them: unsaved editor changes are visible and writes go through the VFS (undoable). */
class IdeWorkspace(private val project: Project) : WorkspaceFiles {

    private val root: Path
        get() = Paths.get(project.basePath ?: error("No project path")).toAbsolutePath().normalize()

    override fun readText(path: String): String {
        val file = findFile(path)
        require(!file.isDirectory) { "$path is a directory" }
        require(!file.fileType.isBinary) { "$path is a binary file" }
        return ReadAction.compute<String, Throwable> {
            FileDocumentManager.getInstance().getCachedDocument(file)?.text
                ?: String(file.contentsToByteArray(), file.charset)
        }
    }

    override fun listDirectory(path: String): List<String> {
        val directory = PathSafety.resolveInside(root, path).toFile()
        require(directory.isDirectory) { "Not a directory: $path" }
        return (directory.listFiles() ?: emptyArray()).sortedBy { it.name }.take(300)
            .map { if (it.isDirectory) it.name + "/" else it.name }
    }

    override fun searchText(query: String, maxMatches: Int): List<String> {
        require(query.isNotBlank()) { "query is empty" }
        val prefix = (project.basePath ?: "") + "/"
        val matches = ArrayList<String>()
        var scanned = 0
        ReadAction.run<Throwable> {
            ProjectFileIndex.getInstance(project).iterateContent { file ->
                if (file.isDirectory || file.length > MAX_FILE_BYTES || file.fileType.isBinary) return@iterateContent true
                if (scanned++ > MAX_FILES) return@iterateContent false
                val text = String(file.contentsToByteArray(), file.charset)
                if (text.contains(query, ignoreCase = true)) {
                    text.lineSequence().forEachIndexed { index, line ->
                        if (matches.size < maxMatches && line.contains(query, ignoreCase = true)) {
                            matches.add("${file.path.removePrefix(prefix)}:${index + 1}: ${line.trim().take(200)}")
                        }
                    }
                }
                matches.size < maxMatches
            }
        }
        return matches
    }

    override fun writeText(path: String, content: String) {
        val target = PathSafety.resolveInside(root, path)
        val fileName = target.fileName.toString()
        WriteCommandAction.writeCommandAction(project).withName("LM Chat: $path").run<Throwable> {
            val directory = VfsUtil.createDirectoryIfMissing(target.parent.toString().replace('\\', '/'))
                ?: error("Cannot create directory")
            val file = directory.findChild(fileName) ?: directory.createChildData(this, fileName)
            VfsUtil.saveText(file, content)
        }
    }

    private fun findFile(path: String): VirtualFile =
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(PathSafety.resolveInside(root, path))
            ?: error("File not found: $path")

    private companion object {
        const val MAX_FILE_BYTES = 500_000L
        const val MAX_FILES = 20_000
    }
}