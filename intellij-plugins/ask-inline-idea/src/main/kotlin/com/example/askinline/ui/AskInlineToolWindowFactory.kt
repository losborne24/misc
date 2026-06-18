package com.example.askinline.ui

import com.example.askinline.state.AskInlineStore
import com.example.askinline.state.ThreadState
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * Side-panel overview: files -> threads -> activity steps. Reads persisted
 * threads from the store and live activity from the runtime registry.
 *
 * Thread nodes carry their [ThreadState] as the node's user object so a
 * double-click / Enter can open the file at the thread's line (the VS Code
 * extension's revealThread).
 */
class AskInlineToolWindowFactory : ToolWindowFactory {

    /** A thread node's payload — kept so clicks can navigate to the source. */
    private class ThreadNodeData(val state: ThreadState, val label: String) {
        override fun toString(): String = label
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val root = DefaultMutableTreeNode("Ask Inline")
        val model = DefaultTreeModel(root)
        val tree = Tree(model).apply {
            isRootVisible = false
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        }

        // Double-click a thread node -> open file at its line.
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount < 2) return
                navigate(project, tree)
            }
        })
        // Enter key -> same.
        tree.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER) navigate(project, tree)
            }
        })

        val panel = JBScrollPane(tree)
        val content = toolWindow.contentManager.factory.createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        trees[project] = TreeHandle(tree, model, root, toolWindow)
        rebuild(project)
    }

    /** Open the selected thread's file and put the caret on its start line. */
    private fun navigate(project: Project, tree: Tree) {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val data = node.userObject as? ThreadNodeData ?: return
        val state = data.state

        val absPath = (project.basePath?.trimEnd('/') ?: "") + "/" + state.filePath
        val vf = LocalFileSystem.getInstance().findFileByPath(absPath) ?: return
        // OpenFileDescriptor is 0-based for line/column.
        OpenFileDescriptor(project, vf, state.startLine, 0)
            .navigate(true) // requestFocus
        FileEditorManager.getInstance(project) // ensure manager initialized
    }

    /** Rebuild the tree from current state. Files grouped, threads under each. */
    private fun rebuild(project: Project) {
        val handle = trees[project] ?: return
        val store = AskInlineStore.getInstance(project)

        handle.root.removeAllChildren()

        val allThreads = store.allThreads()
        val byFile = allThreads.groupBy { it.filePath }.toSortedMap()
        for ((path, threads) in byFile) {
            val fileNode = DefaultMutableTreeNode("$path  (${threads.size})")
            for (ts in threads.sortedBy { it.startLine }) {
                val first = ts.comments.firstOrNull()?.body ?: ""
                val label = com.example.askinline.agent.formatRange(ts.startLine, ts.endLine) +
                    ": " + com.example.askinline.agent.oneLine(first)
                fileNode.add(DefaultMutableTreeNode(ThreadNodeData(ts, label)))
            }
            handle.root.add(fileNode)
        }
        handle.model.reload()
        for (i in 0 until handle.tree.rowCount) handle.tree.expandRow(i)

        // Badge the activity-bar/stripe icon with the total comment count.
        val count = allThreads.sumOf { it.comments.size }
        handle.toolWindow.setIcon(
            if (count > 0) BadgedIcon(AllIcons.Toolwindows.ToolWindowMessages, count)
            else AllIcons.Toolwindows.ToolWindowMessages
        )
        handle.toolWindow.stripeTitle = if (count > 0) "Ask Inline ($count)" else "Ask Inline"
    }

    private class TreeHandle(
        val tree: Tree,
        val model: DefaultTreeModel,
        val root: DefaultMutableTreeNode,
        val toolWindow: ToolWindow,
    )

    /**
     * Wraps a base icon with a small red count badge in the top-right corner —
     * the activity-bar comment count. Self-painted (AWT) for cross-version
     * stability rather than depending on the platform's BadgeIcon API.
     */
    private class BadgedIcon(private val base: Icon, private val count: Int) : Icon {
        override fun getIconWidth() = base.iconWidth
        override fun getIconHeight() = base.iconHeight

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            base.paintIcon(c, g, x, y)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val text = if (count > 99) "99+" else count.toString()
            val font = JBUI.Fonts.label(8f).asBold()
            g2.font = font
            val fm = g2.getFontMetrics(font)
            val tw = fm.stringWidth(text)
            val d = JBUI.scale(11)
            val bw = maxOf(d, tw + JBUI.scale(4))
            val bx = x + iconWidth - bw
            val by = y
            g2.color = JBColor(Color(0xE5534B), Color(0xE5534B))
            g2.fillRoundRect(bx, by, bw, d, d, d)
            g2.color = Color.WHITE
            g2.drawString(text, bx + (bw - tw) / 2, by + (d + fm.ascent - fm.descent) / 2)
            g2.dispose()
        }
    }

    companion object {
        private val trees = ConcurrentHashMap<Project, TreeHandle>()

        /** Re-read state and redraw the tool window for [project], if open. */
        fun refresh(project: Project) {
            trees[project]?.let { AskInlineToolWindowFactory().rebuild(project) }
        }
    }
}
