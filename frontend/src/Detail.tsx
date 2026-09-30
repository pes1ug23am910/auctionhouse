import { useEffect, useReducer, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { ApiError, post, reconcileOrSend, request } from './api';
import type { Actor, Auction, AuctionEvent, BidOutcome, Intent, Snapshot } from './types';
import { initialView, minimumBid, parseAmount, pendingIntent, reducer, saveIntent } from './state';
import { date, Icon, LotArt, message, Notice, number, SignInPrompt, Status } from './ui';

function countdown(deadline: string, now: number) {
  const seconds = Math.max(0, Math.floor((Date.parse(deadline) - now) / 1000));
  if (!seconds) return 'Bidding has ended';
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor(seconds % 86400 / 3600);
  const minutes = Math.floor(seconds % 3600 / 60);
  return days ? `${days}d ${hours}h ${minutes}m` : `${hours}h ${minutes}m ${seconds % 60}s`;
}

export function Detail({ actor, id }: { actor: Actor | null; id: string }) {
  const [state, dispatch] = useReducer(reducer, initialView);
  const [amount, setAmount] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [connection, setConnection] = useState<'connecting' | 'live' | 'reconnecting'>('connecting');
  const [now, setNow] = useState(Date.now());
  const offset = useRef(0);
  const recoverRef = useRef<(reopen?: boolean) => Promise<void>>(async () => {});
  const mounted = useRef(true);

  useEffect(() => {
    if (!actor) { setLoading(false); return; }
    mounted.current = true;
    let active = true;
    let stream: EventSource | null = null;
    let recovering = false;
    let reopenAfterRecovery = false;
    try {
      const pending = pendingIntent(actor.id, id);
      if (pending) { dispatch({ type: 'intent', intent: { ...pending, phase: pending.phase === 'expired' ? 'expired' : 'unknown' } }); setAmount(String(pending.amountMinor)); }
    } catch { setError('Browser storage is unavailable. Enable it before placing a bid so an interrupted bid can be recovered.'); }

    async function recover(reopen = true) {
      if (recovering) { if (reopen) reopenAfterRecovery = true; return; }
      recovering = true;
      try {
        const snapshot = await request<Snapshot>(`/api/auctions/${id}/snapshot`);
        if (!active) return;
        if (snapshot.auction.id !== id) throw new Error('The auction response did not match this piece.');
        if (snapshot.serverTime) offset.current = Date.parse(snapshot.serverTime) - Date.now();
        dispatch({ type: 'snapshot', snapshot });
        setAmount(previous => previous || String(minimumBid(snapshot.auction) ?? ''));
        setLoading(false);
        if (!reopen) return;
        stream?.close();
        stream = new EventSource(`/api/auctions/${id}/events?cursor=${encodeURIComponent(snapshot.cursor)}`);
        stream.onopen = () => { if (active) setConnection('live'); };
        stream.onerror = () => { if (active) setConnection('reconnecting'); };
        stream.addEventListener('auction', event => {
          if (!active) return;
          try {
            const envelope = JSON.parse((event as MessageEvent).data) as AuctionEvent;
            if (!envelope.payload || typeof envelope.payload !== 'object') throw new Error('Incomplete event');
            dispatch({ type: 'event', event: envelope, cursor: (event as MessageEvent).lastEventId });
          } catch { void recover(); }
        });
        stream.addEventListener('snapshot', event => {
          if (!active) return;
          try {
            const fresh = JSON.parse((event as MessageEvent).data) as Snapshot;
            if (fresh.auction.id !== id) throw new Error('Different auction');
            dispatch({ type: 'snapshot', snapshot: fresh });
            if (fresh.serverTime) offset.current = Date.parse(fresh.serverTime) - Date.now();
          } catch { void recover(); }
        });
        stream.addEventListener('gap', () => { void recover(); });
      } catch (reason) { if (active) { setError(message(reason)); setLoading(false); setConnection('reconnecting'); } }
      finally {
        recovering = false;
        if (active && reopenAfterRecovery && navigator.onLine) {
          reopenAfterRecovery = false;
          void recover();
        }
      }
    }
    const offline = () => { stream?.close(); setConnection('reconnecting'); };
    const online = () => { void recover(); };
    window.addEventListener('offline', offline);
    window.addEventListener('online', online);
    recoverRef.current = recover;
    void recover();
    const tick = window.setInterval(() => setNow(Date.now() + offset.current), 1000);
    const refresh = window.setInterval(() => { void recover(false); }, 20_000);
    return () => {
      active = false; mounted.current = false; stream?.close(); clearInterval(tick); clearInterval(refresh);
      window.removeEventListener('offline', offline); window.removeEventListener('online', online);
    };
  }, [actor?.id, id]);

  useEffect(() => { if (state.needsSnapshot) void recoverRef.current(); }, [state.needsSnapshot]);

  if (!actor) return <SignInPrompt />;
  if (loading) return <div className="loading-state" role="status"><span className="spinner" />Taking a closer look...</div>;
  if (!state.auction) return <section className="detail-unavailable"><a href="#/" className="back-link"><Icon name="back" />Back to the collection</a><Notice>{error || 'This piece is not available.'}</Notice><button className="button outline" onClick={() => void recoverRef.current()}>Try again</button></section>;
  const auction = state.auction;
  const owner = actor.id === auction.ownerId;
  const expired = Date.parse(auction.endsAt) <= now;
  const minimum = minimumBid(auction);
  const unresolved = state.intent && ['pending', 'unknown'].includes(state.intent.phase);
  const expiredIntent = state.intent?.phase === 'expired';
  const canBid = auction.status === 'OPEN' && !expired && !owner && minimum !== null;
  const leading = auction.highestBidderId === actor.id;

  async function applyOutcome(outcome: BidOutcome, intent: Intent) {
    if (outcome.actorId !== intent.actorId || outcome.auctionId !== intent.auctionId
      || outcome.key !== intent.key || outcome.amountMinor !== intent.amountMinor) {
      throw new Error('The bid response could not be matched. Keep this bid and check its result again.');
    }
    const resolved: Intent = { ...intent, phase: outcome.accepted ? 'accepted' : 'rejected', outcome, updatedAt: new Date().toISOString() };
    if (mounted.current) dispatch({ type: 'outcome', outcome });
    saveIntent(resolved);
    if (mounted.current) await recoverRef.current(false);
  }

  async function submitBid(event?: FormEvent) {
    event?.preventDefault();
    if (!actor || busy) return;
    setError('');
    let intent: Intent | null = null;
    try {
      intent = pendingIntent(actor.id, id);
      if (intent?.phase === 'expired') { dispatch({ type: 'intent', intent }); return; }
      const checkFirst = Boolean(intent);
      if (!intent) {
        const parsed = parseAmount(amount);
        if (!parsed || minimum === null || parsed < minimum) {
          setError(`Enter a whole bid of at least ${minimum === null ? 'the supported amount' : number(minimum)} units.`); return;
        }
        intent = { actorId: actor.id, auctionId: id, key: crypto.randomUUID(), amountMinor: parsed, phase: 'pending', updatedAt: new Date().toISOString() };
        // Persist identity before a request that may commit without returning.
        saveIntent(intent);
        dispatch({ type: 'intent', intent });
      } else {
        dispatch({ type: 'intent', intent });
      }
      setBusy(true);
      const outcome = await reconcileOrSend(intent, checkFirst);
      await applyOutcome(outcome, intent);
    } catch (reason) {
      if (intent && reason instanceof ApiError && reason.code === 'ACTOR_CHANGED') {
        // The persisted identity belongs to its original account; never rebind or overwrite it.
      } else if (intent && reason instanceof ApiError && reason.status === 410 && reason.code === 'INTENT_EXPIRED') {
        const expired: Intent = { ...intent, phase: 'expired', updatedAt: new Date().toISOString() };
        try { saveIntent(expired); } catch { /* Retain this identity in memory if storage is unavailable. */ }
        if (mounted.current) dispatch({ type: 'intent', intent: expired });
      } else if (intent) {
        const unknown: Intent = { ...intent, phase: 'unknown', updatedAt: new Date().toISOString() };
        try { saveIntent(unknown); } catch { /* Keep the in-memory identity for this session. */ }
        if (mounted.current) dispatch({ type: 'unknown' });
      }
      if (mounted.current) setError(message(reason));
    } finally { if (mounted.current) setBusy(false); }
  }

  async function change(action: 'publish' | 'close' | 'cancel') {
    setBusy(true); setError('');
    try {
      await post<Auction>(`/api/auctions/${id}/${action}`);
      await recoverRef.current();
    } catch (reason) { setError(message(reason)); }
    finally { if (mounted.current) setBusy(false); }
  }

  return <section className="detail-page">
    <div className="detail-topline"><a href="#/" className="back-link"><Icon name="back" size={18} />Back to the collection</a><span className={`connection ${connection}`}><i />{connection === 'live' ? 'Live updates connected' : connection === 'reconnecting' ? 'Reconnecting - checking for updates' : 'Connecting to live updates'}</span></div>
    <div className="detail-grid">
      <div className="detail-story"><div className="detail-art"><LotArt title={auction.title} large /><span className="detail-stamp">A GOOD THING<br />WITH A NEXT CHAPTER.</span></div><div className="story-copy"><p className="eyebrow">THE STORY BEHIND THE PIECE</p><h2>A little more to know.</h2><p>{auction.description || 'The seller has not added a description for this piece.'}</p></div></div>
      <div className="detail-panel"><div className="detail-kicker"><Status auction={auction} /><span>{owner ? 'YOUR LISTING' : 'THE COLLECTION'}</span></div><h1>{auction.title}</h1>
        <div className="detail-price"><span>{auction.highestBidAmountMinor === null ? 'Opening bid' : auction.status === 'CLOSED' ? 'Final bid' : 'Current bid'}</span><strong>{number(auction.highestBidAmountMinor ?? auction.openingPriceMinor)}<small>units</small></strong>{leading && <span className="leading-tag"><Icon name="check" size={15} />{auction.status === 'CLOSED' ? 'This one is yours.' : 'You are the current highest bidder.'}</span>}</div>
        <div className="deadline"><Icon name="clock" /><div><span>{auction.status === 'CLOSED' ? 'Auction ended' : 'Time remaining'}</span><strong>{auction.status === 'CANCELLED' ? 'Listing cancelled' : countdown(auction.endsAt, now)}</strong></div><time dateTime={auction.endsAt}>{date(auction.endsAt)}</time></div>
        {error && <Notice>{error}</Notice>}
        {state.intent?.phase === 'accepted' && <Notice kind="success"><strong>Bid accepted.</strong> Your bid of {number(state.intent.amountMinor)} units was confirmed. The current price may have moved since.</Notice>}
        {state.intent?.phase === 'rejected' && <Notice><strong>This bid was not accepted.</strong> {state.intent.outcome?.rejection?.replaceAll('_', ' ').toLowerCase() ?? 'Review the auction terms before trying again.'}</Notice>}
        {expiredIntent ? <Notice kind="info"><strong>This bid's replay window has ended.</strong>Its original result may have committed. The bid identity has been kept; a replacement bid will not be created. Review the auction's current result below.</Notice> : unresolved ? <div className="pending-panel" role="status"><p className="eyebrow">BID AWAITING CONFIRMATION</p><h3>{number(state.intent!.amountMinor)} units</h3><p>The result is not confirmed yet. Check this same bid before making another.</p><button className="button primary full" disabled={busy} onClick={() => void submitBid()}>{busy ? 'Checking your bid...' : 'Check & recover this bid'}<Icon name="refresh" size={18} /></button></div>
          : canBid ? <form className="bid-form" onSubmit={event => void submitBid(event)}><label htmlFor="bid-amount">Your offer <span>whole units</span></label><div className="bid-input"><input id="bid-amount" inputMode="numeric" pattern="[1-9][0-9]*" value={amount} onChange={event => setAmount(event.target.value)} aria-describedby="bid-minimum" required /><span>units</span></div><p id="bid-minimum" className="field-help">Minimum next bid: {number(minimum!)} units - Increment: {number(auction.minimumIncrementMinor)}</p><button className="button primary full" disabled={busy}>{busy ? 'Confirming your bid...' : 'Place your bid'}<Icon name="arrow" /></button><p className="quiet-note">Only a confirmed bid counts. No payments are collected.</p></form>
          : <div className="auction-explanation"><h3>{auction.status === 'DRAFT' ? 'A story ready to begin.' : auction.status === 'CANCELLED' ? 'This listing has been cancelled.' : auction.status === 'CLOSED' ? auction.highestBidderId ? leading ? 'A good find, made yours.' : 'This chapter is closed.' : 'No bids this time.' : owner ? 'Your piece is on the floor.' : 'The bidding window has closed.'}</h3><p>{auction.status === 'DRAFT' ? 'Review the details, then publish to open bidding.' : owner && !expired && auction.status === 'OPEN' ? 'Watch the offers arrive. Owners cannot bid on their own listings.' : auction.status === 'CLOSED' ? 'The final result is recorded above.' : 'New bids cannot be accepted for this listing right now.'}</p></div>}
        <div className="owner-actions">
          {owner && auction.status === 'DRAFT' && <button className="button dark full" disabled={busy || expired} onClick={() => void change('publish')}>Publish your listing<Icon name="arrow" /></button>}
          {owner && auction.status === 'OPEN' && expired && <button className="button dark full" disabled={busy} onClick={() => void change('close')}>Confirm the final result<Icon name="check" /></button>}
          {owner && ['DRAFT', 'OPEN'].includes(auction.status) && auction.highestBidAmountMinor === null && <button className="text-button cancel-listing" disabled={busy} onClick={() => void change('cancel')}>Cancel this listing</button>}
        </div>
        <div className="detail-rules"><p><Icon name="check" size={16} />A fixed deadline. No last-minute extensions.</p><p><Icon name="check" size={16} />Every accepted bid is confirmed by the auction.</p><p><Icon name="check" size={16} />Whole bidding units. No payments or fees.</p></div>
      </div>
    </div>
  </section>;
}
