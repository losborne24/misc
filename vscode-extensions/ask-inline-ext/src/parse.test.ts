import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  StreamParser,
  toolUseToActivity,
  formatRange,
  baseName,
  oneLine,
} from './parse.ts';

test('oneLine flattens whitespace and truncates at 60 chars', () => {
  assert.equal(oneLine('  hello\n  world  '), 'hello world');
  const long = 'x'.repeat(100);
  const out = oneLine(long);
  assert.equal(out.length, 58); // 57 chars + ellipsis
  assert.ok(out.endsWith('…'));
});

test('baseName returns the last path segment for both separators', () => {
  assert.equal(baseName('/a/b/c.ts'), 'c.ts');
  assert.equal(baseName('a\\b\\c.ts'), 'c.ts');
  assert.equal(baseName('c.ts'), 'c.ts');
  assert.equal(baseName(''), '');
});

test('formatRange renders a single line or a range', () => {
  assert.equal(formatRange(4), 'line 5'); // 0-based in, 1-based out
  assert.equal(formatRange(4, 4), 'line 5');
  assert.equal(formatRange(4, 8), 'lines 5–9');
  assert.equal(formatRange(undefined), 'an unknown location');
});

test('toolUseToActivity labels file, command, and search tools', () => {
  assert.deepEqual(toolUseToActivity({ type: 'tool_use', name: 'Read', input: { file_path: '/x/y/foo.ts' } }), {
    label: 'Read foo.ts',
    icon: 'go-to-file',
    detail: '/x/y/foo.ts',
  });
  assert.deepEqual(toolUseToActivity({ type: 'tool_use', name: 'Edit', input: { file_path: '/x/bar.ts' } }), {
    label: 'Edit bar.ts',
    icon: 'edit',
    detail: '/x/bar.ts',
  });
  const bash = toolUseToActivity({ type: 'tool_use', name: 'Bash', input: { command: 'npm test' } });
  assert.equal(bash.label, 'Bash: npm test');
  assert.equal(bash.icon, 'terminal');
  const grep = toolUseToActivity({ type: 'tool_use', name: 'Grep', input: { pattern: 'TODO' } });
  assert.equal(grep.label, 'Grep: TODO');
  assert.equal(grep.icon, 'search');
});

test('toolUseToActivity falls back for unknown tools', () => {
  const out = toolUseToActivity({ type: 'tool_use', name: 'WebFetch', input: { url: 'http://x' } });
  assert.equal(out.label, 'WebFetch');
  assert.equal(out.icon, 'tools');
});

// A realistic event stream: init, a tool call, then the terminal result.
const STREAM = [
  '{"type":"system","subtype":"init"}',
  '{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"ls"}}]}}',
  '{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"/r/README.md"}}]}}',
  '{"type":"result","subtype":"success","is_error":false,"result":"done","total_cost_usd":0.12,"usage":{"output_tokens":42},"modelUsage":{"us.anthropic.claude-opus-4-8[1m]":{}}}',
].join('\n') + '\n';

test('StreamParser emits one activity per tool_use and parses the result', () => {
  const events: string[] = [];
  const p = new StreamParser((e) => events.push(e.label));
  p.consume(STREAM);
  p.flush();
  assert.deepEqual(events, ['Bash: ls', 'Read README.md']);
  assert.equal(p.error, undefined);
  assert.deepEqual(p.result, {
    text: 'done',
    model: 'us.anthropic.claude-opus-4-8[1m]',
    outputTokens: 42,
    costUsd: 0.12,
  });
});

test('StreamParser handles chunks split mid-line', () => {
  const events: string[] = [];
  const p = new StreamParser((e) => events.push(e.label));
  // Feed one byte at a time — the worst-case split.
  for (const ch of STREAM) p.consume(ch);
  p.flush();
  assert.deepEqual(events, ['Bash: ls', 'Read README.md']);
  assert.equal(p.result?.outputTokens, 42);
});

test('StreamParser drains a final unterminated line on flush', () => {
  const p = new StreamParser();
  // No trailing newline; the result line is only seen after flush().
  p.consume('{"type":"result","is_error":false,"result":"x"}');
  assert.equal(p.result === undefined, true);
  p.flush();
  assert.equal(p.result?.text, 'x');
});

test('StreamParser surfaces an error result', () => {
  const p = new StreamParser();
  p.consume('{"type":"result","is_error":true,"api_error_status":"overloaded"}\n');
  assert.ok(p.error);
  assert.equal(p.error?.message, 'overloaded');
  assert.equal(p.result, undefined);
});

test('StreamParser ignores non-JSON noise lines', () => {
  const p = new StreamParser();
  p.consume('not json\n{"type":"result","is_error":false,"result":"ok"}\n');
  assert.equal(p.result?.text, 'ok');
});
