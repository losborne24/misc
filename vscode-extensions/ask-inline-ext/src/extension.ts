import * as vscode from 'vscode';
import { spawn } from 'node:child_process';
import {
  ActivityNode,
  ClaudeResult,
  StreamParser,
  formatRange as formatLineRange,
  oneLine,
} from './parse';

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

/**
 * In-flight Claude runs, keyed by the thread they post into. Lets us cancel the
 * subprocess when the thread/comment is deleted mid-run (not only via the
 * progress notification's Cancel button).
 */
const runningRuns = new Map<vscode.CommentThread, vscode.CancellationTokenSource>();

/** Cancel an in-flight run for a thread, if one exists. */
function cancelRun(thread: vscode.CommentThread): void {
  runningRuns.get(thread)?.cancel();
}

/**
 * Live activity for each thread's run: the agent runs as an opaque subprocess,
 * so we parse its streamed events and surface each tool call as a child node in
 * the side-panel tree. `running` drives the spinner icon; `activity` persists
 * after the run so the trace stays reviewable.
 */
interface ThreadRun {
  running: boolean;
  activity: ActivityNode[];
}
const runState = new Map<vscode.CommentThread, ThreadRun>();

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
/** Handle to the view, used to set the activity-bar badge. */
let treeView: vscode.TreeView<TreeNode>;

/**
 * Coalesce tree refreshes: streamed events can arrive in bursts, and firing the
 * tree's change event per event thrashes the view. Batch to one refresh per tick.
 */
let refreshQueued = false;
function scheduleRefresh(): void {
  if (refreshQueued) return;
  refreshQueued = true;
  setTimeout(() => {
    refreshQueued = false;
    tree?.refresh();
  }, 80);
}

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
  // createTreeView (vs registerTreeDataProvider) gives us a handle to set the
  // activity-bar badge with the live comment count.
  treeView = vscode.window.createTreeView('askInline.threads', {
    treeDataProvider: tree,
  });
  context.subscriptions.push(treeView);
  tree.updateBadge();

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
  const remaining = thread.comments.filter(
    (c) => (c as ReplyComment).id !== comment.id
  );
  // Emptying the thread implicitly ends it — cancel any run posting into it.
  if (remaining.length === 0) cancelRun(thread);
  setComments(thread, remaining);
  tree?.refresh();
}

/** Dispose a thread and drop it from the registry. */
function disposeThread(thread?: vscode.CommentThread): void {
  if (!thread) return;
  cancelRun(thread); // kill any in-flight Claude run before the thread goes away
  threadRegistry.delete(thread);
  runState.delete(thread);
  thread.dispose();
  tree?.refresh();
}

