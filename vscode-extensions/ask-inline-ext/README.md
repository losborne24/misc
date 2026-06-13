> ⚠️ This extension is entirely vibe-coded — written by AI and lightly reviewed.
> Treat it as experimental and review before running.

# Ask Inline (VS Code extension)

Add a **comment to any line or selected range** of any file (VS Code Comments
API). The comment is
treated as a **task**: the extension hands it to the **Claude Code CLI**
(`claude -p`), which reads the file, **edits the code** if the request calls for
a change, then replies to the thread with a summary. No API key, no SDK — reuses
your existing `claude` auth, so any teammate with the CLI installed can use it.

## Why not Copilot inline chat (⌘I)?

_(Comparison as of 13 June 2026 — Copilot moves fast, so some of this may have
changed since.)_

Copilot inline chat is well-suited for fast, previewable single edits, but it is
effectively **single-session, single-context, and ephemeral**. For more complex
workflows, Copilot shifts you to the **Chat panel or Agents**. These approaches
(similar to running Claude in a **terminal**) share a common limitation: the
conversation lives in a side panel or scrollback, **detached from the code**.

Ask Inline keeps the agent **anchored to the code**:

- Each comment is tied to a **specific line or selection**, so the question,
  Claude's response, and follow-ups remain attached to the relevant code and
  visible in context rather than buried in a separate chat interface.
- Behind it is a full **Claude Code agent** that can read across files, modify
  multiple files, and execute commands to complete tasks rather than only
  suggesting local snippets.
- Replies continue the same thread, with full context carried forward rather than
  reset each time.
- It reuses existing Claude CLI authentication and model selection, with no
  Copilot licence required.

## How it works

```
1. select line(s), "+"    →  start a comment thread on that line or range
2. type comment + submit  →  echo "You: …" into the thread
3. claude -p "<thread>"    →  subprocess acts on the task (may edit files);
                              a cancellable "Claude is working…" notification shows
4. post reply             →  "Claude (<model>): <summary>" appended to the thread
5. reply again in-thread  →  re-sends full transcript, loops to step 3
```

Design notes:

- Each `claude -p` run is **stateless** — the whole thread transcript is the
  prompt, so follow-ups carry prior context. The system prompt frames the latest
  comment as a task to carry out, not just answer.
- Runs under `--permission-mode bypassPermissions` so Claude can edit files and
  run commands without prompts. Lower it via `askInline.permissionMode`.
- Progress is a cancellable notification, not an in-thread placeholder. Cancel
  posts nothing; success appends the reply.
- The **Ask Inline** activity-bar view lists files with threads (the Comments API
  can't enumerate threads, so we track ours in a registry and prune dead ones).

## Use

1. Build the `.vsix`: `cd vscode-extensions/ask-inline-ext && yarn && yarn package`
2. Install it: **Extensions: Install from VSIX…** (command palette) → pick the
   generated `ask-inline-ext-*.vsix`, then reload the window.
3. Select one or more lines, then click the gutter **"+"** to start a comment
   thread on that line or range.
4. Type a task and click **Ask Claude** (the send icon). A *Claude is working…*
   notification appears (cancellable); the reply is appended when it finishes.
5. Use the title-bar **comment icon** to regenerate a reply on an existing thread.
6. Remove a comment with its **trash** icon, or close the whole thread with the
   **Finished** (✓) icon in the thread title bar.

## Settings

| Setting | Default | Meaning |
| --- | --- | --- |
| `askInline.claudeBin` | `claude` | Path to the Claude Code CLI binary. |
| `askInline.model` | `""` | Optional `--model` id. Empty = CLI default. |
| `askInline.systemPrompt` | task-runner prompt | Appended via `--append-system-prompt`. |
| `askInline.permissionMode` | `bypassPermissions` | `--permission-mode`. `bypassPermissions` = all tools incl. Bash, no prompts (**caution**); `acceptEdits` = file edits only (Bash denied); `plan` = answer only, no edits. |
| `askInline.timeoutMs` | `300000` | Subprocess kill timeout. Agentic runs take longer. |

## Limits

- **No visibility into what the agent is doing mid-run.** The CLI runs as an
  opaque subprocess — you see only a "working…" notification, then the final
  summary. There's no live stream of the files it reads, edits it makes, or
  commands it runs; you review the result after the fact (e.g. via the diff).
- Comments post under our own comment controller, so they live in-editor but are
  **not** persisted or synced to GitHub. They vanish when the window reloads. For
  GitHub PR sync you'd call `gh api` against the PR review comment endpoint.
- **`bypassPermissions` (the default) runs every tool — including Bash — with no
  confirmation.** Claude can write files and execute commands with your shell's
  privileges based on comment text. Only use it on a repo you trust. Set
  `permissionMode` to `acceptEdits` (edits only, no Bash) or `plan` (answer only,
  no changes) to tighten it. Review the diff after any run.
