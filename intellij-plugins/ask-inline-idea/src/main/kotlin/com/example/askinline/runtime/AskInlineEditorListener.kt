package com.example.askinline.runtime

import com.example.askinline.state.AskInlineStore
import com.example.askinline.ui.ThreadController
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project

/**
 * Rebuilds per-editor gutter icons from persisted ThreadState when an editor
 * opens, and syncs line drift back to state when it closes. The persisted store
 * is the source of truth; this binds it to live editor markup — the reattach
 * step the VS Code extension never needed (VS Code re-materialized threads).
 */
class AskInlineEditorListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        val relPath = relPath(project, editor) ?: return

        val store = AskInlineStore.getInstance(project)
        val registry = ThreadRegistry.getInstance(project)

        // "+" hover affordance to start a new thread on any line.
        editor.addEditorMouseMotionListener(HoverAddGutter(editor, project, relPath))

        val controller = ThreadController.getInstance(project)
        for (ts in store.threadsForFile(relPath)) {
            if (ts.startLine >= editor.document.lineCount) continue // file shrank
            val hl = addCommentGutter(editor, ts.startLine, ts.endLine, ts) { ed, state ->
                controller.toggleThread(ed, state)
            }
            registry.add(editor, RuntimeThread(ts, hl))
            // Embed the inline card expanded on open (GitHub-plugin behavior).
            controller.showThread(editor, ts)
        }
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        val registry = ThreadRegistry.getInstance(project)
        syncRangesToState(editor, registry)
        registry.clear(editor)
    }

    private fun relPath(project: Project, editor: Editor): String? {
        val vf = editor.virtualFile ?: return null
        val base = project.basePath ?: return null
        return vf.path.removePrefix(base).removePrefix("/").ifEmpty { null }
    }

    companion object {
        /**
         * Pull each live highlighter's current line into its ThreadState, so the
         * persisted .idea/askInline.xml stays honest after edits shifted anchors.
         * Call before persist points (editor close, after a run).
         */
        fun syncRangesToState(editor: Editor, registry: ThreadRegistry) {
            val doc = editor.document
            for (rt in registry.forEditor(editor)) {
                val hl = rt.highlighter
                if (!hl.isValid) continue
                rt.state.startLine = doc.getLineNumber(hl.startOffset)
                rt.state.endLine = doc.getLineNumber(hl.endOffset)
            }
        }
    }
}
