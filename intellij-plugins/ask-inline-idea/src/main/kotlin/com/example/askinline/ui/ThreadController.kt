package com.example.askinline.ui

import com.example.askinline.agent.ClaudeRunner
import com.example.askinline.agent.RunCancelledException
import com.example.askinline.agent.formatRange
import com.example.askinline.runtime.AskInlineEditorListener
import com.example.askinline.runtime.RuntimeThread
import com.example.askinline.runtime.ThreadRegistry
import com.example.askinline.state.AskInlineStore
import com.example.askinline.state.CommentEntry
import com.example.askinline.state.ThreadState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/**
 * Drives a thread's lifecycle: render the inline card, submit a comment, run
 * Claude, append the reply. Project-scoped so the editor listener, gutter, and
 * tool window share one instance.
 *
 * The view is an InlineThreadView embedded as a block inlay below the line
 * (GitHub-PR-plugin style); the gutter icon toggles its collapse.
 */
@Service(Service.Level.PROJECT)
class ThreadController(private val project: Project) {

    private val runner = ClaudeRunner()

    /**
     * Ensure an inline card exists for [state] in [editor] and is expanded.
     * Idempotent: reuses an existing view, creates one otherwise. Called on
     * thread creation, gutter click, and editor reattach.
     */
    fun showThread(editor: Editor, state: ThreadState) {
        val rt = runtimeFor(editor, state) ?: return
        val view = rt.view ?: InlineThreadView(
            editor = editor,
            state = state,
            onAsk = { text, onChange -> submit(editor, state, text, onChange) },
            onDelete = { deleteThread(editor, state) },
        ).also { rt.view = it }
        view.show()
        if (view.collapsed) view.toggleCollapsed()
    }

    /** Gutter-click handler: collapse/expand the inline card. */
    fun toggleThread(editor: Editor, state: ThreadState) {
        val rt = runtimeFor(editor, state) ?: return
        val view = rt.view
        if (view == null || view.inlay()?.isValid != true) {
            showThread(editor, state) // not embedded yet -> create + expand
        } else {
            view.toggleCollapsed()
        }
    }

    private fun runtimeFor(editor: Editor, state: ThreadState): RuntimeThread? =
        ThreadRegistry.getInstance(project).forEditor(editor)
            .firstOrNull { it.state.id == state.id }

    /**
     * Remove a thread completely: drop persisted state, remove the live gutter
     * highlighter + inline view, drop the runtime handle, refresh the tool window.
     * Analog of the VS Code extension's disposeThread.
     */
    fun deleteThread(editor: Editor, state: ThreadState) {
        AskInlineStore.getInstance(project).remove(state.id)

        val registry = ThreadRegistry.getInstance(project)
        registry.forEditor(editor).firstOrNull { it.state.id == state.id }?.let { rt ->
            if (rt.highlighter.isValid) editor.markupModel.removeHighlighter(rt.highlighter)
            rt.view?.dispose()
            registry.remove(editor, rt)
        }
        toolWindowRefresh()
    }

    /** Echo the user comment, then run Claude on a background task. */
    private fun submit(
        editor: Editor,
        state: ThreadState,
        text: String,
        onChange: () -> Unit,
    ) {
        state.comments.add(CommentEntry().apply { author = "You"; body = text })
        onChange()
        toolWindowRefresh()

        val rt = runtimeFor(editor, state)

        ProgressManager.getInstance().run(object :
            Task.Backgroundable(project, "Ask Inline: Claude is working...", true) {

            override fun run(indicator: ProgressIndicator) {
                val workDir = project.basePath ?: return
                val prompt = buildPrompt(state)
                try {
                    val result = runner.run(prompt, workDir, indicator) { ev ->
                        indicator.text = ev.label
                        // Record for the tool window + surface live in the card.
                        rt?.activity?.add(ev)
                        ApplicationManager.getApplication().invokeLater {
                            rt?.view?.setActivity(ev.label)
                        }
                        toolWindowRefresh()
                    }
                    val author = if (result.model.isNotEmpty()) "Claude (${result.model})" else "Claude"
                    val footer = usageFooter(result.outputTokens, result.costUsd)
                    val bodyText = (result.text.trim().ifEmpty { "_Claude returned no text._" }) + footer
                    state.comments.add(CommentEntry().apply { this.author = author; body = bodyText })
                } catch (_: RunCancelledException) {
                    // Nothing posted on cancel (matches the VS Code extension).
                } catch (e: Exception) {
                    state.comments.add(CommentEntry().apply {
                        author = "Claude"; body = "[error] ${e.message}"
                    })
                } finally {
                    // Persist any anchor drift caused by edits during the run.
                    val registry = ThreadRegistry.getInstance(project)
                    AskInlineEditorListener.syncRangesToState(editor, registry)
                    // Refresh the inline card on the EDT.
                    ApplicationManager.getApplication().invokeLater {
                        rt?.view?.clearActivity()
                        onChange()
                    }
                    toolWindowRefresh()
                }
            }
        })
    }

    /** Serialize the thread into a prompt. Mirrors buildPrompt() in extension.ts. */
    private fun buildPrompt(state: ThreadState): String {
        val store = AskInlineStore.getInstance(project)
        val ts = store.find(state.id) ?: state
        val file = (project.basePath?.trimEnd('/') ?: "") + "/" + ts.filePath
        val location = formatRange(ts.startLine, ts.endLine)
        val transcript = ts.comments.joinToString("\n\n") { "${it.author}: ${it.body}" }
        return buildString {
            appendLine("You are working on $file, around $location.")
            appendLine("A developer left this comment thread on that location:")
            appendLine()
            appendLine(transcript)
            appendLine()
            append(
                "Treat the most recent comment as a task. Carry it out - read the " +
                    "file, edit the code if the request calls for a change - then reply " +
                    "to the thread. Keep the reply to a short summary of what you did " +
                    "(or your answer if no change was needed). Do not paste large diffs."
            )
        }
    }

    private fun usageFooter(outputTokens: Int, costUsd: Double): String {
        if (outputTokens == 0 && costUsd == 0.0) return ""
        val parts = buildList {
            if (outputTokens != 0) add("%,d output tokens".format(outputTokens))
            if (costUsd != 0.0) add("$%.4f".format(costUsd))
        }
        return "\n\n---\n" + parts.joinToString(" - ")
    }

    private fun toolWindowRefresh() {
        ApplicationManager.getApplication().invokeLater {
            AskInlineToolWindowFactory.refresh(project)
        }
    }

    companion object {
        fun getInstance(project: Project): ThreadController = project.service()
    }
}
