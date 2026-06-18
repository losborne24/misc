package com.example.askinline.runtime

import com.example.askinline.state.ThreadState
import com.example.askinline.ui.ThreadController
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import javax.swing.Icon

/**
 * Adds a clickable gutter icon spanning [startLine]..[endLine] (0-based) of
 * [editor]. The highlighter auto-tracks the lines as the user edits — the
 * IntelliJ equivalent of VS Code re-anchoring a comment thread.
 *
 * GutterIconRenderer MUST implement equals/hashCode or IntelliJ dedups icons
 * (classic first-time bug: flickering/disappearing icons). We key on the
 * thread id.
 */
fun addCommentGutter(
    editor: Editor,
    startLine: Int,
    endLine: Int,
    state: ThreadState,
    onClick: (Editor, ThreadState) -> Unit,
): RangeHighlighter {
    val doc = editor.document
    val safeStart = startLine.coerceIn(0, (doc.lineCount - 1).coerceAtLeast(0))
    val safeEnd = endLine.coerceIn(safeStart, (doc.lineCount - 1).coerceAtLeast(0))

    val startOffset = doc.getLineStartOffset(safeStart)
    val endOffset = doc.getLineEndOffset(safeEnd)

    val hl = editor.markupModel.addRangeHighlighter(
        startOffset,
        endOffset,
        HighlighterLayer.ADDITIONAL_SYNTAX,
        null,
        HighlighterTargetArea.LINES_IN_RANGE,
    )
    hl.gutterIconRenderer = ThreadGutterIcon(state, editor, onClick)
    return hl
}

private class ThreadGutterIcon(
    private val state: ThreadState,
    private val editor: Editor,
    private val onClick: (Editor, ThreadState) -> Unit,
) : GutterIconRenderer() {

    override fun getIcon(): Icon = AllIcons.Toolwindows.ToolWindowMessages

    override fun getTooltipText(): String {
        val first = state.comments.firstOrNull()?.body ?: "Ask Inline thread"
        return first.replace(Regex("\\s+"), " ").trim().take(80)
    }

    override fun isNavigateAction(): Boolean = true

    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) = onClick(editor, state)
    }

    // Right-click on the gutter icon → "Delete thread".
    override fun getPopupMenuActions(): ActionGroup {
        val group = com.intellij.openapi.actionSystem.DefaultActionGroup()
        group.add(object : AnAction("Delete Thread", "Remove this Ask Inline thread", AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) {
                val project = editor.project ?: return
                ThreadController.getInstance(project).deleteThread(editor, state)
            }
        })
        return group
    }

    // Required: dedup is keyed on equals/hashCode.
    override fun equals(other: Any?): Boolean =
        other is ThreadGutterIcon && other.state.id == state.id

    override fun hashCode(): Int = state.id.hashCode()
}
