package com.example.askinline.state

import com.intellij.util.xmlb.annotations.XCollection

/**
 * Persisted state beans for Ask Inline. Serialized by IntelliJ's XmlSerializer,
 * which requires public mutable fields (@JvmField), no-arg constructors, and
 * only simple types. Kotlin data classes with constructor `val`s do NOT
 * round-trip — keep these as plain mutable holders.
 */

/** One comment in a thread. Author is "You" or "Claude (model)". */
class CommentEntry {
    @JvmField var author: String = ""
    @JvmField var body: String = ""
    /** Epoch millis; 0 = unknown. Display/ordering only. */
    @JvmField var timestamp: Long = 0
}

/**
 * A thread anchored to a line range in a file. Stored as 0-based line numbers,
 * not offsets: offsets shift unpredictably across restarts when the file was
 * edited outside the IDE. Lines re-anchor more intuitively.
 */
class ThreadState {
    /** Stable id linking runtime highlighters/inlays back to this state. */
    @JvmField var id: String = ""
    /** Project-relative path, forward slashes — portable across checkouts. */
    @JvmField var filePath: String = ""
    @JvmField var startLine: Int = 0
    @JvmField var endLine: Int = 0

    @XCollection(style = XCollection.Style.v2)
    @JvmField var comments: MutableList<CommentEntry> = mutableListOf()
}

/** Root persisted object: every thread in the project. */
class AskInlineState {
    @XCollection(style = XCollection.Style.v2)
    @JvmField var threads: MutableList<ThreadState> = mutableListOf()
}
