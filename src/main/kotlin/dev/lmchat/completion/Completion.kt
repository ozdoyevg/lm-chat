package dev.lmchat.completion

import com.google.gson.JsonObject
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.ui.JBColor
import com.intellij.util.Alarm
import dev.lmchat.LmSettings
import dev.lmchat.api.OpenAiClient
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle

/** Fill-in-the-middle prompt formats used by code models on /v1/completions. */
enum class FimTemplate(
    val label: String, private val pre: String, private val suf: String, private val mid: String,
    val stop: List<String>
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

    fun prompt(prefix: String, suffix: String) = pre + prefix + suf + suffix + mid

    companion object {
        fun of(name: String): FimTemplate = values().firstOrNull { it.name == name } ?: QWEN
    }
}

// ---------------------------------------------------------------------------------------------
// Ghost text (inlays) + accepting
// ---------------------------------------------------------------------------------------------

object InlineGhost {
    private class GhostState(val offset: Int, val text: String, val inlays: List<Inlay<*>>)

    private val KEY = Key.create<GhostState>("lmchat.ghost")
    @Volatile
    var accepting = false

    fun has(editor: Editor) = editor.getUserData(KEY) != null

    fun show(editor: Editor, offset: Int, text: String) {
        clear(editor)
        val lines = text.split("\n")
        val inlays = mutableListOf<Inlay<*>>()
        if (lines.first().isNotEmpty()) {
            editor.inlayModel.addInlineElement(offset, true, GhostRenderer(listOf(lines.first())))
                ?.let { inlays.add(it) }
        }
        if (lines.size > 1) {
            editor.inlayModel.addBlockElement(offset, true, false, 0, GhostRenderer(lines.drop(1)))
                ?.let { inlays.add(it) }
        }
        if (inlays.isNotEmpty()) editor.putUserData(KEY, GhostState(offset, text, inlays))
    }

    fun clear(editor: Editor) {
        val st = editor.getUserData(KEY) ?: return
        editor.putUserData(KEY, null)
        st.inlays.forEach { Disposer.dispose(it) }
    }

    fun clearIfMoved(editor: Editor) {
        val st = editor.getUserData(KEY) ?: return
        if (editor.caretModel.offset != st.offset) clear(editor)
    }

    fun accept(editor: Editor): Boolean {
        val st = editor.getUserData(KEY) ?: return false
        val project = editor.project ?: return false
        clear(editor)
        accepting = true
        try {
            WriteCommandAction.writeCommandAction(project).withName("LM Chat: accept completion").run<Throwable> {
                editor.document.insertString(st.offset, st.text)
                editor.caretModel.moveToOffset(st.offset + st.text.length)
            }
            editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        } finally {
            accepting = false
        }
        return true
    }
}

private class GhostRenderer(private val lines: List<String>) : EditorCustomElementRenderer {
    private fun font(editor: Editor): Font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
    private fun metrics(editor: Editor) = editor.contentComponent.getFontMetrics(font(editor))
    private fun visible(s: String) = s.replace("\t", "    ")

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val m = metrics(inlay.editor)
        return lines.maxOf { m.stringWidth(visible(it)) }.coerceAtLeast(1)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = inlay.editor.lineHeight * lines.size

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val m = metrics(editor)
        val lh = editor.lineHeight
        g.color = JBColor.GRAY
        g.font = font(editor)
        lines.forEachIndexed { i, line ->
            val baseline = targetRegion.y + i * lh + (lh - m.height) / 2 + m.ascent
            g.drawString(visible(line), targetRegion.x, baseline)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Tab accepts the suggestion; otherwise Tab behaves as usual
// ---------------------------------------------------------------------------------------------

class TabAcceptHandler(private val original: EditorActionHandler) : EditorActionHandler() {
    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        InlineGhost.has(editor) || original.isEnabled(editor, caret, dataContext)

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        if (!InlineGhost.accept(editor)) original.execute(editor, caret, dataContext)
    }
}

// ---------------------------------------------------------------------------------------------
// Trigger + request
// ---------------------------------------------------------------------------------------------

class CompletionStartup : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        ApplicationManager.getApplication().getService(CompletionService::class.java) // creates the service
    }
}

class CompletionService : Disposable {
    private val log = Logger.getInstance(CompletionService::class.java)
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var generation = 0

