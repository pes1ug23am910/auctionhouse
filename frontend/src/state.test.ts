import { describe, expect, it } from 'vitest';
import type { Auction, AuctionEvent, BidOutcome, Intent } from './types';
import { initialView, MAX_AMOUNT, minimumBid, parseAmount, pendingIntent, reducer, saveIntent } from './state';

const auction: Auction = { id: 'lot-1', ownerId: 'seller', title: 'Ceramic vessel', description: 'Handmade',
  openingPriceMinor: 100, minimumIncrementMinor: 10, endsAt: '2030-01-01T00:00:00Z',
  status: 'OPEN', highestBidAmountMinor: null, highestBidderId: null, version: 1, createdAt: '2026-01-01T00:00:00Z' };
const intent: Intent = { actorId: 'bidder', auctionId: auction.id, key: 'intent-1', amountMinor: 100, phase: 'pending', updatedAt: '2026-01-01T00:00:00Z' };
const outcome: BidOutcome = { actorId: 'bidder', auctionId: auction.id, key: intent.key, amountMinor: 100, accepted: true, rejection: null, bidId: 'bid-1', auctionVersion: 2, decidedAt: '2026-01-01T00:01:00Z' };
const snapshotState = () => reducer(initialView, { type: 'snapshot', snapshot: { auction, cursor: 'v1:lot-1:1' } });
const event = (version: number): AuctionEvent => ({ eventId: 'event-' + version, eventType: 'bid.accepted', schemaVersion: 1, aggregateId: auction.id, aggregateVersion: version, occurredAt: '2026-01-01T00:01:00Z', payload: { ...auction, version, highestBidAmountMinor: 100, highestBidderId: 'bidder' } });

class MemoryStorage implements Storage {
  private values = new Map<string, string>();
  get length() { return this.values.size; }
  clear() { this.values.clear(); }
  getItem(key: string) { return this.values.get(key) ?? null; }
  key(index: number) { return [...this.values.keys()][index] ?? null; }
  removeItem(key: string) { this.values.delete(key); }
  setItem(key: string, value: string) { this.values.set(key, value); }
}

describe('exact bidding units', () => {
  it('accepts exact bounded integers and rejects floats, coercions and oversized input', () => {
    expect(parseAmount('100')).toBe(100);
    expect(parseAmount(String(MAX_AMOUNT))).toBe(MAX_AMOUNT);
    for (const value of ['1.1', '1e3', ' 100', '+100', '0', '-1', '01', '9000000000000001']) expect(parseAmount(value)).toBeNull();
  });
  it('uses opening price before any bid and checks increment overflow after acceptance', () => {
    expect(minimumBid(auction)).toBe(100);
    expect(minimumBid({ ...auction, highestBidAmountMinor: 250 })).toBe(260);
    expect(minimumBid({ ...auction, highestBidAmountMinor: MAX_AMOUNT })).toBeNull();
  });
});

describe('server-confirmed optimistic state', () => {
  it('does not change current price for pending or accepted local outcomes', () => {
    const pending = reducer(snapshotState(), { type: 'intent', intent });
    expect(pending.auction?.highestBidAmountMinor).toBeNull();
    const accepted = reducer(pending, { type: 'outcome', outcome });
    expect(accepted.intent?.phase).toBe('accepted');
    expect(accepted.auction?.highestBidAmountMinor).toBeNull();
    const committed = reducer(accepted, { type: 'event', event: event(2), cursor: 'v1:lot-1:2' });
    expect(committed.auction?.highestBidAmountMinor).toBe(100);
    expect(committed.cursor).toBe('v1:lot-1:2');
  });
  it('keeps ambiguous outcomes unresolved and ignores another actor or changed payload', () => {
    const pending = reducer(snapshotState(), { type: 'intent', intent });
    expect(reducer(pending, { type: 'unknown' }).intent?.phase).toBe('unknown');
    expect(reducer(pending, { type: 'outcome', outcome: { ...outcome, actorId: 'other' } })).toBe(pending);
    expect(reducer(pending, { type: 'outcome', outcome: { ...outcome, amountMinor: 101 } })).toBe(pending);
  });
  it('records explicit server rejection without fabricating a bid', () => {
    const pending = reducer(snapshotState(), { type: 'intent', intent });
    const rejected = reducer(pending, { type: 'outcome', outcome: { ...outcome, accepted: false, rejection: 'CLOSED', bidId: null } });
    expect(rejected.intent?.phase).toBe('rejected');
    expect(rejected.auction).toBe(auction);
  });
});

describe('stream recovery', () => {
  it('detects a missing transition and applies snapshot and cursor together', () => {
    const gap = reducer(snapshotState(), { type: 'event', event: event(3), cursor: 'v1:lot-1:3' });
    expect(gap.needsSnapshot).toBe(true);
    expect(gap.auction?.version).toBe(1);
    expect(gap.cursor).toBe('v1:lot-1:1');
    const recovered = reducer(gap, { type: 'snapshot', snapshot: { auction: event(3).payload, cursor: 'v1:lot-1:3' } });
    expect(recovered.needsSnapshot).toBe(false);
    expect(recovered.auction?.version).toBe(3);
    expect(recovered.cursor).toBe('v1:lot-1:3');
  });
  it('ignores duplicate and older delivery without rolling back newer state', () => {
    const current = reducer(snapshotState(), { type: 'event', event: event(2), cursor: 'v1:lot-1:2' });
    expect(reducer(current, { type: 'event', event: event(2), cursor: 'v1:lot-1:2' })).toBe(current);
    expect(reducer(current, { type: 'event', event: event(1), cursor: 'v1:lot-1:1' })).toBe(current);
    expect(reducer(current, { type: 'snapshot', snapshot: { auction, cursor: 'v1:lot-1:1' } })).toBe(current);
  });
  it('requires recovery for another aggregate or incompatible schema', () => {
    expect(reducer(snapshotState(), { type: 'event', event: { ...event(2), aggregateId: 'other' }, cursor: 'v1:other:2' }).needsSnapshot).toBe(true);
    expect(reducer(snapshotState(), { type: 'event', event: { ...event(2), schemaVersion: 2 }, cursor: 'v1:lot-1:2' }).needsSnapshot).toBe(true);
  });
});

describe('persisted intent ownership', () => {
  it('restores the same key only for its actor and auction', () => {
    const storage = new MemoryStorage();
    saveIntent(intent, storage);
    expect(pendingIntent('bidder', 'lot-1', storage)?.key).toBe(intent.key);
    expect(pendingIntent('other', 'lot-1', storage)).toBeNull();
    expect(pendingIntent('bidder', 'lot-2', storage)).toBeNull();
    saveIntent({ ...intent, phase: 'accepted', outcome }, storage);
    expect(pendingIntent('bidder', 'lot-1', storage)).toBeNull();
  });
  it('preserves multiple unresolved identities rather than overwriting them', () => {
    const storage = new MemoryStorage();
    saveIntent(intent, storage);
    saveIntent({ ...intent, key: 'intent-2', updatedAt: '2026-01-02T00:00:00Z' }, storage);
    expect(storage.length).toBe(2);
    expect(pendingIntent('bidder', 'lot-1', storage)?.key).toBe('intent-1');
  });
});


describe('expired outcome retention', () => {
  it('retains an expired identity so the same intent cannot be replaced with a fresh key', () => {
    const storage = new MemoryStorage();
    saveIntent({ ...intent, phase: 'expired' }, storage);
    expect(pendingIntent('bidder', 'lot-1', storage)?.phase).toBe('expired');
    expect(pendingIntent('bidder', 'lot-1', storage)?.key).toBe(intent.key);
  });
});
