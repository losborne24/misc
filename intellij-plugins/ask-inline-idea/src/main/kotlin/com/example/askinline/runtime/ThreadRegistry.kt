package com.example.askinline.runtime

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory map editor → live RuntimeThreads. The tool window reads this; the
 * editor listener fills it on open and clears it on close. Not persisted —
 * AskInlineStore is the durable source of truth.
 */
@Service(Service.Level.PROJECT)
class ThreadRegistry {
    private val byEditor = ConcurrentHashMap<Editor, MutableList<RuntimeThread>>()

    fun add(editor: Editor, rt: RuntimeThread) {
        byEditor.computeIfAbsent(editor) { mutableListOf() }.add(rt)
    }

    fun forEditor(editor: Editor): List<RuntimeThread> =
        byEditor[editor]?.toList() ?: emptyList()

    fun remove(editor: Editor, rt: RuntimeThread) {
        byEditor[editor]?.remove(rt)
    }

    fun clear(editor: Editor) {
        byEditor.remove(editor)
    }

    fun allRuntime(): List<RuntimeThread> = byEditor.values.flatten()

    companion object {
        fun getInstance(project: Project): ThreadRegistry = project.service()
    }
}
