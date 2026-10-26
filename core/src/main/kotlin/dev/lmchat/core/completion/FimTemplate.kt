package dev.lmchat.core.completion

/** Fill-in-the-middle prompt formats used by code models on /v1/completions. */
enum class FimTemplate(
    val label: String,
    private val prefixToken: String,
    private val suffixToken: String,
    private val middleToken: String,
    val stopSequences: List<String>
) {
    QWEN(
        "Qwen-Coder", "<|fim_prefix|>", "<|fim_suffix|>", "<|fim_middle|>",
        listOf("<|fim_pad|>", "<|endoftext|>", "<|file_sep|>")
    ),
    STARCODER(
        "StarCoder / Granite", "<fim_prefix>", "<fim_suffix>", "<fim_middle>",
        listOf("<fim_prefix>", "<fim_middle>", "<file_sep>", "<|endoftext|>")
    ),
    DEEPSEEK(
        "DeepSeek-Coder", "<\uFF5Cfim\u2581begin\uFF5C>", "<\uFF5Cfim\u2581hole\uFF5C>", "<\uFF5Cfim\u2581end\uFF5C>",
        listOf("<\uFF5Cfim\u2581begin\uFF5C>", "<\uFF5Cfim\u2581end\uFF5C>", "<|EOT|>")
    );

    fun buildPrompt(prefix: String, suffix: String): String =
        prefixToken + prefix + suffixToken + suffix + middleToken

    companion object {
        fun byName(name: String): FimTemplate = values().firstOrNull { it.name == name } ?: QWEN
    }
}