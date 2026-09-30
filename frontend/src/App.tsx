import { useEffect, useState } from 'react';
import { getSession, post } from './api';
import type { Actor } from './types';
import { Browse, Create, Login } from './views';
import { Detail } from './Detail';
import { Icon, message, navigate, Notice } from './ui';

function useRoute() {
  const [route, setRoute] = useState(window.location.hash.slice(1) || '/');
  useEffect(() => {
    const update = () => { setRoute(window.location.hash.slice(1) || '/'); window.scrollTo({ top: 0, behavior: 'instant' }); };
    window.addEventListener('hashchange', update);
    return () => window.removeEventListener('hashchange', update);
  }, []);
  return route;
}

export default function App() {
  const route = useRoute();
  const [actor, setActor] = useState<Actor | null>(null);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [leaving, setLeaving] = useState(false);

  useEffect(() => {
    let active = true;
    void getSession().then(value => { if (active) setActor(value); })
      .catch(reason => { if (active) setError(message(reason)); })
      .finally(() => { if (active) setReady(true); });
    const refresh = () => {
      if (document.visibilityState === 'visible') void getSession()
        .then(value => { if (active) setActor(value); })
        .catch(reason => { if (active) setError(message(reason)); });
    };
    document.addEventListener('visibilitychange', refresh);
    return () => { active = false; document.removeEventListener('visibilitychange', refresh); };
  }, []);

  async function logout() {
    setLeaving(true); setError('');
    try { await post<void>('/api/auth/logout'); setActor(null); navigate('/'); }
    catch (reason) { setError(message(reason)); }
    finally { setLeaving(false); }
  }

  const selectedId = route.startsWith('/lot/') ? route.slice('/lot/'.length) : null;

  return <div className="site-shell">
    <a className="skip-link" href="#main-content" onClick={event => { event.preventDefault(); document.getElementById("main-content")?.focus(); document.getElementById("main-content")?.scrollIntoView(); }}>Skip to content</a>
    <header className="site-header">
      <a href="#/" className="wordmark" aria-label="auctionhouse home">auctionhouse<span /></a>
      <nav aria-label="Main navigation">
        <a href="#/" className={route === '/' ? 'active' : ''} aria-current={route === '/' ? 'page' : undefined}>Discover</a>
        <a href="#/mine" className={route === '/mine' ? 'active' : ''} aria-current={route === '/mine' ? 'page' : undefined}>My collection</a>
      </nav>
      <div className="header-actions">
        {actor ? <div className="account">
          <span className="avatar" aria-hidden="true">{actor.displayName.slice(0, 1).toUpperCase()}</span>
          <span className="account-name">{actor.displayName}</span>
          <button className="text-button logout" onClick={() => void logout()} disabled={leaving}>{leaving ? 'Signing out...' : 'Sign out'}</button>
        </div> : <a href="#/login" className="text-button">Sign in</a>}
        <a href="#/create" className="button dark small" aria-label="List a piece"><Icon name="plus" size={17} /><span>List a piece</span></a>
      </div>
    </header>
    <main id="main-content" tabIndex={-1}>
      {error && <div className="global-notice"><Notice>{error} <button className="text-button" onClick={() => setError('')}>Dismiss</button></Notice></div>}
      {!ready ? <div className="loading-state" role="status"><span className="spinner" />Opening the doors...</div>
        : route === '/login' ? <Login onLogin={value => { setActor(value); navigate('/'); }} />
        : route === '/create' ? <Create actor={actor} />
        : selectedId ? <Detail key={`${actor?.id ?? 'guest'}:${selectedId}`} actor={actor} id={selectedId} />
        : <Browse key={`${actor?.id ?? 'guest'}:${route}`} actor={actor} mine={route === '/mine'} />}
    </main>
    <footer className="site-footer">
      <a href="#/" className="wordmark">auctionhouse<span /></a>
      <p>Good things. Next chapters.</p>
      <span className="footer-note">A place to bid. No payments collected.</span>
    </footer>
  </div>;
}
