package com.example.askinline.ui

import com.example.askinline.state.AskInlineSettings
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.JTextArea

/**
 * Settings UI under Tools > Ask Inline. Mirrors the VS Code extension's
 * contributes.configuration. Backed by the app-level AskInlineSettings.
 */
class AskInlineConfigurable : Configurable {

    private val data = AskInlineSettings.getInstance().mutable()

    private val claudeBin = JBTextField(data.claudeBin)
    private val model = JBTextField(data.model)
    private val systemPrompt = JTextArea(data.systemPrompt, 4, 40)
    private val permissionMode = ComboBox(
        arrayOf("default", "acceptEdits", "dontAsk", "bypassPermissions", "plan")
    ).apply { selectedItem = data.permissionMode }
    private val timeoutMs = JBTextField(data.timeoutMs.toString())

    override fun getDisplayName(): String = "Ask Inline"

    override fun createComponent(): JComponent = panel {
        row("Claude binary:") { cell(claudeBin) }
        row("Model (blank = CLI default):") { cell(model) }
        row("Permission mode:") { cell(permissionMode) }
        row("Idle timeout (ms):") { cell(timeoutMs) }
            .comment("Max time with no CLI output before the run is killed (not a total cap).")
        row("System prompt:") { cell(systemPrompt) }
    }

    override fun isModified(): Boolean =
        claudeBin.text != data.claudeBin ||
            model.text != data.model ||
            systemPrompt.text != data.systemPrompt ||
            permissionMode.selectedItem != data.permissionMode ||
            timeoutMs.text != data.timeoutMs.toString()

    override fun apply() {
        data.claudeBin = claudeBin.text.trim().ifEmpty { "claude" }
        data.model = model.text.trim()
        data.systemPrompt = systemPrompt.text
        data.permissionMode = permissionMode.selectedItem as String
        data.timeoutMs = timeoutMs.text.toIntOrNull() ?: 120_000
    }

    override fun reset() {
        claudeBin.text = data.claudeBin
        model.text = data.model
        systemPrompt.text = data.systemPrompt
        permissionMode.selectedItem = data.permissionMode
        timeoutMs.text = data.timeoutMs.toString()
    }
}
