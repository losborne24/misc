import * as vscode from 'vscode';
import { spawn } from 'node:child_process';

/**
 * Ask Inline
 * -------------
 * Adds a comment-on-any-line affordance using the VS Code Comments API. A
 * comment is treated as a task: we echo the user's text, then run the Claude
 * Code CLI (`claude -p`), which may edit the file, and post its reply.
 *
 * Why a subprocess instead of the Anthropic SDK: it reuses the user's existing
 * Claude Code auth, needs no API key in settings, and scales to any team member
 * who already has the CLI installed.
 */

const USER_AUTHOR: vscode.CommentAuthorInformation = { name: 'You' };
const REPLY_AUTHOR: vscode.CommentAuthorInformation = { name: 'Claude' };

let nextCommentId = 1;

/**
 * The Comments API exposes no way to enumerate threads, so we track every
 * thread we touch here. The tree view reads this; disposed threads are pruned
 * lazily (accessing `.comments` on a dead thread throws — see `isAlive`).
 */
const threadRegistry = new Set<vscode.CommentThread>();

/** A comment we control. Carries `id` + `parent` so it can be deleted later. */
class ReplyComment implements vscode.Comment {
  mode = vscode.CommentMode.Preview;
  id = nextCommentId++;
  // Marks the comment as ours so the delete menu only shows on our comments.
  contextValue = 'askInlineComment';
  constructor(
    public body: vscode.MarkdownString,
    public author: vscode.CommentAuthorInformation,
    public parent: vscode.CommentThread
  ) {}
}

/** Single shared tree provider for the side-panel view. */
let tree: CommentsTreeProvider;

export function activate(context: vscode.ExtensionContext): void {
  const controller = vscode.comments.createCommentController(
    'askInline',
    'Ask Inline'
  );
  context.subscriptions.push(controller);

  // Allow commenting on every line of any file.
  controller.commentingRangeProvider = {
    provideCommentingRanges(document) {
      const last = document.lineCount - 1;
      return [new vscode.Range(0, 0, last, 0)];
    },
  };

  tree = new CommentsTreeProvider();
  context.subscriptions.push(
    vscode.window.registerTreeDataProvider('askInline.threads', tree)
  );

  context.subscriptions.push(
    // Submit button on the comment reply box (new thread or existing thread).
    vscode.commands.registerCommand(
      'askInline.ask',
      (reply: vscode.CommentReply) => ask(reply)
    ),
    // Title-bar button: re-ask using the latest comment already in the thread.
    vscode.commands.registerCommand(
      'askInline.replyToThread',
      (thread?: vscode.CommentThread) => replyToThread(thread)
    ),
    // Per-comment trash icon: remove one comment from its thread.
    vscode.commands.registerCommand(
      'askInline.deleteComment',
      (comment?: ReplyComment) => deleteComment(comment)
    ),
    // Thread title-bar trash icon: dispose the whole thread.
    vscode.commands.registerCommand(
      'askInline.deleteThread',
      (thread?: vscode.CommentThread) => disposeThread(thread)
    ),
    // Tree view refresh button.
    vscode.commands.registerCommand('askInline.refreshThreads', () =>
      tree.refresh()
    ),
    // Tree item click: jump to the thread's file + line.
    vscode.commands.registerCommand(
      'askInline.revealThread',
      (thread: vscode.CommentThread) => revealThread(thread)
    )
  );
}

export function deactivate(): void {
  /* controller disposed via subscriptions */
}

/** Submit handler: echo the user's text, then generate + post a reply. */
async function ask(reply: vscode.CommentReply): Promise<void> {
  const thread = reply.thread;
  const text = reply.text.trim();
  if (!text) return;

  // Echo the user's comment into the thread.
  threadRegistry.add(thread);
  setComments(thread, [
    ...thread.comments,
    new ReplyComment(new vscode.MarkdownString(text), USER_AUTHOR, thread),
  ]);
  thread.collapsibleState = vscode.CommentThreadCollapsibleState.Expanded;
  tree?.refresh();

  // Don't await: returning lets VS Code clear the reply input box. The CLI run
  // is long, so blocking here would leave the typed text sitting in the box.
  void generate(thread);
}

