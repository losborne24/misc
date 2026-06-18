package com.example.askinline.ui

import com.example.askinline.state.CommentEntry
import com.example.askinline.state.ThreadState
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel

/**
 * The inline comment card: a real Swing component embedded as a block inlay
 * below the anchored line, GitHub-PR-plugin style. Replaces the old JBPopup.
 *
 * Embedding uses EditorEmbeddedComponentManager (the same API the bundled
 * GitHub plugin uses for review comments) — a focusable, clickable component,
 * not Graphics2D painting. The card is expanded by default and collapsible via
 * the gutter icon; the reply input lives inline.
 */
class InlineThreadView(
    private val editor: Editor,
    private val state: ThreadState,
    private val onAsk: (String, () -> Unit) -> Unit,
    private val onDelete: () -> Unit,
) {
    private val root = JPanel(BorderLayout())
    private val transcript = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val input = JBTextArea(2, 40).apply { lineWrap = true; wrapStyleWord = true }
    private var inlay: Inlay<*>? = null
    var collapsed = false
        private set

    init {
        buildCard()
        rebuildTranscript()
    }

    /** The embedded inlay, so callers can dispose/track it. */
    fun inlay(): Inlay<*>? = inlay

    /**
     * Embed (or re-embed) the card below the thread's start line.
     *
     * Mirrors the bundled GitHub plugin's EditorComponentInlaysManager:
     *  - wrap the card so getPreferredSize reports the editor text width (the
     *    raw component otherwise gets a degenerate size → events fall through to
     *    the editor, showing the I-beam cursor and swallowing input);
     *  - ResizePolicy.none() (the wrapper owns sizing);
     *  - relatesToPrecedingText = false.
     */
    fun show() {
        if (inlay?.isValid == true) return
        val doc = editor.document
        val line = state.startLine.coerceIn(0, (doc.lineCount - 1).coerceAtLeast(0))
        val offset = doc.getLineEndOffset(line)

        val wrapped = ComponentWrapper(root)
        inlay = EditorEmbeddedComponentManager.getInstance().addComponent(
            editor as EditorEx,
            wrapped,
            EditorEmbeddedComponentManager.Properties(
                EditorEmbeddedComponentManager.ResizePolicy.none(),
                null,                 // no custom renderer
                false,                // relatesToPrecedingText
                false,                // showAbove = false -> below the line
                0,                    // priority
                offset,
            ),
        )
    }

    /**
     * Sizes the embedded card to the editor's visible text width, capped at a
     * readable max — mirrors the GitHub plugin's ComponentWrapper. Without the
     * cap the card stretches to the full viewport ("half the screen").
     */
    private inner class ComponentWrapper(private val inner: JComponent) : BorderLayoutPanel() {
        init {
            isOpaque = false
            border = JBUI.Borders.empty()
            addToCenter(inner)
            inner.addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) =
                    dispatchEvent(ComponentEvent(inner, ComponentEvent.COMPONENT_RESIZED))
            })
        }

        override fun getPreferredSize(): Dimension {
            val ed = editor as? EditorImpl
            val visible = ed?.let {
                it.scrollPane.viewport.width - it.scrollPane.verticalScrollBar.width * 2
            } ?: 600
            // Cap so the card stays a readable column, not the whole viewport.
            // Cap to a readable column; also never exceed the visible width.
            val width = minOf(visible, MAX_CARD_WIDTH).coerceAtLeast(200)
            return Dimension(width, inner.preferredSize.height)
        }
    }

    fun dispose() {
        inlay?.let { if (it.isValid) it.dispose() }
        inlay = null
    }

    /** Toggle the card body; keep a thin header bar when collapsed. */
    fun toggleCollapsed() {
        collapsed = !collapsed
        transcript.isVisible = !collapsed
        replyRow.isVisible = !collapsed
        updateInlayHeight()
    }

    private lateinit var replyRow: JComponent

    private fun buildCard() {
        root.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            JBUI.Borders.empty(6),
        )
        root.background = editor.contentComponent.background
        // Embedded components inherit the editor's text (I-beam) cursor; reset it
        // so the card reads as UI, not editable text.
        root.cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)

        // Header: title + collapse + delete.
        val title = JBLabel(com.example.askinline.agent.formatRange(state.startLine, state.endLine))
        val collapse = iconButton(AllIcons.General.CollapseComponent, "Collapse / expand") { toggleCollapsed() }
        val del = iconButton(AllIcons.Actions.GC, "Delete thread") { onDelete() }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            add(title, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                add(collapse); add(del)
            }, BorderLayout.EAST)
        }

        transcript.isOpaque = false
        // The transcript container itself must left-align and be allowed to grow,
        // else BoxLayout caps it at preferred width and parks it on the right —
        // the symptom of output filling only the right half.
        transcript.alignmentX = Component.LEFT_ALIGNMENT
        transcript.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

        // Reply row: inline input + Ask + "+" (add as new comment).
        val ask = JButton("Ask Claude").apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        val plus = iconButton(AllIcons.General.Add, "Add comment") { submit() }
        ask.addActionListener { submit() }
        input.cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        replyRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            alignmentX = Component.LEFT_ALIGNMENT
            add(input, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                add(plus); add(ask)
            }, BorderLayout.EAST)
        }

        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(header)
            add(Box.createVerticalStrut(4))
            add(transcript)
            add(replyRow)
        }
        root.add(center, BorderLayout.CENTER)
    }

    private fun submit() {
        val text = input.text.trim()
        if (text.isEmpty()) return
        input.text = ""
        onAsk(text) {
            rebuildTranscript()
            updateInlayHeight()
        }
        rebuildTranscript()
        updateInlayHeight()
    }

    /** Redraw the comment list from current state. */
    fun rebuildTranscript() {
        transcript.removeAll()
        for (c in state.comments) {
            transcript.add(commentRow(c))
            transcript.add(Box.createVerticalStrut(6))
        }
        transcript.revalidate()
        transcript.repaint()
    }

    private fun commentRow(c: CommentEntry): JComponent {
        val isClaude = c.author.startsWith("Claude")
        val author = JBLabel("<html><b>${escape(c.author)}</b></html>").apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        // Render Claude's markdown as HTML, styled with the IDE UI font + CSS so
        // it reads like a GitHub comment, not raw terminal text. The default
        // JEditorPane kit uses a serif font with no styling — the "terminal look".
        val body = JEditorPane().apply {
            editorKit = HTMLEditorKitBuilder().build()
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.emptyLeft(8)
            cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
            // Let the HTML pane fill the row width; otherwise BoxLayout caps it at
            // its preferred size and text wraps at ~half the card.
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            text = markdownToHtml(c.body)
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = if (isClaude) JBColor.namedColor("EditorPane.background", JBColor.background())
            else JBColor.namedColor("TextField.background", JBColor.background())
            border = JBUI.Borders.empty(4)
            alignmentX = Component.LEFT_ALIGNMENT
            // Under BoxLayout a panel won't stretch past its preferred width
            // unless maximumSize allows it — without this the row fills only
            // half the card. Let width grow to the card; keep height tight.
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            add(author)
            add(body)
        }
    }

    /**
     * Markdown -> styled HTML using JetBrains' GFM engine (org.intellij.markdown,
     * bundled into the plugin jar) — the same parser the Markdown plugin uses, so
     * tables, nested lists, links, blockquotes, fenced code etc. all render with
     * full GitHub-Flavored-Markdown parity. Wrapped in CSS that forces the IDE UI
     * font so it reads like a GitHub comment, not terminal text.
     */
    private fun markdownToHtml(md: String): String {
        val flavour = GFMFlavourDescriptor()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(md)
        val bodyHtml = HtmlGenerator(md, tree, flavour).generateHtml()

        val font = UIUtil.getLabelFont()
        val fg = ColorUtil.toHtmlColor(UIUtil.getLabelForeground())
        val codeBg = ColorUtil.toHtmlColor(
            JBColor.namedColor("Editor.background", JBColor(0xF2F2F2, 0x2B2D30))
        )
        val borderCol = ColorUtil.toHtmlColor(JBColor.border())
        val css = """
            <style>
              body { font-family:'${font.family}'; font-size:${font.size}pt; color:$fg; margin:0; }
              p { margin:4px 0; }
              h1,h2,h3,h4 { margin:8px 0 4px 0; font-weight:bold; }
              h1 { font-size:${font.size + 4}pt; }
              h2 { font-size:${font.size + 2}pt; }
              h3 { font-size:${font.size + 1}pt; }
              code { font-family:monospace; background:$codeBg; padding:1px 4px; }
              pre { font-family:monospace; background:$codeBg; padding:6px 8px; margin:6px 0; }
              ul,ol { margin:4px 0 4px 18px; }
              li { margin:2px 0; }
              blockquote { margin:4px 0; padding-left:8px; border-left:3px solid $borderCol; color:#888; }
              table { border-collapse:collapse; margin:6px 0; }
              th,td { border:1px solid $borderCol; padding:3px 8px; }
              hr { border:none; border-top:1px solid $borderCol; margin:8px 0; }
              a { color:#3592c4; }
            </style>
        """.trimIndent()
        // HtmlGenerator already wraps in <body>; embed it inside our styled doc.
        return "<html><head>$css</head><body>$bodyHtml</body></html>"
    }

    private fun updateInlayHeight() {
        root.revalidate()
        root.repaint()
        // Force the inlay to re-measure the wrapper's preferred size.
        inlay?.update()
    }

    private fun iconButton(icon: javax.swing.Icon, tip: String, action: () -> Unit): JButton =
        JButton(icon).apply {
            toolTipText = tip
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(22, 22)
            addActionListener { action() }
        }

    private fun escape(s: String) = s.replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        /** Max card width in px (unscaled); keeps the card a readable column. */
        const val MAX_CARD_WIDTH = 700
    }
}
