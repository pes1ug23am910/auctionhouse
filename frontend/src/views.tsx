import { useEffect, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { getSession, post, request } from './api';
import type { Actor, Auction } from './types';
import { MAX_AMOUNT, parseAmount } from './state';
import { date, Icon, LotArt, message, navigate, Notice, number, SignInPrompt, Status } from './ui';

export function Browse({ actor, mine }: { actor: Actor | null; mine: boolean }) {
  const [auctions, setAuctions] = useState<Auction[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState('all');
  const [sort, setSort] = useState('ending');
  const [more, setMore] = useState(false);
  async function load(append = false) {
    if (!actor) return;
    setLoading(true); setError('');
    try {
      const page = await request<Auction[]>(`/api/auctions?mine=${mine}&limit=24&offset=${append ? auctions.length : 0}`);
      setAuctions(previous => append ? [...previous, ...page.filter(item => !previous.some(old => old.id === item.id))] : page);
      setMore(page.length === 24);
    } catch (reason) { setError(message(reason)); }
    finally { setLoading(false); }
  }
  useEffect(() => { void load(); }, [actor?.id, mine]);
  const filtered = useMemo(() => auctions.filter(item =>
    (filter === 'all' || item.status === filter)
    && (item.title + ' ' + item.description).toLowerCase().includes(search.toLowerCase()))
    .sort((a, b) => sort === 'newest' ? Date.parse(b.createdAt) - Date.parse(a.createdAt)
      : sort === 'price' ? (b.highestBidAmountMinor ?? b.openingPriceMinor) - (a.highestBidAmountMinor ?? a.openingPriceMinor)
      : Date.parse(a.endsAt) - Date.parse(b.endsAt)), [auctions, filter, search, sort]);

  return <>
    <section className="hero">
      <div className="hero-copy">
        <p className="eyebrow"><span className="tiny-dot" /> {mine ? 'YOUR PERSONAL SELECTION' : 'AN OPEN HOUSE FOR GOOD FINDS'}</p>
        <h1>{mine ? <>Every piece has<br />a <em>next chapter.</em></> : <>Good things.<br />Worth a <em>second look.</em></>}</h1>
        <p className="hero-description">{mine ? 'Make space for something new. Manage your listings, follow the bidding, and see where your pieces go next.' : 'Discover something with character. Make your offer. Give a good thing its next chapter.'}</p>
        <div className="hero-bottom"><span className="handwritten">Find your kind of extraordinary.</span><Icon name="arrow" size={27} /></div>
      </div>
      <div className="hero-art" aria-hidden="true">
        <span className="hero-note">OBJECTS WITH<br />ANOTHER STORY.</span>
        <div className="hero-pedestal" /><div className="hero-vase"><span /></div><div className="hero-disc" />
        <span className="hero-seal">FOUND IT.<br /><b>LOVE IT.</b><br />BID FOR IT.</span>
        <span className="hero-art-label">THE ART OF A GOOD FIND — AUCTIONHOUSE</span>
      </div>
    </section>

    {!actor ? <SignInPrompt /> : <section className="collection" aria-labelledby="collection-heading">
      <div className="section-heading"><div><p className="eyebrow">{mine ? 'CURATED BY YOU' : 'TAKE A CLOSER LOOK'}</p><h2 id="collection-heading">{mine ? 'Your collection' : 'On the auction floor'}<span className="count">{auctions.length}</span></h2></div>
        <button className="text-button refresh-button" onClick={() => void load()} disabled={loading}><Icon name="refresh" size={17} /> Refresh</button></div>
      <div className="collection-tools">
        <div className="filter-tabs" role="group" aria-label="Auction status">
          {([['all', 'All pieces'], ['OPEN', 'Live now'], ['CLOSED', 'Closed'], ...(mine ? [['DRAFT', 'Drafts']] : [])]).map(([value, label]) =>
            <button key={value} onClick={() => setFilter(value)} className={filter === value ? 'selected' : ''} aria-pressed={filter === value}>{label}</button>)}
        </div>
        <div className="search-sort">
          <label className="search-box"><Icon name="search" size={17} /><span className="sr-only">Search loaded auctions</span><input value={search} onChange={event => setSearch(event.target.value)} placeholder="Find something good…" /></label>
          <label className="sort-box"><span className="sr-only">Sort auctions</span><select value={sort} onChange={event => setSort(event.target.value)}><option value="ending">Ending soon</option><option value="newest">Newly listed</option><option value="price">Highest bid</option></select></label>
        </div>
      </div>
      {error && <Notice>{error}</Notice>}
      {loading && !auctions.length ? <div className="loading-state" role="status"><span className="spinner" />Finding the good things…</div>
        : !filtered.length ? <div className="empty-state compact"><span className="empty-glyph">↗</span><h3>{search ? 'No matches in this collection.' : 'A little room for possibility.'}</h3><p>{search ? 'Try another title, clear the filters, or load more pieces.' : mine ? 'Your first listing starts a new story.' : 'There are no pieces in this selection yet. Check back or list one of your own.'}</p><a href="#/create" className="button dark">List a piece <Icon name="plus" size={17} /></a></div>
        : <div className="auction-grid">{filtered.map((auction, index) => <a className="auction-card" key={auction.id} href={`#/lot/${auction.id}`}>
          <div className="card-art-wrap"><LotArt title={auction.title} /><span className="lot-number">LOT {String(index + 1).padStart(3, '0')}</span><span className="card-arrow"><Icon name="arrow" size={19} /></span></div>
          <div className="card-meta"><Status auction={auction} /><span>{date(auction.endsAt)}</span></div>
          <h3>{auction.title}</h3><p className="card-description">{auction.description || 'A new chapter is waiting.'}</p>
          <div className="card-price"><div><span>{auction.highestBidAmountMinor === null ? 'Opening bid' : 'Current bid'}</span><strong>{number(auction.highestBidAmountMinor ?? auction.openingPriceMinor)} <small>units</small></strong></div><span className="card-link">View piece <Icon name="arrow" size={16} /></span></div>
        </a>)}</div>}
      {more && <div className="load-more"><button className="button outline" disabled={loading} onClick={() => void load(true)}>{loading ? 'Loading…' : 'Discover more'}<Icon name="arrow" /></button></div>}
    </section>}
    <section className="editorial-strip"><span className="editorial-number">01—03</span><div><h2>Find it. Follow it. Make it yours.</h2><p>Clear deadlines. Visible bids. A new chapter, one piece at a time.</p></div><span className="editorial-star" aria-hidden="true">✳</span></section>
  </>;
}

export function Login({ onLogin }: { onLogin: (actor: Actor) => void }) {
  const [username, setUsername] = useState('bidder');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function submit(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('');
    try {
      await post('/api/auth/demo/login', { username, password });
      const actor = await getSession();
      if (!actor) throw new Error('Your session could not be opened. Please try signing in again.');
      onLogin(actor);
    } catch (reason) { setError(message(reason)); }
    finally { setBusy(false); }
  }
  return <section className="auth-layout">
    <div className="auth-illustration"><LotArt title="A new chapter" large /><div><p className="eyebrow">WELCOME TO THE HOUSE</p><h1>Good finds.<br /><em>Better stories.</em></h1></div></div>
    <div className="auth-form"><p className="eyebrow">COME ON IN</p><h2>Make yourself at home.</h2><p className="form-intro">Sign in to follow a piece, make an offer, or start a listing.</p>
      <a className="button dark full" href="/oauth2/authorization/keycloak">Continue with your identity provider <Icon name="arrow" /></a>
      <div className="form-divider"><span>Local demonstration</span></div>
      <form onSubmit={event => void submit(event)}>
        {error && <Notice>{error}</Notice>}
        <label>Demo account<select value={username} onChange={event => setUsername(event.target.value)}><option value="bidder">Bidder</option><option value="seller">Seller</option><option value="other">Another bidder</option></select></label>
        <label>Password<input type="password" autoComplete="current-password" value={password} onChange={event => setPassword(event.target.value)} required /></label>
        <p className="field-help">Use the password configured for this local demonstration.</p>
        <button className="button primary full" disabled={busy}>{busy ? 'Opening your session…' : 'Sign in'}<Icon name="arrow" /></button>
      </form><p className="quiet-note">No payments are collected. Bids use whole bidding units.</p>
    </div>
  </section>;
}

function localDate(value: Date) {
  return new Date(value.getTime() - value.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}

export function Create({ actor }: { actor: Actor | null }) {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [opening, setOpening] = useState('100');
  const [increment, setIncrement] = useState('10');
  const [endsAt, setEndsAt] = useState(localDate(new Date(Date.now() + 86_400_000)));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  if (!actor) return <SignInPrompt />;
  async function submit(event: FormEvent) {
    event.preventDefault(); setError('');
    const openingPriceMinor = parseAmount(opening);
    const minimumIncrementMinor = parseAmount(increment);
    const deadline = new Date(endsAt);
    if (!openingPriceMinor || !minimumIncrementMinor) { setError('Enter positive whole amounts within the supported range.'); return; }
    if (!title.trim() || !Number.isFinite(deadline.getTime()) || deadline.getTime() <= Date.now()) { setError('Add a title and choose a deadline in the future.'); return; }
    setBusy(true);
    try {
      const auction = await post<Auction>('/api/auctions', { title: title.trim(), description: description.trim(), openingPriceMinor, minimumIncrementMinor, endsAt: deadline.toISOString() });
      navigate(`/lot/${auction.id}`);
    } catch (reason) { setError(message(reason)); }
    finally { setBusy(false); }
  }
  return <section className="create-layout">
    <div className="create-copy"><a href="#/mine" className="back-link"><Icon name="back" size={18} />Your collection</a><p className="eyebrow">MAKE ROOM FOR A NEW CHAPTER</p><h1>Something good<br />starts <em>here.</em></h1><p>Tell its story. Set the opening bid. Choose a deadline. You can review everything before publishing.</p><div className="listing-note"><span>01</span><div><h3>Give it a story</h3><p>A clear title and description help the right person find your piece.</p></div></div><div className="listing-note"><span>02</span><div><h3>Set the terms</h3><p>Every bid meets your minimum increment. The deadline stays fixed.</p></div></div><div className="listing-note"><span>03</span><div><h3>Open the doors</h3><p>Your draft stays private until you publish it.</p></div></div></div>
    <form className="listing-form" onSubmit={event => void submit(event)}>
      <div className="form-heading"><h2>The details</h2><span>DRAFT LISTING</span></div>
      {error && <Notice>{error}</Notice>}
      <label>What are you listing?<input value={title} maxLength={160} onChange={event => setTitle(event.target.value)} placeholder="A title with a little character" required /></label>
      <label>Tell its story<textarea value={description} maxLength={4000} rows={5} onChange={event => setDescription(event.target.value)} placeholder="The details, the condition, and what makes it worth a second look." /></label>
      <div className="form-row"><label>Opening bid <span>in whole units</span><input inputMode="numeric" pattern="[1-9][0-9]*" max={MAX_AMOUNT} value={opening} onChange={event => setOpening(event.target.value)} required /></label><label>Minimum increment <span>in whole units</span><input inputMode="numeric" pattern="[1-9][0-9]*" value={increment} onChange={event => setIncrement(event.target.value)} required /></label></div>
      <label>Auction ends<input type="datetime-local" step="1" value={endsAt} onChange={event => setEndsAt(event.target.value)} required /></label><p className="field-help">Shown in your local time. Bids do not extend the deadline.</p>
      <div className="form-submit"><span>Save now. Publish when you're ready.</span><button className="button primary" disabled={busy}>{busy ? 'Saving…' : 'Create draft'}<Icon name="arrow" /></button></div>
    </form>
  </section>;
}
