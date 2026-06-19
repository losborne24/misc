package com.example.askinline.runtime

import com.example.askinline.state.AskInlineStore
import com.example.askinline.state.ThreadState
import com.example.askinline.ui.ThreadController
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import java.util.UUID
import javax.swing.Icon

/**
 * Shows a "+" gutter icon on the line under the mouse (GitHub-PR-plugin style),
 * clicking it starts a new comment thread there. One transient highlighter is
 * moved to follow the cursor; it is removed when the mouse leaves a valid line.
 *
 * Registered per-editor by AskInlineEditorListener. This is the discoverable
 * "hover to add" affordance — the right-click "Ask Claude Here" action stays as
 * a keyboard/menu alternative.
 */
class HoverAddGutter(
    private val editor: Editor,
    private val project: Project,
    private val relPath: String,
) : EditorMouseMotionListener {

    private var hoverHl: RangeHighlighter? = null
    private var hoverLine: Int = -1

    override fun mouseMoved(e: EditorMouseEvent) {
        val line = editor.xyToLogicalPosition(e.mouseEvent.point).line
        if (line == hoverLine && hoverHl?.isValid == true) return
        if (line < 0 || line >= editor.document.lineCount) {
            clear()
            return
        }
        // Don't show "+" on a line that already has a thread (it has its own icon).
        if (AskInlineStore.getInstance(project).threadsForFile(relPath).any { it.startLine == line }) {
            clear()
            return
        }
        clear()
        hoverLine = line
        val offset = editor.document.getLineStartOffset(line)
        hoverHl = editor.markupModel.addRangeHighlighter(
            offset, offset,
            HighlighterLayer.ADDITIONAL_SYNTAX - 1,
            null,
            HighlighterTargetArea.LINES_IN_RANGE,
        ).also { it.gutterIconRenderer = AddIcon(line) }
    }

    private fun clear() {
        hoverHl?.let { if (it.isValid) editor.markupModel.removeHighlighter(it) }
        hoverHl = null
        hoverLine = -1
    }

    private inner class AddIcon(private val line: Int) : GutterIconRenderer() {
        override fun getIcon(): Icon = AllIcons.General.InlineAdd
        override fun getTooltipText(): String = "Ask Claude on this line"
        override fun isNavigateAction(): Boolean = true

        override fun getClickAction(): AnAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) = startThread(line)
        }

        override fun equals(other: Any?): Boolean = other is AddIcon && other.line == line
        override fun hashCode(): Int = line
    }

    private fun startThread(line: Int) {
        clear()

        // If the click line falls inside an active multi-line selection, anchor
        // the thread to the whole selected range; otherwise just the one line.
        val sel = editor.selectionModel
        val doc = editor.document
        var start = line
        var end = line
        if (sel.hasSelection()) {
            val selStart = doc.getLineNumber(sel.selectionStart)
            // selectionEnd at a line start (offset == lineStart) means the line
            // isn't really included — back off one line in that case.
            val rawEnd = doc.getLineNumber(sel.selectionEnd)
            val selEnd =
                if (rawEnd > selStart && sel.selectionEnd == doc.getLineStartOffset(rawEnd)) rawEnd - 1
                else rawEnd
            if (line in selStart..selEnd) { start = selStart; end = selEnd }
        }

        val state = ThreadState().apply {
            id = UUID.randomUUID().toString()
            filePath = relPath
            startLine = start
            endLine = end
        }
        AskInlineStore.getInstance(project).add(state)

        val controller = ThreadController.getInstance(project)
        val hl = addCommentGutter(editor, start, end, state) { ed, st ->
            controller.toggleThread(ed, st)
        }
        ThreadRegistry.getInstance(project).add(editor, RuntimeThread(state, hl))
        controller.showThread(editor, state)
    }
}