    init {
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addDocumentListener(object : DocumentListener {
            override fun beforeDocumentChange(event: DocumentEvent) {
                if (InlineGhost.accepting) return
                EditorFactory.getInstance().getEditors(event.document).forEach { InlineGhost.clear(it) }
            }

            override fun documentChanged(event: DocumentEvent) = onChange(event)
        }, this)
        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = InlineGhost.clearIfMoved(event.editor)
        }, this)
    }

    private fun onChange(event: DocumentEvent) {
        if (InlineGhost.accepting) return
        val s = LmSettings.get().state
        if (!s.completionEnabled || s.completionModel.isBlank()) return
        if (event.newLength == 0 || event.newLength > 40) return          // ignore deletions and big pastes
        val editor = EditorFactory.getInstance().getEditors(event.document).firstOrNull {
            it.contentComponent.hasFocus() && it.editorKind == EditorKind.MAIN_EDITOR && !it.isViewer
        } ?: return
        val gen = ++generation
        alarm.cancelAllRequests()
        alarm.addRequest({ requestCompletion(editor, gen) }, s.completionDelayMs)
    }

    private fun requestCompletion(editor: Editor, gen: Int) {
        if (gen != generation || editor.isDisposed) return
        if (LookupManager.getActiveLookup(editor) != null) return       // IDE popup is open
        val s = LmSettings.get().state
        val provider = s.providers.firstOrNull { it.id == s.completionProviderId } ?: return

        val doc = editor.document
        val offset = editor.caretModel.offset
        val line = doc.getLineNumber(offset)
        if (doc.getText(TextRange(offset, doc.getLineEndOffset(line))).isNotBlank()) return   // end of line only
        val from = doc.getLineStartOffset(maxOf(0, line - s.ctxLinesBefore))
        val to = doc.getLineEndOffset(minOf(doc.lineCount - 1, line + s.ctxLinesAfter))
        val prefix = doc.getText(TextRange(from, offset))
        val suffix = doc.getText(TextRange(offset, to))
        if (prefix.isBlank()) return

        val stamp = doc.modificationStamp
        val fileName = FileDocumentManager.getInstance().getFile(doc)?.name ?: "untitled"
        val url = provider.baseUrl
        val providerId = provider.id
        val model = s.completionModel
        val mode = s.completionMode
        val template = FimTemplate.of(s.fimTemplate)
        val maxLines = s.maxSuggestLines
        val maxTokens = s.completionMaxTokens

        ApplicationManager.getApplication().executeOnPooledThread {
            val text = try {
                val client = OpenAiClient(url, LmSettings.apiKey(providerId))
                val raw = when (mode) {
                    "ollama" -> client.ollamaFim(model, prefix, suffix, maxTokens)
                    "fim" -> client.completeOnce(model, template.prompt(prefix, suffix), 0.0, maxTokens, template.stop)
                    else -> client.chatOnce(model, chatMessages(fileName, prefix, suffix, maxLines), 0.0, maxTokens)
                }
                clean(raw, prefix, maxLines)
            } catch (e: Exception) {
                log.warn("LM Chat completion failed: ${e.message ?: e}")
                ""
            }
            if (text.isNotEmpty()) {
                ApplicationManager.getApplication().invokeLater({
                    if (gen == generation && !editor.isDisposed && doc.modificationStamp == stamp &&
                        editor.caretModel.offset == offset && LookupManager.getActiveLookup(editor) == null
                    ) InlineGhost.show(editor, offset, text)
                }, ModalityState.NON_MODAL)
            }
        }
    }

    private fun chatMessages(file: String, prefix: String, suffix: String, maxLines: Int): List<JsonObject> {
        val system = "You are a code completion engine embedded in an IDE. The user message contains a source file " +
                "with a <CURSOR> marker. Reply with ONLY the code to insert at <CURSOR>: at most $maxLines line(s), " +
                "no explanations, no markdown fences, and do not repeat code that is already before or after the " +
                "cursor. Match the file's indentation and style."
        return listOf(msg("system", system), msg("user", "File: $file\n\n$prefix<CURSOR>$suffix"))
    }

    private fun msg(role: String, content: String) =
        JsonObject().apply { addProperty("role", role); addProperty("content", content) }

    private val thinkBlock = Regex("(?s)<think>.*?</think>")
    private val fenced = Regex("(?s)^\\s*```[a-zA-Z0-9_+-]*\\n(.*?)(\\n```.*)?$")

    private fun clean(raw: String, prefix: String, maxLines: Int): String {
        var t = raw.replace(thinkBlock, "").replace("<CURSOR>", "")
        fenced.find(t)?.let { t = it.groupValues[1] }
        t = t.trimEnd()
        val lastLine = prefix.substringAfterLast('\n')
        if (lastLine.isNotBlank() && t.startsWith(lastLine)) t = t.removePrefix(lastLine)  // model echoed the line
        val lead = if (t.startsWith("\n")) "\n" else ""
        val body = t.removePrefix("\n").lines().take(maxLines).joinToString("\n").trimEnd()
        return if (body.isBlank()) "" else lead + body
    }

    override fun dispose() {}
}