/** Remove a single comment from its thread. */
function deleteComment(comment?: ReplyComment): void {
  const thread = comment?.parent;
  if (!thread) return;
  setComments(
    thread,
    thread.comments.filter((c) => (c as ReplyComment).id !== comment.id)
  );
  tree?.refresh();
}

/** Dispose a thread and drop it from the registry. */
function disposeThread(thread?: vscode.CommentThread): void {
  if (!thread) return;
  threadRegistry.delete(thread);
  thread.dispose();
  tree?.refresh();
}

/** Open the thread's file and put the cursor on its line. */
async function revealThread(thread: vscode.CommentThread): Promise<void> {
  const range = thread.range ?? new vscode.Range(0, 0, 0, 0);
  await vscode.window.showTextDocument(thread.uri, {
    selection: range,
    preview: false,
  });
}

/**
 * Assign comments, swallowing the throw VS Code raises when the thread was
 * disposed mid-run (user closed it while Claude was still working).
 */
function setComments(thread: vscode.CommentThread, comments: vscode.Comment[]): boolean {
  try {
    thread.comments = comments;
    return true;
  } catch {
    return false; // thread gone — nothing to render into
  }
}

/** Title-bar handler: regenerate a reply from the thread as-is. */
async function replyToThread(thread?: vscode.CommentThread): Promise<void> {
  if (!thread || thread.comments.length === 0) {
    vscode.window.showWarningMessage('Ask Inline: no comment thread in context.');
    return;
  }
  threadRegistry.add(thread);
  await generate(thread);
}

/** Run Claude (progress shown as a cancellable notification), append the reply. */
async function generate(thread: vscode.CommentThread): Promise<void> {
  await vscode.window.withProgress(
    {
      location: vscode.ProgressLocation.Notification,
      title: 'Ask Inline: Claude is working…',
      cancellable: true,
    },
    async (_progress, token) => {
      let author = REPLY_AUTHOR;
      let body: vscode.MarkdownString;
      try {
        const prompt = buildPrompt(thread);
        const { text, model, outputTokens, costUsd } = await runClaude(
          prompt,
          token,
          thread.uri
        );
        // Name the actual model the CLI resolved to (not the config string).
        author = { name: model ? `Claude (${model})` : 'Claude' };
        body = new vscode.MarkdownString(
          (text.trim() || '_Claude returned no text._') +
            usageFooter(outputTokens, costUsd)
        );
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        if (msg === 'cancelled') return; // nothing posted on cancel
        body = new vscode.MarkdownString(`⚠️ ${msg}`);
      }
      setComments(thread, [
        ...thread.comments,
        new ReplyComment(body, author, thread),
      ]);
      tree?.refresh();
    }
  );
}

/** A small italic footer line with the run's output tokens + cost. */
function usageFooter(outputTokens: number, costUsd: number): string {
  if (!outputTokens && !costUsd) return '';
  const parts: string[] = [];
  if (outputTokens) parts.push(`${outputTokens.toLocaleString()} output tokens`);
  if (costUsd) parts.push(`$${costUsd.toFixed(4)}`);
  return `\n\n---\n_${parts.join(' · ')}_`;
}

/** Serialize a thread into a plain-text transcript for the model. */
function buildPrompt(thread: vscode.CommentThread): string {
  // Absolute path so Claude resolves the file regardless of its cwd.
  const file = thread.uri.fsPath;
  const line = thread.range ? thread.range.start.line + 1 : '?';

  const transcript = thread.comments
    .map((c) => {
      const author = c.author?.name ?? 'unknown';
      const body = typeof c.body === 'string' ? c.body : c.body.value;
      return `${author}: ${body}`;
    })
    .join('\n\n');

  return [
    `You are working on ${file}, around line ${line}.`,
    'A developer left this comment thread on that location:',
    '',
    transcript,
    '',
    'Treat the most recent comment as a task. Carry it out — read the file, ' +
      'edit the code if the request calls for a change — then reply to the ' +
      'thread. Keep the reply to a short summary of what you did (or your answer ' +
      'if no change was needed). Do not paste large diffs.',
  ].join('\n');
}

