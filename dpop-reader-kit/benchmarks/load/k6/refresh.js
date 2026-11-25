// Renouvellement du refresh token sous charge, avec ou sans DPoP (k6).
//
//   MODE=dpop|bearer RATE=100 WARMUP=120s DURATION=120s BASE_URL=http://backend:8099 k6 run refresh.js
//
// - Modèle à débit d'arrivée constant (constant-arrival-rate).
// - Chaque VU ouvre SA session (uid unique) et, en mode DPoP, génère SA paire de clés ECDSA
//   P-256. Ses jetons ne sont jamais partagés avec un autre VU.
// - Cookies : comme le navigateur de la SPA, le VU renvoie les deux cookies posés par le serveur
//   sur Path=/ (access_token et refresh_token), chacun une fois, avec la DERNIÈRE valeur reçue
//   par Set-Cookie (rotation). Ils sont gérés explicitement : le jar automatique de k6 ne renvoie
//   pas les cookies d'un hôte sans point (« backend »), et chaque requête reçoit un jar vide,
//   jeté ensuite, pour qu'aucun cookie ne soit ajouté en double.
// - Chaque preuve porte un jti unique (crypto.randomUUID) et un iat courant.
// - Seules les itérations planifiées après WARMUP sont marquées phase=measure.

import http from 'k6/http';
import encoding from 'k6/encoding';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const MODE = __ENV.MODE || 'dpop';
const RATE = Number(__ENV.RATE || 10);
const WARMUP_S = parseInt(__ENV.WARMUP || '120s', 10);
const DURATION_S = parseInt(__ENV.DURATION || '120s', 10);
const BASE_URL = __ENV.BASE_URL || 'http://backend:8099';
const PRE_VUS = Number(__ENV.PRE_VUS || Math.max(10, Math.ceil(RATE / 5)));
const MAX_VUS = Number(__ENV.MAX_VUS || Math.max(50, RATE * 2));

if (MODE !== 'dpop' && MODE !== 'bearer') throw new Error(`MODE inconnu : ${MODE}`);

