package com.example.askinline.ui

import com.example.askinline.runtime.ThreadRegistry
import com.example.askinline.runtime.addCommentGutter
import com.example.askinline.runtime.RuntimeThread
import com.example.askinline.state.AskInlineStore
import com.example.askinline.state.ThreadState
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import java.util.UUID

/**
 * "Ask Claude Here": create a thread anchored to the current selection (or
 * caret line), then open its popup for the first comment. Editor right-click /
 * Ctrl+Alt+A. Analog of the VS Code extension's askInline.ask on a new thread.
 */
class AskAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val relPath = relPath(project, editor) ?: return

        val sel = editor.selectionModel
        val doc = editor.document
        val startLine = doc.getLineNumber(
            if (sel.hasSelection()) sel.selectionStart else editor.caretModel.offset
        )
        val endLine = doc.getLineNumber(
            if (sel.hasSelection()) sel.selectionEnd else editor.caretModel.offset
        )

        val state = ThreadState().apply {
            id = UUID.randomUUID().toString()
            filePath = relPath
            this.startLine = startLine
            this.endLine = endLine
        }
        AskInlineStore.getInstance(project).add(state)

        val controller = ThreadController.getInstance(project)
        // Gutter click toggles the inline card's collapse.
        val hl = addCommentGutter(editor, startLine, endLine, state) { ed, st ->
            controller.toggleThread(ed, st)
        }
        // Register before showThread — it resolves the RuntimeThread from the registry.
        ThreadRegistry.getInstance(project).add(editor, RuntimeThread(state, hl))

        controller.showThread(editor, state)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    private fun relPath(project: Project, editor: Editor): String? {
        val vf = editor.virtualFile ?: return null
        val base = project.basePath ?: return null
        return vf.path.removePrefix(base).removePrefix("/").ifEmpty { null }
    }
}