interface ClaudeResult {
  text: string;
  /** Actual resolved model id (e.g. `us.anthropic.claude-opus-4-8[1m]`), if known. */
  model: string;
  /** Output tokens the run produced, if reported. */
  outputTokens: number;
  /** Total cost in USD, if reported. */
  costUsd: number;
}

/** Spawn `claude -p <prompt> --output-format json` and parse its result. */
function runClaude(
  prompt: string,
  token: vscode.CancellationToken,
  fileUri?: vscode.Uri
): Promise<ClaudeResult> {
  const cfg = vscode.workspace.getConfiguration('askInline');
  const bin = cfg.get<string>('claudeBin', 'claude');
  const model = cfg.get<string>('model', '');
  const systemPrompt = cfg.get<string>('systemPrompt', '');
  const timeoutMs = cfg.get<number>('timeoutMs', 300_000);

  const permissionMode = cfg.get<string>('permissionMode', 'bypassPermissions');

  const args = ['-p', prompt, '--output-format', 'json'];
  if (model) args.push('--model', model);
  if (systemPrompt) args.push('--append-system-prompt', systemPrompt);
  if (permissionMode) args.push('--permission-mode', permissionMode);

  // Run Claude in the workspace folder that actually owns the commented file,
  // not workspaceFolders[0] — they differ in multi-root / wrong-window setups.
  const folder = fileUri && vscode.workspace.getWorkspaceFolder(fileUri);
  const cwd =
    folder?.uri.fsPath ?? vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;

  return new Promise<ClaudeResult>((resolve, reject) => {
    const child = spawn(bin, args, { cwd, shell: false });

    let stdout = '';
    let stderr = '';
    let settled = false;

    const cleanup = () => {
      clearTimeout(timer);
      onCancel.dispose();
    };

    const timer = setTimeout(() => {
      settled = true;
      child.kill('SIGKILL');
      cleanup();
      reject(new Error(`Claude CLI timed out after ${timeoutMs}ms`));
    }, timeoutMs);

    const onCancel = token.onCancellationRequested(() => {
      settled = true;
      child.kill('SIGTERM');
      cleanup();
      reject(new Error('cancelled'));
    });

    child.stdout.on('data', (d) => (stdout += d.toString()));
    child.stderr.on('data', (d) => (stderr += d.toString()));

    child.on('error', (e) => {
      if (settled) return;
      settled = true;
      cleanup();
      reject(
        new Error(
          `could not run "${bin}". Is the Claude Code CLI installed and on PATH? (${e.message})`
        )
      );
    });

    child.on('close', (code) => {
      if (settled) return;
      settled = true;
      cleanup();
      if (code !== 0) {
        reject(new Error(stderr.trim() || `claude exited with code ${code}`));
        return;
      }
      try {
        resolve(parseClaudeJson(stdout));
      } catch (e) {
        reject(e);
      }
    });
  });
}

/**
 * Pull the reply text and resolved model id out of `--output-format json`.
 * Throws on a CLI-reported error (exit 0 but `is_error: true`).
 */
function parseClaudeJson(stdout: string): ClaudeResult {
  let json: {
    result?: string;
    is_error?: boolean;
    api_error_status?: string | null;
    total_cost_usd?: number;
    usage?: { output_tokens?: number };
    modelUsage?: Record<string, unknown>;
  };
  try {
    json = JSON.parse(stdout);
  } catch {
    // Fall back to raw stdout if the CLI didn't emit JSON.
    return { text: stdout, model: '', outputTokens: 0, costUsd: 0 };
  }
  if (json.is_error) {
    throw new Error(
      json.api_error_status || json.result || 'Claude reported an error'
    );
  }
  // `modelUsage` is keyed by the actual resolved model id; take the first key.
  const model = json.modelUsage ? Object.keys(json.modelUsage)[0] ?? '' : '';
  return {
    text: json.result ?? '',
    model,
    outputTokens: json.usage?.output_tokens ?? 0,
    costUsd: json.total_cost_usd ?? 0,
  };
}

