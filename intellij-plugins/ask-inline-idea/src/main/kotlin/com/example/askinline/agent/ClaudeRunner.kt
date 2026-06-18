package com.example.askinline.agent

import com.example.askinline.state.AskInlineSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Key

/** Thrown when the user cancels the run via the progress indicator. */
class RunCancelledException : RuntimeException("cancelled")

/**
 * Runs `claude -p <prompt> --output-format stream-json` and parses its NDJSON.
 * Analog of the VS Code extension's runClaude(): same args, same stream parsing.
 * Cancellation is via [ProgressIndicator] (not a CancellationToken); a deadline
 * mirrors the extension's timeoutMs.
 *
 * Blocking — call from Task.Backgroundable, never the EDT.
 */
class ClaudeRunner(private val settings: AskInlineSettings = AskInlineSettings.getInstance()) {

    fun run(
        prompt: String,
        workDir: String,
        indicator: ProgressIndicator,
        onEvent: (ActivityNode) -> Unit,
    ): ClaudeResult {
        val cmd = GeneralCommandLine(settings.claudeBin)
            .withParameters(buildArgs(prompt))
            .withWorkDirectory(workDir)
            .withCharset(Charsets.UTF_8)

        val handler = try {
            OSProcessHandler(cmd)
        } catch (e: Exception) {
            throw RuntimeException(
                "could not run \"${settings.claudeBin}\". Is the Claude Code CLI " +
                    "installed and on PATH? (${e.message})"
            )
        }

        val parser = StreamParser(onEvent)
        val stderr = StringBuilder()

        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(e: ProcessEvent, type: Key<*>) {
                when (type) {
                    ProcessOutputTypes.STDOUT -> parser.consume(e.text)
                    ProcessOutputTypes.STDERR -> stderr.append(e.text)
                }
            }
        })

        handler.startNotify()

        val deadline = System.currentTimeMillis() + settings.timeoutMs
        while (!handler.waitFor(100)) {
            if (indicator.isCanceled) {
                handler.destroyProcess()
                throw RunCancelledException()
            }
            if (System.currentTimeMillis() > deadline) {
                handler.destroyProcess()
                throw RuntimeException("Claude CLI timed out after ${settings.timeoutMs}ms")
            }
        }

        parser.flush()
        parser.error?.let { throw it }

        val exit = handler.exitCode ?: -1
        if (exit != 0) {
            throw RuntimeException(stderr.toString().trim().ifEmpty { "claude exited with code $exit" })
        }
        return parser.result ?: throw RuntimeException("Claude produced no result event")
    }

    private fun buildArgs(prompt: String): List<String> = buildList {
        add("-p"); add(prompt)
        add("--output-format"); add("stream-json")
        add("--verbose")
        if (settings.model.isNotEmpty()) { add("--model"); add(settings.model) }
        if (settings.systemPrompt.isNotEmpty()) {
            add("--append-system-prompt"); add(settings.systemPrompt)
        }
        if (settings.permissionMode.isNotEmpty()) {
            add("--permission-mode"); add(settings.permissionMode)
        }
    }
}
