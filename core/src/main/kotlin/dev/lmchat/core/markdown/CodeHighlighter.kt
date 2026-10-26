package dev.lmchat.core.markdown

enum class TokenKind { PLAIN, COMMENT, STRING, VARIABLE, NUMBER, KEYWORD, CALL }

/** Tiny line-based tokenizer. Good enough to color snippets, not a parser. One instance per code block. */
class CodeHighlighter(language: String) {
    private val caseInsensitiveKeywords = language == "php" || language == "sql"
    private val keywords = KEYWORDS[ALIASES[language] ?: language] ?: GENERIC_KEYWORDS
    private val tokenPattern = if (language in HASH_COMMENT_LANGUAGES) PATTERN_WITH_HASH_COMMENTS else PATTERN
    private var insideBlockComment = false

    fun highlightLine(line: String): List<Pair<String, TokenKind>> {
        val tokens = ArrayList<Pair<String, TokenKind>>()
        var position = 0
        if (insideBlockComment) {
            val close = line.indexOf("*/")
            if (close < 0) return listOf(line to TokenKind.COMMENT)
            tokens.add(line.substring(0, close + 2) to TokenKind.COMMENT)
            insideBlockComment = false
            position = close + 2
        }
        for (match in tokenPattern.findAll(line, position)) {
            if (match.range.first > position) tokens.add(line.substring(position, match.range.first) to TokenKind.PLAIN)
            val text = match.value
            val kind = when {
                match.groups[1] != null -> {
                    if (text.startsWith("/*") && !text.endsWith("*/")) insideBlockComment = true
                    TokenKind.COMMENT
                }

                match.groups[2] != null -> TokenKind.STRING
                match.groups[3] != null -> TokenKind.VARIABLE
                match.groups[4] != null -> TokenKind.NUMBER
                (if (caseInsensitiveKeywords) text.lowercase() else text) in keywords -> TokenKind.KEYWORD
                line.getOrNull(match.range.last + 1) == '(' -> TokenKind.CALL
                else -> TokenKind.PLAIN
            }
            tokens.add(text to kind)
            position = match.range.last + 1
        }
        if (position < line.length) tokens.add(line.substring(position) to TokenKind.PLAIN)
        return tokens
    }

    private companion object {
        const val STRINGS = """("(?:\\.|[^"\\])*"?|'(?:\\.|[^'\\])*'?|`(?:\\.|[^`\\])*`?)"""
        const val REST = """|(\$[A-Za-z_]\w*)|(\b\d[\d_]*(?:\.\d+)?\b)|(\b[A-Za-z_]\w*\b)"""
        val PATTERN = Regex("""(/\*.*?\*/|/\*.*|//.*)|$STRINGS$REST""")
        val PATTERN_WITH_HASH_COMMENTS = Regex("""(/\*.*?\*/|/\*.*|//.*|#.*)|$STRINGS$REST""")

        fun words(text: String) = text.split(' ').filter { it.isNotEmpty() }.toSet()

        val GENERIC_KEYWORDS =
            words("if else for while do return function class new true false null break continue switch case default try catch finally throw import export")
        val KEYWORDS = mapOf(
            "php" to words("abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile enum extends final finally fn for foreach function global goto if implements include include_once instanceof insteadof interface isset list match namespace new or print private protected public readonly require require_once return static switch throw trait try unset use var while xor yield null true false self parent"),
            "js" to words("async await break case catch class const continue debugger default delete do else enum export extends false finally for from function if implements import in instanceof interface let new null of package private protected public return static super switch this throw true try type typeof undefined var void while with yield"),
            "py" to words("and as assert async await break class continue def del elif else except False finally for from global if import in is lambda None nonlocal not or pass raise return True try while with yield self"),
            "java" to words("abstract boolean break byte case catch char class const continue default do double else enum extends false final finally float for fun if implements import in instanceof int interface is long new null object open override package private protected public return short static super switch this throw true try val var void when while"),
            "sql" to words("select from where insert into values update set delete join left right inner outer on group by order having limit offset create table alter drop index primary key foreign references distinct union all as and or not null like in is between exists case when then else end asc desc"),
            "sh" to words("if then else elif fi for while do done case esac function in return export local echo cd exit")
        )
        val ALIASES = mapOf(
            "javascript" to "js", "jsx" to "js", "ts" to "js", "typescript" to "js", "tsx" to "js",
            "python" to "py", "kotlin" to "java", "kt" to "java", "csharp" to "java", "cs" to "java",
            "bash" to "sh", "shell" to "sh", "zsh" to "sh", "phtml" to "php"
        )
        val HASH_COMMENT_LANGUAGES = setOf(
            "php", "phtml", "py", "python", "sh", "bash", "shell", "zsh", "yaml", "yml",
            "ruby", "rb", "ini", "conf", "toml", "dockerfile", "makefile"
        )
    }
}