// ---------------------------------------------------------------------------
// Side-panel tree: files → threads with comments
// ---------------------------------------------------------------------------

/** True if the thread hasn't been disposed (dead threads throw on access). */
function isAlive(thread: vscode.CommentThread): boolean {
  try {
    return thread.comments.length > 0;
  } catch {
    return false;
  }
}

type TreeNode = FileNode | vscode.CommentThread;

/** A file grouping node, holding the threads that live in it. */
class FileNode {
  constructor(
    public readonly uri: vscode.Uri,
    public readonly threads: vscode.CommentThread[]
  ) {}
}

/** Lists files that have comment threads, expandable to the threads themselves. */
class CommentsTreeProvider implements vscode.TreeDataProvider<TreeNode> {
  private readonly _onDidChange = new vscode.EventEmitter<void>();
  readonly onDidChangeTreeData = this._onDidChange.event;

  refresh(): void {
    // Prune dead threads before re-rendering.
    for (const t of threadRegistry) if (!isAlive(t)) threadRegistry.delete(t);
    this._onDidChange.fire();
  }

  getChildren(node?: TreeNode): TreeNode[] {
    if (!node) return this.fileNodes();
    if (node instanceof FileNode) return node.threads;
    return []; // thread node is a leaf
  }

  /** Group live threads by file, sorted by path then by line. */
  private fileNodes(): FileNode[] {
    const byFile = new Map<string, vscode.CommentThread[]>();
    for (const t of threadRegistry) {
      if (!isAlive(t)) continue;
      const key = t.uri.toString();
      let bucket = byFile.get(key);
      if (!bucket) byFile.set(key, (bucket = []));
      bucket.push(t);
    }
    return [...byFile.values()]
      .map((threads) => {
        threads.sort(
          (a, b) => (a.range?.start.line ?? 0) - (b.range?.start.line ?? 0)
        );
        return new FileNode(threads[0].uri, threads);
      })
      .sort((a, b) => a.uri.fsPath.localeCompare(b.uri.fsPath));
  }

  getTreeItem(node: TreeNode): vscode.TreeItem {
    if (node instanceof FileNode) {
      const item = new vscode.TreeItem(
        vscode.workspace.asRelativePath(node.uri),
        vscode.TreeItemCollapsibleState.Expanded
      );
      item.resourceUri = node.uri;
      item.iconPath = vscode.ThemeIcon.File;
      item.description = `${node.threads.length} thread${
        node.threads.length === 1 ? '' : 's'
      }`;
      return item;
    }

    // Thread leaf: show line number + first comment as a one-line preview.
    const line = node.range ? node.range.start.line + 1 : 0;
    const first = node.comments[0];
    const body = first
      ? typeof first.body === 'string'
        ? first.body
        : first.body.value
      : '';
    const item = new vscode.TreeItem(
      `Line ${line}: ${oneLine(body)}`,
      vscode.TreeItemCollapsibleState.None
    );
    item.iconPath = new vscode.ThemeIcon('comment');
    item.tooltip = body;
    // Lets the tree's right-click / inline menu target thread nodes.
    item.contextValue = 'askInlineThreadNode';
    item.command = {
      command: 'askInline.revealThread',
      title: 'Reveal',
      arguments: [node],
    };
    return item;
  }
}

/** Collapse a comment body to a single trimmed line for the tree label. */
function oneLine(text: string): string {
  const flat = text.replace(/\s+/g, ' ').trim();
  return flat.length > 60 ? `${flat.slice(0, 57)}…` : flat;
}