/** Open the thread's file, expand the thread, and put the cursor on its line. */
async function revealThread(thread: vscode.CommentThread): Promise<void> {
  const range = thread.range ?? new vscode.Range(0, 0, 0, 0);
  // Expand the thread so its comments show without a second click.
  thread.collapsibleState = vscode.CommentThreadCollapsibleState.Expanded;
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
    async (progress, token) => {
      // Cancel either via the notification's button or when the thread/comment
      // is deleted (deleteComment/disposeThread call cancelRun).
      const source = new vscode.CancellationTokenSource();
      token.onCancellationRequested(() => source.cancel());
      runningRuns.get(thread)?.cancel(); // supersede any prior run on this thread
      runningRuns.set(thread, source);

      // Fresh activity log for this run; the tree renders it under the thread.
      const run: ThreadRun = { running: true, activity: [] };
      runState.set(thread, run);
      tree?.refresh();

      // Each streamed tool call becomes a tree child and the notification's
      // current-action line. Coalesced refreshes keep the tree from thrashing.
      const onEvent = (ev: ActivityNode) => {
        if (runState.get(thread) !== run) return; // superseded by a newer run
        run.activity.push(ev);
        progress.report({ message: ev.label });
        scheduleRefresh();
      };

      let author = REPLY_AUTHOR;
      let body: vscode.MarkdownString;
      try {
        const prompt = buildPrompt(thread);
        const { text, model, outputTokens, costUsd } = await runClaude(
          prompt,
          source.token,
          thread.uri,
          onEvent
        );
        // Name the actual model the CLI resolved to (not the config string).
        author = { name: model ? `Claude (${model})` : 'Claude' };
        body = new vscode.MarkdownString(
          (text.trim() || '_Claude returned no text._') +
            usageFooter(outputTokens, costUsd)
        );
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        if (msg === 'cancelled') {
          // Drop the partial activity log; nothing is posted on cancel.
          if (runState.get(thread) === run) runState.delete(thread);
          tree?.refresh();
          return;
        }
        body = new vscode.MarkdownString(`⚠️ ${msg}`);
      } finally {
        // Only clear if we're still the active run (a newer run may have replaced us).
        if (runningRuns.get(thread) === source) runningRuns.delete(thread);
        source.dispose();
        run.running = false; // stop the spinner; keep the trace for review
        tree?.refresh();
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

/** Human label for a thread's anchor: "line 5" or "lines 5–9" (1-based). */
function formatRange(range?: vscode.Range): string {
  return formatLineRange(range?.start.line, range?.end.line);
}

/** Serialize a thread into a plain-text transcript for the model. */
function buildPrompt(thread: vscode.CommentThread): string {
  // Absolute path so Claude resolves the file regardless of its cwd.
  const file = thread.uri.fsPath;
  const location = formatRange(thread.range);

  const transcript = thread.comments
    .map((c) => {
      const author = c.author?.name ?? 'unknown';
      const body = typeof c.body === 'string' ? c.body : c.body.value;
      return `${author}: ${body}`;
    })
    .join('\n\n');

  return [
    `You are working on ${file}, around ${location}.`,
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

/**
 * Spawn `claude -p <prompt> --output-format stream-json` and parse its NDJSON
 * event stream. `onEvent` fires once per agent tool call so callers can render
 * live activity; the final `result` event yields the reply + usage.
 */
function runClaude(
  prompt: string,
  token: vscode.CancellationToken,
  fileUri?: vscode.Uri,
  onEvent?: (ev: ActivityNode) => void
): Promise<ClaudeResult> {
  const cfg = vscode.workspace.getConfiguration('askInline');
  const bin = cfg.get<string>('claudeBin', 'claude');
  const model = cfg.get<string>('model', '');
  const systemPrompt = cfg.get<string>('systemPrompt', '');
  const timeoutMs = cfg.get<number>('timeoutMs', 300_000);

  const permissionMode = cfg.get<string>('permissionMode', 'bypassPermissions');

  // stream-json emits one JSON object per line as the run progresses; --verbose
  // is required for it to include the intermediate assistant/tool_use events.
  const args = [
    '-p',
    prompt,
    '--output-format',
    'stream-json',
    '--verbose',
  ];
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

    const parser = new StreamParser(onEvent);
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

    child.stdout.on('data', (d) => parser.consume(d.toString()));
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
      parser.flush(); // drain a final unterminated line
      if (parser.error) {
        reject(parser.error);
        return;
      }
      if (code !== 0) {
        reject(new Error(stderr.trim() || `claude exited with code ${code}`));
        return;
      }
      if (parser.result) {
        resolve(parser.result);
        return;
      }
      reject(new Error('Claude produced no result event'));
    });
  });
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

type TreeNode = FileNode | vscode.CommentThread | ActivityLeaf;

/** A file grouping node, holding the threads that live in it. */
class FileNode {
  constructor(
    public readonly uri: vscode.Uri,
    public readonly threads: vscode.CommentThread[]
  ) {}
}

/** A streamed activity step shown as a child under a running/finished thread. */
class ActivityLeaf {
  constructor(public readonly event: ActivityNode) {}
}

/** Lists files that have comment threads, expandable to the threads themselves. */
class CommentsTreeProvider implements vscode.TreeDataProvider<TreeNode> {
  private readonly _onDidChange = new vscode.EventEmitter<void>();
  readonly onDidChangeTreeData = this._onDidChange.event;

  refresh(): void {
    // Prune dead threads (and their run state) before re-rendering.
    for (const t of threadRegistry)
      if (!isAlive(t)) {
        threadRegistry.delete(t);
        runState.delete(t);
      }
    this.updateBadge();
    this._onDidChange.fire();
  }

  /** Set the activity-bar badge to the total comment count across live threads. */
  updateBadge(): void {
    if (!treeView) return;
    let count = 0;
    for (const t of threadRegistry) if (isAlive(t)) count += t.comments.length;
    treeView.badge = count
      ? { value: count, tooltip: `${count} comment${count === 1 ? '' : 's'}` }
      : undefined;
  }

  getChildren(node?: TreeNode): TreeNode[] {
    if (!node) return this.fileNodes();
    if (node instanceof FileNode) return node.threads;
    if (node instanceof ActivityLeaf) return []; // activity is a leaf
    // Thread node: children are its run's activity steps, if any.
    const run = runState.get(node);
    return run ? run.activity.map((e) => new ActivityLeaf(e)) : [];
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
    if (node instanceof ActivityLeaf) {
      const item = new vscode.TreeItem(
        node.event.label,
        vscode.TreeItemCollapsibleState.None
      );
      item.iconPath = new vscode.ThemeIcon(node.event.icon);
      item.tooltip = node.event.detail ?? node.event.label;
      return item;
    }

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

    // Thread leaf: show line/range + first comment as a one-line preview.
    const first = node.comments[0];
    const body = first
      ? typeof first.body === 'string'
        ? first.body
        : first.body.value
      : '';
    const label = formatRange(node.range);
    const run = runState.get(node);
    // Expand the run's activity when present; auto-expand while it's live.
    const collapsible = run?.activity.length
      ? run.running
        ? vscode.TreeItemCollapsibleState.Expanded
        : vscode.TreeItemCollapsibleState.Collapsed
      : vscode.TreeItemCollapsibleState.None;
    const item = new vscode.TreeItem(
      `${label.charAt(0).toUpperCase()}${label.slice(1)}: ${oneLine(body)}`,
      collapsible
    );
    // Spinner while running, comment glyph otherwise.
    item.iconPath = new vscode.ThemeIcon(run?.running ? 'loading~spin' : 'comment');
    if (run?.running) item.description = 'running…';
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
