package dev.lmchat.ide.completion

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler

/** Tab accepts a showing suggestion; otherwise it behaves as usual. */
class TabAcceptHandler(private val original: EditorActionHandler) : EditorActionHandler() {
    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        InlineGhost.isShowing(editor) || original.isEnabled(editor, caret, dataContext)

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        if (!InlineGhost.accept(editor)) original.execute(editor, caret, dataContext)
    }
}