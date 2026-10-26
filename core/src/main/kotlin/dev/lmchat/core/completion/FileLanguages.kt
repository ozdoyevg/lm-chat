package dev.lmchat.core.markdown

object FileLanguages {
    /** Language name for syntax highlighting, based on the file extension. Unknown extensions are returned as is. */
    fun fromPath(path: String?): String {
        val extension = path?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when (extension) {
            "php", "phtml", "inc" -> "php"
            "js", "mjs", "jsx" -> "js"
            "ts", "tsx" -> "ts"
            "py" -> "py"
            "kt", "kts" -> "kt"
            "sh", "bash" -> "sh"
            "yml", "yaml" -> "yaml"
            else -> extension
        }
    }
}