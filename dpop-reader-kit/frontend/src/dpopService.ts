// Service DPoP côté navigateur (RFC 9449).
//
// La paire de clés vit uniquement en mémoire et la clé privée est NON EXPORTABLE :
// JavaScript ne peut jamais lire sa valeur, seulement demander une signature.
// C'est ce qui rend une preuve infalsifiable même en cas de XSS passif.

let keyPair: CryptoKeyPair | null = null;
let publicJwk: JsonWebKey | null = null;

/** Génère la paire EC P-256 non exportable. À appeler une fois au démarrage. */
export async function initializeDPopKeyPair(): Promise<void> {
  keyPair = await crypto.subtle.generateKey(
    { name: 'ECDSA', namedCurve: 'P-256' },
    false, // extractable:false -> clé privée inaccessible à JS
    ['sign'],
  );
  publicJwk = await crypto.subtle.exportKey('jwk', keyPair.publicKey);
}

/** Construit une preuve DPoP pour une méthode et une URL absolue données. */
export async function buildDPopProof(httpMethod: string, requestUrl: string): Promise<string | null> {
  if (!keyPair || !publicJwk) return null;

  const jwk = { crv: publicJwk.crv, kty: publicJwk.kty, x: publicJwk.x, y: publicJwk.y };
  const header = { typ: 'dpop+jwt', alg: 'ES256', jwk };
  const payload = {
    jti: crypto.randomUUID(),
    htm: httpMethod.toUpperCase(),
    htu: requestUrl,
    iat: Math.floor(Date.now() / 1000),
  };

  const signingInput = `${b64u(enc(header))}.${b64u(enc(payload))}`;
  const sig = await crypto.subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' },
    keyPair.privateKey,
    new TextEncoder().encode(signingInput),
  );
  // Web Crypto renvoie la signature au format r||s (P1363), directement compatible JOSE.
  return `${signingInput}.${b64uBytes(new Uint8Array(sig))}`;
}

/** Empreinte de la clé (jkt, RFC 7638) — ce que le serveur stocke avec le jeton. */
export async function computeJkt(): Promise<string | null> {
  if (!publicJwk) return null;
  const canonical = JSON.stringify({
    crv: publicJwk.crv, kty: publicJwk.kty, x: publicJwk.x, y: publicJwk.y,
  });
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(canonical));
  return b64uBytes(new Uint8Array(digest));
}

/** Tente d'exporter la clé privée : doit échouer (démonstration de extractable:false). */
export async function tryExportPrivateKey(): Promise<string> {
  if (!keyPair) return 'Clé non initialisée';
  try {
    await crypto.subtle.exportKey('jwk', keyPair.privateKey);
    return 'INATTENDU : la clé privée a été exportée';
  } catch (e) {
    return `Export refusé (${(e as Error).name}) — la clé privée reste inaccessible à JS`;
  }
}

function enc(obj: object): Uint8Array {
  return new TextEncoder().encode(JSON.stringify(obj));
}

function b64u(bytes: Uint8Array): string {
  return b64uBytes(bytes);
}

function b64uBytes(bytes: Uint8Array): string {
  let s = '';
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
}
