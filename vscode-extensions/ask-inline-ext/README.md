> ⚠️ This extension is entirely vibe-coded — written by AI and lightly reviewed.
> Treat it as experimental and review before running.

# Ask Inline (VS Code extension)

Add a **comment to any line** of any file (VS Code Comments API). The comment is
treated as a **task**: the extension hands it to the **Claude Code CLI**
(`claude -p`), which reads the file, **edits the code** if the request calls for
a change, then replies to the thread with a summary. No API key, no SDK — reuses
your existing `claude` auth, so any teammate with the CLI installed can use it.

## How it works

```
1. gutter "+" on a line   →  start a comment thread
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
3. Hover the gutter next to any line and click **"+"** to start a comment thread.
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

- Comments post under our own comment controller, so they live in-editor but are
  **not** persisted or synced to GitHub. They vanish when the window reloads. For
  GitHub PR sync you'd call `gh api` against the PR review comment endpoint.
- **`bypassPermissions` (the default) runs every tool — including Bash — with no
  confirmation.** Claude can write files and execute commands with your shell's
  privileges based on comment text. Only use it on a repo you trust. Set
  `permissionMode` to `acceptEdits` (edits only, no Bash) or `plan` (answer only,
  no changes) to tighten it. Review the diff after any run.