export const options = {
  discardResponseBodies: false,
  scenarios: {
    refresh: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${WARMUP_S + DURATION_S}s`,
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
      gracefulStop: '10s',
    },
  },
  summaryTrendStats: ['count', 'avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
};

const refreshHttp = new Trend('refresh_http_ms', true);   // latence HTTP seule (k6 timings.duration)
// Preuve + requête + lecture de la réponse. k6 n'expose que Date.now() : résolution de 1 ms.
const refreshTotal = new Trend('refresh_total_ms', true);
const proofBytes = new Trend('dpop_header_bytes');        // taille de la valeur de l'en-tête DPoP
const cookieBytes = new Trend('cookie_header_bytes');     // taille de la valeur de l'en-tête Cookie
const okCount = new Counter('refresh_ok');
const failCount = new Counter('refresh_failed');
const loginCount = new Counter('logins');
const acceptedProofs = new Counter('accepted_proofs');   // preuves validées par le serveur (store anti-rejeu)

// État propre à chaque VU.
let session = null; // { key, jwk, cookies: { access_token, refresh_token } }

const ascii = s => {
  const b = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) b[i] = s.charCodeAt(i);
  return b.buffer;
};
const b64u = data => encoding.b64encode(data, 'rawurl');

// Clé privée non extractible, comme dans la SPA ; la clé publique est exportée en JWK.
const newKey = () => crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);

async function buildProof(jwk, privateKey, htm, htu) {
  const header = { typ: 'dpop+jwt', alg: 'ES256', jwk };
  const payload = { jti: crypto.randomUUID(), htm, htu, iat: Math.floor(Date.now() / 1000) };
  const input = `${b64u(JSON.stringify(header))}.${b64u(JSON.stringify(payload))}`;
  const sig = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, privateKey, ascii(input));
  return `${input}.${b64u(sig)}`;
}

/** Applique les Set-Cookie de la réponse (dernière valeur gagnante), comme un navigateur. */
function storeCookies(res, jar) {
  for (const name of ['access_token', 'refresh_token']) {
    const c = res.cookies[name];
    if (c && c.length) jar[name] = c[c.length - 1].value;
  }
  return jar;
}

const cookieHeader = jar => Object.entries(jar).map(([k, v]) => `${k}=${v}`).join('; ');

// Phase calculée depuis le début du SCÉNARIO (et non du test) : setup() — plus long en mode
// DPoP — ne décale donc pas la fenêtre de mesure. Elle dure exactement DURATION dans les deux modes.
function inMeasure() {
  return Date.now() - exec.scenario.startTime >= WARMUP_S * 1000;
}

async function login() {
  const url = `${BASE_URL}/login/connection`;
  const phase = inMeasure() ? 'measure' : 'warmup';
  const headers = { 'Content-Type': 'application/json' };
  let key = null;
  let jwk = null;
  if (MODE === 'dpop') {
    const kp = await newKey();
    const exported = await crypto.subtle.exportKey('jwk', kp.publicKey);
    jwk = { crv: exported.crv, kty: exported.kty, x: exported.x, y: exported.y };
    key = kp.privateKey;
    headers.DPoP = await buildProof(jwk, key, 'POST', url);
  }
  const res = http.post(url, JSON.stringify({ uid: `vu-${exec.vu.idInTest}-${exec.vu.iterationInInstance}` }),
    { headers, jar: new http.CookieJar(), tags: { name: 'login', phase } });
  const ok = res.status === 200 && res.json('status') === 'AUTHENTICATED';
  if (ok && MODE === 'dpop') acceptedProofs.add(1, { phase });
  loginCount.add(1, { phase, ok: String(ok) });
  session = ok ? { key, jwk, cookies: storeCookies(res, {}) } : null;
  if (session && !session.cookies.refresh_token) throw new Error('Set-Cookie refresh_token absent');
}

/**
 * Coût de génération d'une preuve DPoP DANS k6 (claims, JSON, Base64URL, ES256), mesuré en lot
 * avant la charge : Date.now() est trop grossier pour une preuve isolée. Sert à interpréter
 * refresh_total_ms ; ce n'est pas le coût côté navigateur (voir benchmarks/browser).
 */
export async function setup() {
  if (MODE !== 'dpop') return { proofGenMeanUs: null };
  const kp = await newKey();
  const e = await crypto.subtle.exportKey('jwk', kp.publicKey);
  const jwk = { crv: e.crv, kty: e.kty, x: e.x, y: e.y };
  const url = `${BASE_URL}/login/refresh`;
  for (let i = 0; i < 200; i++) await buildProof(jwk, kp.privateKey, 'POST', url); // échauffement
  const n = 2000;
  const t0 = Date.now();
  for (let i = 0; i < n; i++) await buildProof(jwk, kp.privateKey, 'POST', url);
  return { proofGenMeanUs: ((Date.now() - t0) * 1000) / n, proofGenSamples: n };
}

export default async function () {
  if (session === null) {
    await login();
    return; // l'itération de connexion n'est pas un renouvellement
  }
  const phase = inMeasure() ? 'measure' : 'warmup';
  const url = `${BASE_URL}/login/refresh`;
  const cookie = cookieHeader(session.cookies);
  const headers = { 'Content-Type': 'application/json', Cookie: cookie };
  cookieBytes.add(cookie.length, { phase });

  const t0 = Date.now();
  if (MODE === 'dpop') {
    headers.DPoP = await buildProof(session.jwk, session.key, 'POST', url);
    proofBytes.add(headers.DPoP.length, { phase });
  }
  const res = http.post(url, null, { headers, jar: new http.CookieJar(), tags: { name: 'refresh', phase } });
  const ok = res.status === 200 && res.json('status') === 'AUTHENTICATED';
  const total = Date.now() - t0;

  if (ok) {
    storeCookies(res, session.cookies); // rotation : les nouveaux jetons remplacent les anciens
    okCount.add(1, { phase });
    if (MODE === 'dpop') acceptedProofs.add(1, { phase });
    refreshHttp.add(res.timings.duration, { phase });
    refreshTotal.add(total, { phase });
  } else {
    failCount.add(1, { phase, status: String(res.status) });
    session = null; // nouvelle session à l'itération suivante
  }
}

export function handleSummary(data) {
  return { '/out/summary.json': JSON.stringify({ mode: MODE, rate: RATE, warmupS: WARMUP_S,
    durationS: DURATION_S, preVUs: PRE_VUS, maxVUs: MAX_VUS, setup: data.setup_data,
    metrics: data.metrics }, null, 1) };
}
