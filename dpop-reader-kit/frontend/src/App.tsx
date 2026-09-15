import { useEffect, useState } from 'react';
import { initializeDPopKeyPair, computeJkt, tryExportPrivateKey } from './dpopService';
import { connect, refresh, fetchTokens, reset, type AuthResponse } from './api';

interface LogEntry { id: number; text: string; kind: 'ok' | 'ko' | 'info'; }

/** Décode (sans vérifier) l'en-tête et la charge utile d'un JWT, pour l'affichage. */
function decodeJwt(token: string): { header: unknown; payload: Record<string, unknown> } | null {
  const part = (s: string) =>
    JSON.parse(atob(s.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(s.length / 4) * 4, '=')));
  try {
    const [header, payload] = token.split('.');
    return { header: part(header), payload: part(payload) };
  } catch {
    return null;
  }
}

export function App() {
  const [ready, setReady] = useState(false);
  const [jkt, setJkt] = useState('—');
  const [log, setLog] = useState<LogEntry[]>([]);
  let counter = 0;

  useEffect(() => {
    // La clé DPoP est générée une fois au démarrage de la SPA.
    initializeDPopKeyPair()
      .then(async () => {
        setJkt((await computeJkt()) ?? '—');
        setReady(true);
        push('Clé DPoP générée (EC P-256, non exportable).', 'info');
      })
      .catch(() => push('Web Crypto indisponible (contexte non sécurisé ?)', 'ko'));
  }, []);

  function push(text: string, kind: LogEntry['kind']) {
    setLog((prev) => [{ id: counter++ + Date.now(), text, kind }, ...prev]);
  }

  function report(label: string, r: AuthResponse) {
    const kind = r.status === 'AUTHENTICATED' ? 'ok' : 'ko';
    push(`${label} → ${r.status}${r.reason ? ` (${r.reason})` : ''}`, kind);

    // L'access token est un JWT : on montre son contenu et on vérifie sa liaison à la clé.
    const at = r.accessToken ? decodeJwt(r.accessToken) : null;
    if (at) {
      const cnfJkt = (at.payload.cnf as { jkt?: string } | undefined)?.jkt;
      const binding = cnfJkt === jkt
        ? '✓ cnf.jkt = jkt de la clé de ce navigateur'
        : `✗ cnf.jkt (${cnfJkt}) ≠ jkt local`;
      push(
        `Access token (JWT décodé)\n${JSON.stringify(at.header, null, 2)}\n${JSON.stringify(at.payload, null, 2)}\n${binding}`,
        'info',
      );
    }
  }

  return (
    <main>
      <h1>DPoP Lab — SPA React</h1>
      <p>
        Front React/TypeScript signant de vraies preuves DPoP (RFC 9449) auprès du backend
        Kotlin/Spring&nbsp;Boot. La clé privée ne quitte jamais le navigateur.
      </p>
      <p className="jkt">jkt de cette session : <code>{jkt}</code></p>

      <div className="row">
        <button disabled={!ready} onClick={async () => report('connexion', await connect('alice'))}>
          1. Se connecter
        </button>
        <button disabled={!ready} onClick={async () => report('refresh', await refresh())}>
          2. Renouveler (rotation)
        </button>
        <button className="ghost" disabled={!ready} onClick={async () => push(await tryExportPrivateKey(), 'info')}>
          3. Tenter d'exporter la clé
        </button>
        <button className="ghost" onClick={async () => push(JSON.stringify(await fetchTokens(), null, 2), 'info')}>
          Table des jetons
        </button>
        <button className="ghost" onClick={async () => { await reset(); push('Table des jetons vidée.', 'info'); }}>
          Reset
        </button>
      </div>

      <ol className="log">
        {log.map((e) => (
          <li key={e.id} className={e.kind}>
            <pre>{e.text}</pre>
          </li>
        ))}
      </ol>
    </main>
  );
}
