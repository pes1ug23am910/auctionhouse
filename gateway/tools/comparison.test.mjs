import test from 'node:test';
import assert from 'node:assert/strict';
import { Observation, summarize } from './comparison-metrics.mjs';

const auction = '11111111-1111-1111-1111-111111111111';
function frame(id, version) {
  return { event: 'auction', id: 'v1:' + auction + ':' + version,
    data: JSON.stringify({ eventId: id, aggregateId: auction, aggregateVersion: version, schemaVersion: 1,
      payload: { id: auction, version } }) };
}
const expected = [{ version: 3, postStartedMs: 100, postFinishedMs: 150 }];
test('paired observations compare identical IDs on one collector clock', () => {
  const direct = new Observation('java', 0, auction, 2);
  const proxied = new Observation('gateway', 0, auction, 2);
  direct.record(frame('one', 3), 130); proxied.record(frame('one', 3), 155);
  const result = summarize([direct, proxied], expected);
  assert.equal(result.clean, true);
  assert.equal(result.pairedArrivalDifferenceMs.p50, 25);
  assert.equal(result.clients[0].postStartToArrivalMs.p50, 30);
  assert.equal(result.clients[0].samples[0].postResponseToArrivalMs, -20);
});
test('missing clients and conflicting event identity suppress distributions', () => {
  const direct = new Observation('java', 0, auction, 2);
  const proxied = new Observation('gateway', 0, auction, 2);
  direct.record(frame('one', 3), 130);
  assert.equal(summarize([direct, proxied], expected).pairedArrivalDifferenceMs, null);
  proxied.record(frame('different', 3), 135);
  const result = summarize([direct, proxied], expected);
  assert.deepEqual(result.identityMismatches, [3]);
  assert.equal(result.clean, false);
  assert.equal(result.pairedArrivalDifferenceMs, null);
});
test('duplicate, changed identity and out-of-order frames remain visible', () => {
  const observer = new Observation('java', 0, auction, 2);
  observer.record(frame('one', 3), 110);
  observer.record(frame('one', 3), 120);
  observer.record(frame('one', 4), 130);
  observer.record(frame('two', 2), 140);
  assert.equal(observer.diagnostics.duplicates, 2);
  assert.equal(observer.diagnostics.conflictingIdentities, 1);
  assert.equal(observer.diagnostics.outOfOrder, 1);
  assert.equal(summarize([observer], expected).clean, false);
});
test('gaps, malformed cursors and explicit recovery are not hidden by deduplication', () => {
  const observer = new Observation('java', 0, auction, 2);
  observer.record(frame('two', 4), 110);
  observer.record({ ...frame('three', 5), id: 'v1:foreign:5' }, 120);
  observer.record({ event: 'gap', data: '{}' }, 130);
  observer.record({ event: 'snapshot', data: '{}' }, 140);
  assert.equal(observer.diagnostics.gaps, 1);
  assert.equal(observer.diagnostics.malformed, 1);
  assert.equal(observer.diagnostics.gapFrames, 1);
  assert.equal(observer.diagnostics.snapshots, 1);
});
