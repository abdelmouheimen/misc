// Page de benchmark exécutée dans Chromium (Playwright).
//
// Elle importe le VRAI service DPoP de la SPA (frontend/src/dpopService.ts) : la génération
// complète d'une preuve et le calcul du jkt sont mesurés sur le code du PoC, sans copie.
// Les primitives isolées (génération de clé, export JWK, signature seule) appellent Web Crypto
// avec exactement les paramètres utilisés par ce service.

import { initializeDPopKeyPair, buildDPopProof, computeJkt } from '../../../frontend/src/dpopService';

type Op = () => Promise<unknown>;

export interface Series {
  op: string;
  samplesMs: number[];
  // Durée d'un lot de `batchOps` opérations consécutives, pour une moyenne non limitée
  // par la résolution de performance.now().
  batchOps: number;
  batchMs: number;
  extra?: Record<string, unknown>;
}

const ECDSA_GEN = { name: 'ECDSA', namedCurve: 'P-256' } as const;
const ECDSA_SIGN = { name: 'ECDSA', hash: 'SHA-256' } as const;

/** Résolution observée : plus petit écart non nul entre deux lectures consécutives. */
function timerResolutionMs(): number {
  let min = Infinity;
  for (let i = 0; i < 20000; i++) {
    const a = performance.now();
    let b = performance.now();
    while (b === a) b = performance.now();
    min = Math.min(min, b - a);
  }
  return min;
}

async function measure(name: string, op: Op, warmup: number, iterations: number, batchOps: number,
                       extra?: Record<string, unknown>): Promise<Series> {
  for (let i = 0; i < warmup; i++) await op();
  const samplesMs = new Array<number>(iterations);
  for (let i = 0; i < iterations; i++) {
    const t0 = performance.now();
    await op();
    samplesMs[i] = performance.now() - t0;
  }
  const b0 = performance.now();
  for (let i = 0; i < batchOps; i++) await op();
  const batchMs = performance.now() - b0;
  return { op: name, samplesMs, batchOps, batchMs, extra };
}

export async function runSeries(params: { warmup: number; iterations: number; batchOps: number; htu: string }) {
  const { warmup, iterations, batchOps, htu } = params;
  const out: Series[] = [];

  // Référence : coût d'un await vide (planification des promesses + lecture du timer).
  out.push(await measure('baseline_await', async () => undefined, warmup, iterations, batchOps));

  // 1. Génération d'une paire ECDSA P-256 non extractible (paramètres de initializeDPopKeyPair).
  out.push(await measure('keygen_p256', () => crypto.subtle.generateKey(ECDSA_GEN, false, ['sign']),
    warmup, iterations, batchOps));

  // 2. Export JWK de la clé publique.
  const kp = (await crypto.subtle.generateKey(ECDSA_GEN, false, ['sign'])) as CryptoKeyPair;
  out.push(await measure('export_jwk', () => crypto.subtle.exportKey('jwk', kp.publicKey),
    warmup, iterations, batchOps));

  // Fonction réelle du PoC : génération + export (une fois par chargement de la SPA).
  out.push(await measure('initialize_dpop_keypair', () => initializeDPopKeyPair(), warmup, iterations, batchOps));

  // 3. Thumbprint JWK (RFC 7638) : fonction réelle computeJkt() (sérialisation + SHA-256).
  out.push(await measure('jwk_thumbprint', () => computeJkt(), warmup, iterations, batchOps));

  // 5. Preuve complète : fonction réelle buildDPopProof() (claims, JSON, Base64URL, signature).
  const sample = (await buildDPopProof('POST', htu))!;
  const sizes: number[] = [];
  out.push(await measure('build_dpop_proof', async () => {
    const p = await buildDPopProof('POST', htu);
    sizes.push(p!.length);
  }, warmup, iterations, batchOps));

  // 4. Signature ECDSA seule, sur une entrée de la taille réelle (en-tête.charge utile d'une preuve).
  const signingInput = new TextEncoder().encode(sample.slice(0, sample.lastIndexOf('.')));
  const privateKey = (await crypto.subtle.generateKey(ECDSA_GEN, false, ['sign']) as CryptoKeyPair).privateKey;
  out.push(await measure('ecdsa_sign_only', () => crypto.subtle.sign(ECDSA_SIGN, privateKey, signingInput),
    warmup, iterations, batchOps, { signingInputBytes: signingInput.length }));

  // 6. Taille des preuves (octets ASCII = octets de la valeur de l'en-tête DPoP).
  const proofSize = { min: Math.min(...sizes), max: Math.max(...sizes), count: sizes.length,
    headerLineBytes: 'DPoP: '.length + sample.length + 2 };

  return {
    timerResolutionMs: timerResolutionMs(),
    crossOriginIsolated: globalThis.crossOriginIsolated === true,
    userAgent: navigator.userAgent,
    hardwareConcurrency: navigator.hardwareConcurrency,
    proofSize,
    series: out,
  };
}

(globalThis as unknown as { runSeries: typeof runSeries }).runSeries = runSeries;
