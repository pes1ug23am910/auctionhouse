import { createHash } from 'node:crypto';
export const canonical = value => JSON.stringify(value, (_, v) => v && !Array.isArray(v) && typeof v === 'object'
  ? Object.fromEntries(Object.keys(v).sort().map(k => [k, v[k]])) : v);
export const hash = value => createHash('sha256').update(typeof value === 'string' ? value : canonical(value)).digest('hex');
export function requireThat(condition, message) { if (!condition) throw new Error(message); }
export function eventOracle(frames, durable, { after = 2, through = Infinity } = {}) {
  const expected = durable.filter(e => e.aggregateVersion > after && e.aggregateVersion <= through);
  const actual = frames.filter(f => f.event === 'auction').map(f => ({ cursor: f.id, envelope: JSON.parse(f.data) }));
  const identities = new Map(expected.map(e => [e.eventId, e]));
  const seen = new Set(); let previous = after;
  const faults = [];
  for (const {cursor, envelope: e} of actual) {
    const source = identities.get(e.eventId);
    if (seen.has(e.eventId)) faults.push('duplicate');
    if (!source || canonical(source) !== canonical(e)) faults.push('identity-or-content');
    if (cursor !== `v1:${e.aggregateId}:${e.aggregateVersion}`) faults.push('cursor');
    if (e.aggregateVersion !== previous + 1) faults.push('ordering');
    previous = e.aggregateVersion; seen.add(e.eventId);
  }
  const missing = expected.filter(e => !seen.has(e.eventId)).map(e => e.eventId);
  return { passed: !faults.length && !missing.length && actual.length === expected.length,
    expected: expected.map(e => ({ eventId: e.eventId, version: e.aggregateVersion, sha256: hash(e) })),
    observed: actual.map(({envelope:e}) => ({ eventId:e.eventId, version:e.aggregateVersion, sha256:hash(e) })), missing, faults };
}
export function snapshotOracle(frame, state) {
  if (frame?.event !== 'snapshot') return false;
  const value = JSON.parse(frame.data);
  return frame.id === value.cursor && value.cursor === `v1:${state.id}:${state.version}`
    && canonical(value.auction) === canonical(state);
}
export function difference(before, after) {
  return { calls: after.calls - before.calls, executionMs: after.executionMs - before.executionMs,
    rows: after.rows - before.rows, queryIds: after.queryIds,
    scope: 'Actual PostgreSQL access-token lookup SELECT only; not all Java authorization logic or end-to-end CPU.' };
}

export function readerTerminated(message) {
  return message?.eof === true || (message?.eof === false
    && ['ConnectionResetError', 'ConnectionAbortedError', 'BrokenPipeError'].includes(message.failure));
}
