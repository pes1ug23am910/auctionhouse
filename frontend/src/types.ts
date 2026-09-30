export interface Actor {
  id: string;
  displayName: string;
  role: 'USER' | 'ADMIN';
}

export interface Auction {
  id: string;
  ownerId: string;
  title: string;
  description: string;
  openingPriceMinor: number;
  minimumIncrementMinor: number;
  endsAt: string;
  status: 'DRAFT' | 'OPEN' | 'CLOSED' | 'CANCELLED';
  highestBidAmountMinor: number | null;
  highestBidderId: string | null;
  version: number;
  createdAt: string;
}

export interface BidOutcome {
  actorId: string;
  auctionId: string;
  key: string;
  amountMinor: number;
  accepted: boolean;
  rejection: string | null;
  bidId: string | null;
  auctionVersion: number;
  decidedAt: string;
}

export interface Intent {
  actorId: string;
  auctionId: string;
  key: string;
  amountMinor: number;
  phase: 'pending' | 'unknown' | 'expired' | 'accepted' | 'rejected';
  updatedAt: string;
  outcome?: BidOutcome;
}

export interface Snapshot {
  auction: Auction;
  cursor: string;
  serverTime?: string;
}

export interface AuctionEvent {
  eventId: string;
  eventType: string;
  schemaVersion: number;
  aggregateId: string;
  aggregateVersion: number;
  occurredAt: string;
  payload: Auction;
}
