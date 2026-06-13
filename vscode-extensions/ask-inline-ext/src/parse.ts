/**
 * Pure parsing/formatting helpers, free of any `vscode` import so they can be
 * unit-tested in plain Node. `extension.ts` wires these into the VS Code APIs.
 */

/** A single streamed step in a run (one tool call). */
export interface ActivityNode {
  /** One-line label, e.g. "Edit foo.ts" or "Bash: npm test". */
  label: string;
  /** Codicon id for the leaf, chosen by tool. */
  icon: string;
  /** Longer detail for the tooltip (full path, full command). */
  detail?: string;
}

/** Reply text + usage pulled from the final `result` event. */
export interface ClaudeResult {
  text: string;
  /** Actual resolved model id (e.g. `us.anthropic.claude-opus-4-8[1m]`), if known. */
  model: string;
  /** Output tokens the run produced, if reported. */
  outputTokens: number;
  /** Total cost in USD, if reported. */
  costUsd: number;
}

/** Minimal shape of the stream-json events we read (others are ignored). */
export interface StreamEvent {
  type: string;
  is_error?: boolean;
  result?: string;
  api_error_status?: string | null;
  total_cost_usd?: number;
  usage?: { output_tokens?: number };
  modelUsage?: Record<string, unknown>;
  message?: { content?: ToolUseContent[] };
}

export interface ToolUseContent {
  type: string;
  name?: string;
  input?: Record<string, unknown>;
}

/** Collapse text to a single trimmed line, truncated for compact labels. */
export function oneLine(text: string): string {
  const flat = String(text).replace(/\s+/g, ' ').trim();
  return flat.length > 60 ? `${flat.slice(0, 57)}…` : flat;
}

/** Last path segment, for compact activity labels. */
export function baseName(p: string): string {
  if (!p) return '';
  const parts = p.split(/[\\/]/);
  return parts[parts.length - 1] || p;
}

/** Human label for a thread's anchor, from 0-based line numbers. */
export function formatRange(startLine0?: number, endLine0?: number): string {
  if (startLine0 == null) return 'an unknown location';
  const start = startLine0 + 1;
  const end = (endLine0 ?? startLine0) + 1;
  return start === end ? `line ${start}` : `lines ${start}–${end}`;
}

/** Map a `tool_use` content block to a tree-friendly activity node. */
export function toolUseToActivity(c: ToolUseContent): ActivityNode {
  const name = c.name ?? 'Tool';
  const input = c.input ?? {};
  const file = typeof input.file_path === 'string' ? input.file_path : '';
  const cmd = typeof input.command === 'string' ? input.command : '';
  const desc = typeof input.description === 'string' ? input.description : '';

  // File-oriented tools: show the basename; commands: show the command itself.
  switch (name) {
    case 'Read':
    case 'Edit':
    case 'Write':
    case 'NotebookEdit':
      return {
        label: `${name} ${baseName(file)}`,
        icon: name === 'Read' ? 'go-to-file' : 'edit',
        detail: file,
      };
    case 'Bash':
      return { label: `Bash: ${oneLine(cmd)}`, icon: 'terminal', detail: cmd || desc };
    case 'Grep':
    case 'Glob':
      return {
        label: `${name}: ${oneLine(String(input.pattern ?? ''))}`,
        icon: 'search',
        detail: String(input.pattern ?? ''),
      };
    default:
      return { label: name, icon: 'tools', detail: desc || JSON.stringify(input).slice(0, 200) };
  }
}

/**
 * Incrementally parses the `--output-format stream-json` NDJSON stream. Feed
 * raw chunks (any size, may split lines) to `consume`; call `flush` once the
 * process closes to drain a final unterminated line. Each agent tool call fires
 * `onEvent`; the terminal `result` event populates `result` or `error`.
 */
export class StreamParser {
  private buffer = '';
  /** Set when the final `result` event arrives without error. */
  result?: ClaudeResult;
  /** Set when the `result` event reports an error. */
  error?: Error;

  private readonly onEvent?: (ev: ActivityNode) => void;

  constructor(onEvent?: (ev: ActivityNode) => void) {
    this.onEvent = onEvent;
  }

  /** Feed a chunk of stdout; parses every complete line, buffers the rest. */
  consume(chunk: string): void {
    this.buffer += chunk;
    let nl: number;
    while ((nl = this.buffer.indexOf('\n')) !== -1) {
      const line = this.buffer.slice(0, nl).trim();
      this.buffer = this.buffer.slice(nl + 1);
      if (line) this.handleLine(line);
    }
  }

  /** Drain any buffered final line that wasn't newline-terminated. */
  flush(): void {
    const line = this.buffer.trim();
    this.buffer = '';
    if (line) this.handleLine(line);
  }

  private handleLine(line: string): void {
    let ev: StreamEvent;
    try {
      ev = JSON.parse(line);
    } catch {
      return; // ignore non-JSON noise
    }
    this.handleEvent(ev);
  }

  private handleEvent(ev: StreamEvent): void {
    if (ev.type === 'assistant') {
      for (const c of ev.message?.content ?? []) {
        if (c.type === 'tool_use') this.onEvent?.(toolUseToActivity(c));
      }
    } else if (ev.type === 'result') {
      if (ev.is_error) {
        this.error = new Error(
          ev.api_error_status || ev.result || 'Claude reported an error'
        );
        return;
      }
      const m = ev.modelUsage ? Object.keys(ev.modelUsage)[0] ?? '' : '';
      this.result = {
        text: ev.result ?? '',
        model: m,
        outputTokens: ev.usage?.output_tokens ?? 0,
        costUsd: ev.total_cost_usd ?? 0,
      };
    }
  }
}
