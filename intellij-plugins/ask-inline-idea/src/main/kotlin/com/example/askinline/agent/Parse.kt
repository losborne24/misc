package com.example.askinline.agent

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pure parsing/formatting helpers + the NDJSON stream parser. Port of the VS
 * Code extension's parse.ts. No IntelliJ imports here so it can be unit-tested
 * standalone (Gson is the only dependency). Icon ids are abstract codicon-style
 * names; the tool window maps them to AllIcons at render time.
 */

/** A single streamed step (one tool call). */
data class ActivityNode(val label: String, val icon: String, val detail: String? = null)

/** Reply text + usage pulled from the final `result` event. */
data class ClaudeResult(
    val text: String,
    val model: String,
    val outputTokens: Int,
    val costUsd: Double,
)

/** Collapse text to a single trimmed line, truncated for compact labels. */
fun oneLine(text: String): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    return if (flat.length > 60) flat.take(57) + "…" else flat
}

/** Last path segment, for compact activity labels. */
fun baseName(p: String): String =
    if (p.isEmpty()) "" else p.split('\\', '/').lastOrNull { it.isNotEmpty() } ?: p

/** Human label for a thread's anchor, from 0-based line numbers. */
fun formatRange(startLine0: Int?, endLine0: Int? = null): String {
    if (startLine0 == null) return "an unknown location"
    val start = startLine0 + 1
    val end = (endLine0 ?: startLine0) + 1
    return if (start == end) "line $start" else "lines $start–$end"
}

/** Safe string-field read from a JSON object (empty if missing/non-string). */
private fun JsonObject.str(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

/** Map a `tool_use` content block to a tree-friendly activity node. */
fun toolUseToActivity(c: JsonObject): ActivityNode {
    val name = c.get("name")?.asString ?: "Tool"
    val input = c.getAsJsonObject("input") ?: JsonObject()
    val file = input.str("file_path")
    val cmd = input.str("command")
    val desc = input.str("description")

    return when (name) {
        "Read", "Edit", "Write", "NotebookEdit" -> ActivityNode(
            label = "$name ${baseName(file)}",
            icon = if (name == "Read") "go-to-file" else "edit",
            detail = file,
        )
        "Bash" -> ActivityNode("Bash: ${oneLine(cmd)}", "terminal", cmd.ifEmpty { desc })
        "Grep", "Glob" -> ActivityNode(
            label = "$name: ${oneLine(input.str("pattern"))}",
            icon = "search",
            detail = input.str("pattern"),
        )
        else -> ActivityNode(name, "tools", desc.ifEmpty { input.toString().take(200) })
    }
}

/**
 * Incrementally parses the `--output-format stream-json` NDJSON stream. Feed raw
 * chunks (any size, may split lines) to [consume]; call [flush] on process close
 * to drain a final unterminated line. Each tool_use fires [onEvent]; the terminal
 * `result` event populates [result] or [error].
 */
class StreamParser(private val onEvent: ((ActivityNode) -> Unit)? = null) {
    private val buffer = StringBuilder()

    var result: ClaudeResult? = null
        private set
    var error: Throwable? = null
        private set

    fun consume(chunk: String) {
        buffer.append(chunk)
        var nl = buffer.indexOf("\n")
        while (nl != -1) {
            val line = buffer.substring(0, nl).trim()
            buffer.delete(0, nl + 1)
            if (line.isNotEmpty()) handleLine(line)
            nl = buffer.indexOf("\n")
        }
    }

    fun flush() {
        val line = buffer.toString().trim()
        buffer.setLength(0)
        if (line.isNotEmpty()) handleLine(line)
    }

    private fun handleLine(line: String) {
        val obj = try {
            JsonParser.parseString(line).asJsonObject
        } catch (_: Exception) {
            return // ignore non-JSON noise
        }
        handleEvent(obj)
    }

    private fun handleEvent(ev: JsonObject) {
        when (ev.get("type")?.asString) {
            "assistant" -> {
                val content = ev.getAsJsonObject("message")
                    ?.getAsJsonArray("content") ?: return
                for (el in content) {
                    val c = el.asJsonObject
                    if (c.get("type")?.asString == "tool_use") {
                        onEvent?.invoke(toolUseToActivity(c))
                    }
                }
            }
            "result" -> {
                if (ev.get("is_error")?.asBoolean == true) {
                    error = RuntimeException(
                        ev.get("api_error_status")?.takeIf { !it.isJsonNull }?.asString
                            ?: ev.get("result")?.asString
                            ?: "Claude reported an error"
                    )
                    return
                }
                val model = ev.getAsJsonObject("modelUsage")?.keySet()?.firstOrNull() ?: ""
                result = ClaudeResult(
                    text = ev.get("result")?.asString ?: "",
                    model = model,
                    outputTokens = ev.getAsJsonObject("usage")?.get("output_tokens")?.asInt ?: 0,
                    costUsd = ev.get("total_cost_usd")?.asDouble ?: 0.0,
                )
            }
        }
    }
}
