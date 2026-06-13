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

- A `commentingRangeProvider` exposes every line of every file, so the gutter
  shows the **"+"** affordance to start a comment thread anywhere.
- Progress shows as a cancellable notification (not an in-thread placeholder).
  Cancelling posts nothing; on success the reply is appended.
- The Claude Code subprocess runs with `--permission-mode bypassPermissions`, so
  it can edit files and run commands without interactive prompts. The thread
  transcript is the prompt; the system prompt frames the comment as a task to
  carry out, not just answer. Lower it via `askInline.permissionMode`.
- Delete a single comment with the **trash** icon on the comment, or the whole
  thread with the trash icon in the thread title bar.
- The **Ask Inline** activity-bar view lists every file that has comment
  threads (grouped by file, each thread shown by line + preview). Click a thread
  to jump to it. The Comments API can't enumerate threads, so we track the ones
  we create in a registry and prune disposed ones on refresh.

## Use

1. Build it (one time): `cd tools/ask-inline-ext && yarn && yarn build`
2. Open `tools/ask-inline-ext` folder reference and press **F5** (Run Extension),
   or run the `Run Extension` launch config from the repo root.
3. In the dev host, hover the gutter next to any line and click **"+"** to start
   a comment thread.
4. Type a task and click **Ask Claude** (the send icon). A *Claude is working…*
   notification appears (cancellable); the reply is appended when it finishes.
5. Use the title-bar **comment icon** to regenerate a reply on an existing thread.
6. Remove a comment with its **trash** icon, or the whole thread with the trash
   icon in the thread title bar.

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
- Each `claude -p` run is stateless: the whole thread transcript is re-sent as
  the prompt every time, so follow-up comments carry prior context.
- **`bypassPermissions` (the default) runs every tool — including Bash — with no
  confirmation.** Claude can write files and execute commands with your shell's
  privileges based on comment text. Only use it on a repo you trust. Set
  `permissionMode` to `acceptEdits` (edits only, no Bash) or `plan` (answer only,
  no changes) to tighten it. Review the diff after any run.

## Packaging

`yarn package` (needs `@vscode/vsce`) → produces a `.vsix` you can install via
*Extensions: Install from VSIX*.
