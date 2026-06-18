# Ask Inline — IntelliJ plugin

IntelliJ port of the [`ask-inline-ext`](../../vscode-extensions/ask-inline-ext)
VS Code extension. Comment on a line of code; the comment is handed to the
Claude Code CLI (`claude -p`) as a task — it edits the code and replies inline.

## Status

**Scaffold / v1 skeleton.** Compiles against IntelliJ Platform 2024.1. Core
flow wired (gutter → popup → run → reply, persisted threads, tool window).
Block-inlay rendering and richer markdown in the popup are TODO.

## How it maps to the VS Code extension

| VS Code piece | IntelliJ equivalent | File |
|---|---|---|
| Comments API thread anchor | `RangeHighlighter` + `GutterIconRenderer` | `runtime/Gutter.kt` |
| Comment reply input box | `JBPopup` with Swing panel | `ui/ThreadController.kt` |
| `spawn(claude -p)` | `OSProcessHandler` + `GeneralCommandLine` | `agent/ClaudeRunner.kt` |
| `StreamParser` (parse.ts) | `StreamParser` (Gson) | `agent/Parse.kt` |
| `TreeDataProvider` side panel | `ToolWindow` + `Tree` | `ui/AskInlineToolWindowFactory.kt` |
| `contributes.configuration` | `Configurable` + app settings | `ui/AskInlineConfigurable.kt`, `state/AskInlineSettings.kt` |
| in-memory `threadRegistry` | `PersistentStateComponent` (`.idea/askInline.xml`) | `state/AskInlineStore.kt` |
| — (net new) | reattach on editor open + anchor-drift sync | `runtime/AskInlineEditorListener.kt` |

## Key differences

- **Threads persist** across restarts (`.idea/askInline.xml`). The VS Code
  extension kept threads in memory and lost them on reload.
- **Gutter icon + popup** replaces the inline comment thread widget — IntelliJ
  has no Comments API. A read-only block inlay showing the latest reply is the
  planned next step (`runtime/RuntimeThread.inlay` is reserved for it).
- Same `claude` CLI, same args, same auth reuse — the agent layer is a near
  1:1 port.

## Build / run

```bash
./gradlew runIde          # launch a sandbox IDE with the plugin
./gradlew buildPlugin     # produce build/distributions/*.zip
```

Requires the `claude` CLI on PATH (configurable under **Tools > Ask Inline**).

## TODO

- [ ] Block inlay rendering of the transcript below the anchored line
- [ ] Delete-thread / regenerate actions on the gutter popup-menu
      (`GutterIconRenderer.getPopupMenuActions`)
- [ ] Markdown rendering in the popup (currently plain text)
- [ ] Re-anchor or drop a thread when its line is deleted (`hl.isValid == false`)
- [ ] Unit tests for `agent/Parse.kt` (port parse.test.ts)
- [ ] Cancel button wiring beyond the background-task indicator
