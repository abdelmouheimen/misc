// Benchmark Web Crypto dans un vrai Chromium (Playwright).
//
//   node run-browser-bench.mjs [--series 5] [--warmup 200] [--iterations 2000] [--batch 500] [--out DIR]
//
// - La page est servie sur http://127.0.0.1 (contexte sécurisé requis par Web Crypto) avec
//   COOP/COEP, ce qui isole l'origine et donne à performance.now() sa résolution la plus fine.
// - Chaque série s'exécute dans un nouveau contexte de navigation (état JS indépendant).
// - Le démarrage du navigateur et le chargement de la page ne sont jamais chronométrés.

import { build } from 'esbuild';
import { chromium } from 'playwright';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) =>
  (a.startsWith('--') ? [...acc, [a.slice(2), all[i + 1]]] : acc), []));
const P = {
  series: Number(args.series ?? 5),
  warmup: Number(args.warmup ?? 200),
  iterations: Number(args.iterations ?? 2000),
  batchOps: Number(args.batch ?? 500),
  // Même forme d'URL que la SPA : base publique du backend + chemin de renouvellement.
  htu: args.htu ?? 'http://localhost:8099/login/refresh',
};
const outDir = path.resolve(args.out ?? path.join(here, '..', 'results', 'browser',
  new Date().toISOString().replace(/[:.]/g, '-')));

const bundle = await build({
  entryPoints: [path.join(here, 'src', 'bench-page.ts')],
  bundle: true, format: 'iife', target: 'es2022', write: false, minify: false,
});
const js = bundle.outputFiles[0].text;
const html = '<!doctype html><meta charset="utf-8"><title>dpop-bench</title><script src="/bench.js"></script>';

const server = http.createServer((req, res) => {
  const headers = {
    'Cross-Origin-Opener-Policy': 'same-origin',
    'Cross-Origin-Embedder-Policy': 'require-corp',
    'Cache-Control': 'no-store',
  };
  if (req.url === '/bench.js') { res.writeHead(200, { ...headers, 'Content-Type': 'text/javascript' }); res.end(js); }
  else { res.writeHead(200, { ...headers, 'Content-Type': 'text/html' }); res.end(html); }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const url = `http://127.0.0.1:${server.address().port}/`;

const browser = await chromium.launch({ headless: true });
const results = [];
try {
  for (let s = 0; s < P.series; s++) {
    const context = await browser.newContext();
    const page = await context.newPage();
    await page.goto(url);
    await page.waitForFunction(() => typeof globalThis.runSeries === 'function');
    const r = await page.evaluate(p => globalThis.runSeries(p), P);
    results.push({ series: s + 1, ...r });
    process.stdout.write(`série ${s + 1}/${P.series} terminée\n`);
    await context.close();
  }
} finally {
  await browser.close();
  server.close();
}

fs.mkdirSync(outDir, { recursive: true });
const meta = {
  date: new Date().toISOString(),
  params: P,
  browserVersion: browser.version(),
  playwrightVersion: JSON.parse(fs.readFileSync(path.join(here, 'node_modules', 'playwright', 'package.json'))).version,
  node: process.version,
  timerResolutionMs: results.map(r => r.timerResolutionMs),
  crossOriginIsolated: results.every(r => r.crossOriginIsolated),
  userAgent: results[0]?.userAgent,
  hardwareConcurrency: results[0]?.hardwareConcurrency,
  proofSize: results[0]?.proofSize,
};
fs.writeFileSync(path.join(outDir, 'raw.json'), JSON.stringify({ meta, results }));
fs.writeFileSync(path.join(outDir, 'meta.json'), JSON.stringify(meta, null, 2));
console.log(`résultats bruts : ${outDir}`);
