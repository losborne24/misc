package com.example.askinline.runtime

import com.example.askinline.agent.ActivityNode
import com.example.askinline.state.ThreadState
import com.example.askinline.ui.InlineThreadView
import com.intellij.openapi.editor.markup.RangeHighlighter

/**
 * Live, per-editor representation of one thread. Created when an editor for the
 * thread's file opens; dropped when it closes. The persisted [ThreadState] is the
 * source of truth for content; this holds the editor objects that render it.
 *
 * `view` is the inline comment card (block inlay component); the gutter icon
 * toggles its collapse. `activity` is the live agent trace for the in-flight
 * (or last) run — not persisted.
 */
class RuntimeThread(
    val state: ThreadState,
    val highlighter: RangeHighlighter,   // gutter anchor; auto-tracks edits
    var view: InlineThreadView? = null,  // inline card embedded below the line
) {
    @Volatile var running: Boolean = false
    val activity: MutableList<ActivityNode> = mutableListOf()
}
