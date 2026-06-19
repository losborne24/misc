package com.example.askinline.state

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Per-project store of comment threads. Survives IDE restart via
 * .idea/askInline.xml. Persistence analog of the VS Code extension's in-memory
 * `threadRegistry` Set, which lost all threads on window reload.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "AskInline",
    storages = [Storage("askInline.xml")]
)
class AskInlineStore : PersistentStateComponent<AskInlineState> {

    private var state = AskInlineState()

    override fun getState(): AskInlineState = state

    override fun loadState(loaded: AskInlineState) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    fun allThreads(): List<ThreadState> = state.threads.toList()

    fun threadsForFile(relPath: String): List<ThreadState> =
        state.threads.filter { it.filePath == relPath }

    fun add(thread: ThreadState) {
        state.threads.add(thread)
    }

    fun remove(id: String) {
        state.threads.removeIf { it.id == id }
    }

    fun find(id: String): ThreadState? = state.threads.firstOrNull { it.id == id }

    /** Persist current line range after edits shifted the anchor. */
    fun updateRange(id: String, startLine: Int, endLine: Int) {
        find(id)?.let { it.startLine = startLine; it.endLine = endLine }
    }

    companion object {
        fun getInstance(project: Project): AskInlineStore = project.service()
    }
}
