package dev.lmchat.ide.completion

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.TextRange
import com.intellij.util.Alarm
import dev.lmchat.core.completion.CompletionContext
import dev.lmchat.core.completion.CompletionEngine
import dev.lmchat.core.completion.CompletionMode
import dev.lmchat.core.completion.CompletionSettings
import dev.lmchat.core.completion.FimTemplate
import dev.lmchat.core.llm.OpenAiCompatibleClient
import dev.lmchat.ide.settings.IdeCredentials
import dev.lmchat.ide.settings.LmSettings

class CompletionStartup : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        ApplicationManager.getApplication().getService(CompletionService::class.java) // creates the service
    }
}

/** Watches typing in the editors and asks the [CompletionEngine] for a suggestion after a short pause. */
class CompletionService : Disposable {
    private val log = Logger.getInstance(CompletionService::class.java)
    private val debounce = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var requestGeneration = 0

    init {
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addDocumentListener(object : DocumentListener {
            override fun beforeDocumentChange(event: DocumentEvent) {
                if (InlineGhost.accepting) return
                EditorFactory.getInstance().getEditors(event.document).forEach { InlineGhost.clear(it) }
            }

            override fun documentChanged(event: DocumentEvent) = onDocumentChanged(event)
        }, this)
        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = InlineGhost.clearIfCaretMoved(event.editor)
        }, this)
    }

    private fun onDocumentChanged(event: DocumentEvent) {
        if (InlineGhost.accepting) return
        val state = LmSettings.get().state
        if (!state.completionEnabled || state.completionModel.isBlank()) return
        if (event.newLength == 0 || event.newLength > MAX_TYPED_LENGTH) return    // ignore deletions and big pastes
        val editor = EditorFactory.getInstance().getEditors(event.document).firstOrNull {
            it.contentComponent.hasFocus() && it.editorKind == EditorKind.MAIN_EDITOR && !it.isViewer
        } ?: return
        val generation = ++requestGeneration
        debounce.cancelAllRequests()
        debounce.addRequest({ requestCompletion(editor, generation) }, state.completionDelayMs)
    }

    private fun requestCompletion(editor: Editor, generation: Int) {
        if (generation != requestGeneration || editor.isDisposed) return
        if (LookupManager.getActiveLookup(editor) != null) return       // the IDE's own popup is open

        val state = LmSettings.get().state
        val provider = state.providers.firstOrNull { it.id == state.completionProviderId } ?: return

        val document = editor.document
        val offset = editor.caretModel.offset
        val line = document.getLineNumber(offset)
        // only suggest at the end of a line
        if (document.getText(TextRange(offset, document.getLineEndOffset(line))).isNotBlank()) return

        val from = document.getLineStartOffset(maxOf(0, line - state.ctxLinesBefore))
        val to = document.getLineEndOffset(minOf(document.lineCount - 1, line + state.ctxLinesAfter))
        val prefix = document.getText(TextRange(from, offset))
        if (prefix.isBlank()) return

        val fileName = FileDocumentManager.getInstance().getFile(document)?.name ?: "untitled"
        val context = CompletionContext(fileName, prefix, document.getText(TextRange(offset, to)))
        val settings = CompletionSettings(
            CompletionMode.fromId(state.completionMode), FimTemplate.byName(state.fimTemplate),
            state.completionModel, state.maxSuggestLines, state.completionMaxTokens
        )
        val baseUrl = provider.baseUrl
        val providerId = provider.id
        val documentStamp = document.modificationStamp

        ApplicationManager.getApplication().executeOnPooledThread {
            val suggestion = try {
                val client = OpenAiCompatibleClient(baseUrl, IdeCredentials.apiKey(providerId))
                CompletionEngine(client).suggest(context, settings)
            } catch (e: Exception) {
                log.warn("LM Chat completion failed: ${e.message ?: e}")
                ""
            }
            if (suggestion.isNotEmpty()) {
                ApplicationManager.getApplication().invokeLater({
                    val unchanged = generation == requestGeneration && !editor.isDisposed &&
                            document.modificationStamp == documentStamp && editor.caretModel.offset == offset
                    if (unchanged && LookupManager.getActiveLookup(editor) == null) {
                        InlineGhost.show(editor, offset, suggestion)
                    }
                }, ModalityState.NON_MODAL)
            }
        }
    }

    override fun dispose() {}

    private companion object {
        const val MAX_TYPED_LENGTH = 40
    }
}