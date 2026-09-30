import type { Auction, AuctionEvent, BidOutcome, Intent, Snapshot } from './types';

export const MAX_AMOUNT = 9_000_000_000_000_000;

export function parseAmount(value: string): number | null {
  if (!/^[1-9][0-9]*$/.test(value)) return null;
  const amount = Number(value);
  return Number.isSafeInteger(amount) && amount <= MAX_AMOUNT ? amount : null;
}

export function minimumBid(auction: Auction): number | null {
  const minimum = auction.highestBidAmountMinor === null
    ? auction.openingPriceMinor : auction.highestBidAmountMinor + auction.minimumIncrementMinor;
  return Number.isSafeInteger(minimum) && minimum <= MAX_AMOUNT ? minimum : null;
}

export interface ViewState {
  auction: Auction | null;
  cursor: string | null;
  intent: Intent | null;
  needsSnapshot: boolean;
}
export type Action =
  | { type: 'snapshot'; snapshot: Snapshot }
  | { type: 'event'; event: AuctionEvent; cursor: string }
  | { type: 'intent'; intent: Intent }
  | { type: 'outcome'; outcome: BidOutcome }
  | { type: 'unknown' }
  | { type: 'reset' };

export const initialView: ViewState = { auction: null, cursor: null, intent: null, needsSnapshot: false };

function cursorParts(cursor: string): { generation: string; id: string; version: number } | null {
  const parts = cursor.split(':');
  if (parts.length !== 3 || !/^v[1-9][0-9]*$/.test(parts[0]) || !/^[0-9]+$/.test(parts[2])) return null;
  const version = Number(parts[2]);
  return Number.isSafeInteger(version) ? { generation: parts[0], id: parts[1], version } : null;
}

export function reducer(state: ViewState, action: Action): ViewState {
  switch (action.type) {
    case 'reset': return initialView;
    case 'snapshot': {
      const incoming = cursorParts(action.snapshot.cursor);
      const previous = state.cursor ? cursorParts(state.cursor) : null;
      if (!incoming || incoming.id !== action.snapshot.auction.id || incoming.version !== action.snapshot.auction.version) return { ...state, needsSnapshot: true };
      if (incoming.generation === previous?.generation && state.auction && state.auction.id === action.snapshot.auction.id
        && action.snapshot.auction.version < state.auction.version) return state;
      return { ...state, auction: action.snapshot.auction, cursor: action.snapshot.cursor, needsSnapshot: false };
    }
    case 'event': {
      const current = state.auction;
      const incoming = cursorParts(action.cursor);
      const previous = state.cursor ? cursorParts(state.cursor) : null;
      if (!incoming || !previous || incoming.generation !== previous.generation || incoming.id !== action.event.aggregateId || incoming.version !== action.event.aggregateVersion) return { ...state, needsSnapshot: true };
      if (!current || current.id !== action.event.aggregateId || action.event.schemaVersion !== 1) {
        return { ...state, needsSnapshot: true };
      }
      if (action.event.aggregateVersion <= current.version) return state;
      if (action.event.aggregateVersion !== current.version + 1
        || action.event.payload.id !== current.id
        || action.event.payload.version !== action.event.aggregateVersion) {
        return { ...state, needsSnapshot: true };
      }
      return { ...state, auction: action.event.payload, cursor: action.cursor, needsSnapshot: false };
    }
    case 'intent': return { ...state, intent: action.intent };
    case 'unknown': return state.intent
      ? { ...state, intent: { ...state.intent, phase: 'unknown' } } : state;
    case 'outcome': {
      if (!state.intent || action.outcome.key !== state.intent.key
        || action.outcome.actorId !== state.intent.actorId
        || action.outcome.auctionId !== state.intent.auctionId
        || action.outcome.amountMinor !== state.intent.amountMinor) return state;
      return { ...state, intent: { ...state.intent,
        phase: action.outcome.accepted ? 'accepted' : 'rejected', outcome: action.outcome } };
    }
  }
}

const prefix = 'auctionhouse:bid:v1:';
const storageKey = (intent: Intent) => `${prefix}${intent.actorId}:${intent.auctionId}:${intent.key}`;

export function saveIntent(intent: Intent, storage: Storage = localStorage): void {
  storage.setItem(storageKey(intent), JSON.stringify(intent));
}

export function pendingIntent(actorId: string, auctionId: string, storage: Storage = localStorage): Intent | null {
  const match = `${prefix}${actorId}:${auctionId}:`;
  const found: Intent[] = [];
  for (let i = 0; i < storage.length; i++) {
    const key = storage.key(i);
    if (!key?.startsWith(match)) continue;
    try {
      const value = JSON.parse(storage.getItem(key) ?? 'null') as Intent | null;
      if (value?.actorId === actorId && value.auctionId === auctionId
        && /^[A-Za-z0-9._:-]{1,128}$/.test(value.key)
        && Number.isSafeInteger(value.amountMinor) && value.amountMinor > 0 && value.amountMinor <= MAX_AMOUNT
        && ['pending', 'unknown', 'expired'].includes(value.phase)) found.push(value);
    } catch { /* An unreadable browser record cannot authorize a request. */ }
  }
  return found.sort((a, b) => a.updatedAt.localeCompare(b.updatedAt))[0] ?? null;
}
