package com.example.askinline.state

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * App-level settings, mirroring the VS Code extension's contributes.configuration.
 * Defaults match ask-inline-ext package.json.
 */
@Service(Service.Level.APP)
@State(
    name = "AskInlineSettings",
    storages = [Storage("askInlineSettings.xml")]
)
class AskInlineSettings : PersistentStateComponent<AskInlineSettings.Data> {

    class Data {
        @JvmField var claudeBin: String = "claude"
        @JvmField var model: String = ""
        @JvmField var systemPrompt: String =
            "You act on code review comments as tasks. Make the requested code " +
                "change when one is called for, then reply with a short, technical " +
                "summary of what you did. No greetings or sign-offs."
        /** default | acceptEdits | dontAsk | bypassPermissions | plan */
        @JvmField var permissionMode: String = "bypassPermissions"
        @JvmField var timeoutMs: Int = 300_000
    }

    private var data = Data()

    override fun getState(): Data = data
    override fun loadState(loaded: Data) = XmlSerializerUtil.copyBean(loaded, data)

    // Convenience accessors used by ClaudeRunner.
    val claudeBin get() = data.claudeBin
    val model get() = data.model
    val systemPrompt get() = data.systemPrompt
    val permissionMode get() = data.permissionMode
    val timeoutMs get() = data.timeoutMs

    fun mutable(): Data = data

    companion object {
        fun getInstance(): AskInlineSettings =
            ApplicationManager.getApplication().getService(AskInlineSettings::class.java)
    }
}
