import type { Auction } from './types';

export const number = (amount: number) => new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 }).format(amount);
export const date = (value: string) => new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }).format(new Date(value));
export const navigate = (path: string) => { window.location.hash = path; };
export const message = (error: unknown) => error instanceof Error ? error.message : 'Something went wrong. Please try again.';

export function Icon({ name, size = 20 }: { name: 'arrow' | 'plus' | 'search' | 'check' | 'back' | 'refresh' | 'clock'; size?: number }) {
  const paths = {
    arrow: 'M5 12h14M13 6l6 6-6 6',
    back: 'M19 12H5M11 6l-6 6 6 6',
    plus: 'M12 5v14M5 12h14',
    search: 'm16 16 4 4M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
    check: 'm5 12 4 4L19 6',
    refresh: 'M20 7v5h-5M4 17v-5h5M5.5 7a8 8 0 0 1 13-2L20 7M4 17l1.5 2a8 8 0 0 0 13-2',
    clock: 'M12 7v5l3 2M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name]} /></svg>;
}

export function LotArt({ title, large = false }: { title: string; large?: boolean }) {
  const hash = [...title].reduce((sum, letter) => sum + letter.charCodeAt(0), 0);
  const kind = /vessel|vase|ceramic|pot/i.test(title) ? 'vessel'
    : /lamp|light/i.test(title) ? 'orb'
    : /jazz|record|vinyl/i.test(title) ? 'discs'
    : /book|arch/i.test(title) ? 'arch' : ['vessel', 'orb', 'arch', 'discs'][hash % 4];
  return <div className={`lot-art art-${kind} tone-${hash % 5}${large ? ' large' : ''}`} aria-hidden="true">
    <span className="art-grid" /><span className="art-shadow" />
    <span className="art-object"><i /><b /></span>
    <span className="art-caption">FORM / {String(hash % 99 + 1).padStart(2, '0')}</span>
  </div>;
}

export function Status({ auction }: { auction: Auction }) {
  const expired = new Date(auction.endsAt).getTime() <= Date.now();
  const label = auction.status === 'OPEN' ? (expired ? 'Awaiting result' : 'Live auction') : auction.status === 'DRAFT' ? 'Draft' : auction.status === 'CLOSED' ? 'Closed' : 'Cancelled';
  return <span className={`status ${auction.status.toLowerCase()} ${expired ? 'expired' : ''}`}><span />{label}</span>;
}

export function Notice({ children, kind = 'error' }: { children: React.ReactNode; kind?: 'error' | 'success' | 'info' }) {
  return <div className={`notice notice-${kind}`} role={kind === 'error' ? 'alert' : 'status'}>{children}</div>;
}

export function SignInPrompt() {
  return <div className="empty-state">
    <span className="empty-glyph">a.</span>
    <h2>Your next good thing is here.</h2>
    <p>Sign in to explore the collection, place a bid, or give something a new chapter.</p>
    <a href="#/login" className="button primary">Come on in <Icon name="arrow" /></a>
  </div>;
}
