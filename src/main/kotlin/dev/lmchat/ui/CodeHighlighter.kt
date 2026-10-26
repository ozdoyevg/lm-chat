package dev.lmchat.ui

// ---------------------------------------------------------------------------------------------
// Tiny line-based syntax highlighter (good enough for chat snippets)
// ---------------------------------------------------------------------------------------------

enum class Kind { PLAIN, COMMENT, STRING, VAR, NUMBER, KEYWORD, CALL }

class CodeHighlighter(lang: String) {
    private val ci = lang == "php" || lang == "sql"
    private val keywords = KEYWORDS[ALIASES[lang] ?: lang] ?: GENERIC
    private val re = if (lang in HASH_LANGS) RE_HASH else RE_PLAIN
    private var inBlock = false

    fun line(s: String): List<Pair<String, Kind>> {
        val out = ArrayList<Pair<String, Kind>>()
        var i = 0
        if (inBlock) {
            val end = s.indexOf("*/")
            if (end < 0) return listOf(s to Kind.COMMENT)
            out.add(s.substring(0, end + 2) to Kind.COMMENT)
            inBlock = false
            i = end + 2
        }
        for (m in re.findAll(s, i)) {
            if (m.range.first > i) out.add(s.substring(i, m.range.first) to Kind.PLAIN)
            val t = m.value
            val kind = when {
                m.groups[1] != null -> {
                    if (t.startsWith("/*") && !t.endsWith("*/")) inBlock = true
                    Kind.COMMENT
                }

                m.groups[2] != null -> Kind.STRING
                m.groups[3] != null -> Kind.VAR
                m.groups[4] != null -> Kind.NUMBER
                (if (ci) t.lowercase() else t) in keywords -> Kind.KEYWORD
                s.getOrNull(m.range.last + 1) == '(' -> Kind.CALL
                else -> Kind.PLAIN
            }
            out.add(t to kind)
            i = m.range.last + 1
        }
        if (i < s.length) out.add(s.substring(i) to Kind.PLAIN)
        return out
    }

    companion object {
        private const val STR = """("(?:\\.|[^"\\])*"?|'(?:\\.|[^'\\])*'?|`(?:\\.|[^`\\])*`?)"""
        private const val REST = """|(\$[A-Za-z_]\w*)|(\b\d[\d_]*(?:\.\d+)?\b)|(\b[A-Za-z_]\w*\b)"""
        private val RE_PLAIN = Regex("""(/\*.*?\*/|/\*.*|//.*)|$STR$REST""")
        private val RE_HASH = Regex("""(/\*.*?\*/|/\*.*|//.*|#.*)|$STR$REST""")

        private fun words(s: String) = s.split(' ').filter { it.isNotEmpty() }.toSet()

        private val GENERIC =
            words("if else for while do return function class new true false null break continue switch case default try catch finally throw import export")
        private val KEYWORDS = mapOf(
            "php" to words("abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile enum extends final finally fn for foreach function global goto if implements include include_once instanceof insteadof interface isset list match namespace new or print private protected public readonly require require_once return static switch throw trait try unset use var while xor yield null true false self parent"),
            "js" to words("async await break case catch class const continue debugger default delete do else enum export extends false finally for from function if implements import in instanceof interface let new null of package private protected public return static super switch this throw true try type typeof undefined var void while with yield"),
            "py" to words("and as assert async await break class continue def del elif else except False finally for from global if import in is lambda None nonlocal not or pass raise return True try while with yield self"),
            "java" to words("abstract boolean break byte case catch char class const continue default do double else enum extends false final finally float for fun if implements import in instanceof int interface is long new null object open override package private protected public return short static super switch this throw true try val var void when while"),
            "sql" to words("select from where insert into values update set delete join left right inner outer on group by order having limit offset create table alter drop index primary key foreign references distinct union all as and or not null like in is between exists case when then else end asc desc"),
            "sh" to words("if then else elif fi for while do done case esac function in return export local echo cd exit")
        )
        private val ALIASES = mapOf(
            "javascript" to "js", "jsx" to "js", "ts" to "js", "typescript" to "js", "tsx" to "js",
            "python" to "py", "kotlin" to "java", "kt" to "java", "csharp" to "java", "cs" to "java",
            "bash" to "sh", "shell" to "sh", "zsh" to "sh", "phtml" to "php"
        )
        private val HASH_LANGS = setOf(
            "php", "phtml", "py", "python", "sh", "bash", "shell", "zsh", "yaml", "yml",
            "ruby", "rb", "ini", "conf", "toml", "dockerfile", "makefile"
        )
    }
}