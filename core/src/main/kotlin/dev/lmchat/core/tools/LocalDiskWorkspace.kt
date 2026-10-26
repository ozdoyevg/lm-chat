package dev.lmchat.core.tools

import java.nio.file.Files
import java.nio.file.Path

/** A [WorkspaceFiles] backed by a directory on disk. Handy for CLI tools and tests. */
class LocalDiskWorkspace(rootDirectory: Path) : WorkspaceFiles {
    private val root: Path = rootDirectory.toAbsolutePath().normalize()

    override fun readText(path: String): String {
        val file = PathSafety.resolveInside(root, path)
        require(Files.isRegularFile(file)) { "File not found: $path" }
        require(!looksBinary(file)) { "$path is a binary file" }
        return Files.readString(file)
    }

    override fun listDirectory(path: String): List<String> {
        val directory = PathSafety.resolveInside(root, path).toFile()
        require(directory.isDirectory) { "Not a directory: $path" }
        return (directory.listFiles() ?: emptyArray()).sortedBy { it.name }.take(300)
            .map { if (it.isDirectory) it.name + "/" else it.name }
    }

    override fun searchText(query: String, maxMatches: Int): List<String> {
        require(query.isNotBlank()) { "query is empty" }
        val matches = ArrayList<String>()
        var scanned = 0
        val files = root.toFile().walkTopDown()
            .onEnter { !it.name.startsWith(".") }
            .filter { it.isFile && it.length() <= MAX_FILE_BYTES }
        for (file in files) {
            if (matches.size >= maxMatches || scanned++ > MAX_FILES) break
            if (looksBinary(file.toPath())) continue
            val relative = root.relativize(file.toPath()).toString().replace('\\', '/')
            file.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (matches.size < maxMatches && line.contains(query, ignoreCase = true)) {
                        matches.add("$relative:${index + 1}: ${line.trim().take(200)}")
                    }
                }
            }
        }
        return matches
    }

    override fun writeText(path: String, content: String) {
        val file = PathSafety.resolveInside(root, path)
        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(file, content)
    }

    private fun looksBinary(file: Path): Boolean =
        file.toFile().inputStream().use { input ->
            val head = ByteArray(512)
            val read = input.read(head)
            (0 until maxOf(read, 0)).any { head[it].toInt() == 0 }
        }

    private companion object {
        const val MAX_FILE_BYTES = 500_000L
        const val MAX_FILES = 20_000
    }
}