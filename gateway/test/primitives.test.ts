import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { ClientBuffer } from '../src/buffer.js';
import { cursor, encode, EventRing, Parser } from '../src/sse.js';
import type { Frame } from '../src/sse.js';

const frame = (version: number): Frame => ({ event: 'auction', id: 'v1:lot:' + version,
  data: JSON.stringify({ eventId: 'event-' + version, schemaVersion: 1, aggregateId: 'lot',
    aggregateVersion: version, payload: { id: 'lot', version } }) });

test('incremental SSE decoding preserves UTF-8, CRLF, multiline data and stable IDs', () => {
  const parser = new Parser();
  const data = Buffer.from(': heartbeat\r\nid: v1:lot:2\r\nevent: auction\r\ndata: café\r\ndata: second line\r\n\r\n');
  const frames: Frame[] = [];
  for (const byte of data) frames.push(...parser.feed(Uint8Array.of(byte)));
  assert.deepEqual(frames, [{ event: 'auction', id: 'v1:lot:2', data: 'café\nsecond line' }]);
  assert.equal(cursor('v1:lot:9007199254740992'), null);
  assert.equal(cursor('v1:lot:-1'), null);
});

test('frame limit applies per event and rejects an unbounded unterminated frame', () => {
  const parser = new Parser(30);
  assert.equal(parser.feed(Buffer.from('data: 1234567890\n\ndata: 1234567890\n\n')).length, 2);
  assert.throws(() => new Parser(30).feed(Buffer.from('data: ' + 'x'.repeat(40))), /SSE_FRAME_LIMIT/);
});

test('ring detects gaps and immutable-identity conflicts while ignoring old duplicate delivery', () => {
  const ring = new EventRing('v1:lot:1', 3, 4096);
  assert.equal(ring.accept(frame(2)).kind, 'accepted');
  assert.equal(ring.accept(frame(2)).kind, 'duplicate');
  assert.equal(ring.accept(frame(1)).kind, 'duplicate');
  assert.equal(ring.accept(frame(4)).kind, 'gap');
  const changed = frame(2);
  changed.data = changed.data.replace('event-2', 'different-event');
  assert.equal(ring.accept(changed).kind, 'gap');
  assert.equal(ring.accept({ ...frame(3), id: 'v2:lot:3' }).kind, 'gap');
});

test('bounded retained history replays a complete suffix or requests a snapshot', () => {
  const ring = new EventRing('v1:lot:1', 2, 4096);
  for (const version of [2, 3, 4]) ring.accept(frame(version));
  assert.equal(ring.replay('v1:lot:1', 4), null);
  assert.deepEqual(ring.replay('v1:lot:2', 4)?.map(event => event.cursor.version), [3, 4]);
  assert.equal(ring.replay('v1:lot:4', 5), null);
  assert.equal(ring.replay('v1:other:2', 4), null);
  assert.equal(ring.lastCursor, 'v1:lot:4');
});

class SlowSink extends EventEmitter {
  writableLength = 0;
  blocked = true;
  destroyed = false;
  received: string[] = [];
  write(value: string) { this.received.push(value); this.writableLength += Buffer.byteLength(value); return !this.blocked; }
  destroy() { this.destroyed = true; }
  drain() { this.writableLength = 0; this.blocked = false; this.emit('drain'); }
}

test('write(false) bounds pending events and disconnects a slow client on overflow', () => {
  const sink = new SlowSink();
  let overflows = 0;
  const writer = new ClientBuffer(sink, 2, 1024, () => overflows++);
  assert.equal(writer.send('one'), true);
  writer.send('two'); writer.send('three');
  assert.equal(writer.pendingEvents, 2);
  assert.equal(writer.send('four'), false);
  assert.equal(overflows, 1);
  assert.equal(sink.destroyed, true);
  assert.equal(writer.pendingBytes, 0);
});

test('backpressure drain preserves order and includes native writable bytes in the cap', () => {
  const sink = new SlowSink();
  const writer = new ClientBuffer(sink, 10, 30, () => {});
  writer.send('one'); writer.send('two'); writer.send('three');
  assert.deepEqual(sink.received, ['one']);
  sink.drain();
  assert.deepEqual(sink.received, ['one', 'two', 'three']);
  assert.equal(writer.pendingEvents, 0);
  sink.writableLength = 29;
  assert.equal(writer.send('xx'), false);
  assert.equal(writer.isClosed, true);
  assert.match(encode(frame(2)), /^id: v1:lot:2\nevent: auction\ndata: /);
});
