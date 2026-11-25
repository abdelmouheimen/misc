#!/usr/bin/env python3
"""Contre-vérification indépendante des chiffres publiés.

    python3 scripts/verify_numbers.py

Recalcule, directement depuis les résultats bruts VERSIONNÉS et avec un code distinct de
analyze.py, les valeurs reprises dans le README, les rapports et l'article :
- navigateur : raw.json (échantillons de chaque série) ;
- JMH : jmh.json.gz (sortie brute de JMH) ;
- charge : measure_samples.csv.gz (latences de la phase de mesure) et run.json (cgroups).
Compare ensuite à results/summary/*.csv et échoue (code 1) au moindre écart.
"""
import csv, glob, gzip, json, math, os, statistics as st, sys

sys.stdout.reconfigure(encoding='utf-8')
R = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'results')
S = os.path.join(R, 'summary')
last = lambda kind: sorted(d for d in glob.glob(os.path.join(R, kind, '*'))
                           if os.path.isdir(d) and not os.path.basename(d).startswith('smoke'))[-1]
fails = []


def q(xs, p):
    """Quantile par interpolation linéaire entre rangs (définition 7 de Hyndman et Fan)."""
    s = sorted(xs); h = (len(s) - 1) * p
    lo = math.floor(h); hi = math.ceil(h)
    return s[lo] + (h - lo) * (s[hi] - s[lo])


def check(label, mine, published, tol):
    ok = abs(mine - published) <= tol
    print(f"{'OK ' if ok else 'ÉCART'}  {label:<58} recalculé {mine:12.4f}   publié {published:12.4f}")
    if not ok: fails.append(label)


def rows(name):
    with open(os.path.join(S, name), encoding='utf-8') as f:
        return list(csv.DictReader(f))


# ---- Navigateur
raw = json.load(open(os.path.join(last('browser'), 'raw.json'), encoding='utf-8'))
pub = {r['op']: r for r in rows('browser_summary.csv')}
for op in ('keygen_p256', 'ecdsa_sign_only', 'build_dpop_proof'):
    xs = [v for res in raw['results'] for s in res['series'] if s['op'] == op for v in s['samplesMs']]
    check(f'navigateur {op} médiane (ms)', q(xs, .5), float(pub[op]['median_ms']), 1e-9)
    check(f'navigateur {op} p99 (ms)', q(xs, .99), float(pub[op]['p99_ms']), 1e-9)

# ---- JMH
jmh = json.load(gzip.open(os.path.join(last('jvm'), 'jmh.json.gz'), 'rt', encoding='utf-8'))
pubj = rows('jvm_summary.csv')
for b in jmh:
    if b['mode'] != 'sample': continue
    name = b['benchmark'].rsplit('.', 1)[1]
    store = (b.get('params') or {}).get('storeSize', '')
    bound = (b.get('params') or {}).get('bound', '')
    # Moyenne recalculée depuis l'histogramme brut de SampleTime (toutes forks et itérations).
    tot = cnt = 0
    for fork in b['primaryMetric']['rawDataHistogram']:
        for it in fork:
            for v, c in it: tot += v * c; cnt += c
    p = next(r for r in pubj if r['benchmark'] == name and r['storeSize'] == store and r['bound'] == bound)
    check(f'JMH {name} {store}{bound} moyenne (µs)', tot / cnt, float(p['lat_mean_us']), 0.005 * float(p['lat_mean_us']))

# ---- Charge : latences et CPU de chaque exécution valide, puis surcoût apparié
camp = last('load')
runs = {(int(r['rep']), int(r['rate']), r['mode']): r for r in rows('load_runs.csv')}
mine = {}
for (rep, rate, mode), r in sorted(runs.items()):
    if r['valid'] != 'True': continue
    d = os.path.join(camp, f'rep{rep}', f'{rate}rps_{mode}')
    with gzip.open(os.path.join(d, 'measure_samples.csv.gz'), 'rt', encoding='utf-8') as f:
        lat = [float(v) for m, v in csv.reader(f) if m == 'refresh_http_ms']
    cw = json.load(open(os.path.join(d, 'run.json')))['cpuWindow']
    cores = (cw['backendUsageUsec'][1] - cw['backendUsageUsec'][0]) / 1e6 / (cw['t1'] - cw['t0'])
    mine[(rep, rate, mode)] = {'p50': q(lat, .5), 'p95': q(lat, .95), 'p99': q(lat, .99),
                               'cpu': cores * 1000 / float(r['achieved_ok_rps'])}
    check(f'charge rep{rep} {rate}/s {mode} p50 HTTP (ms)', mine[(rep, rate, mode)]['p50'], float(r['http_p50_ms']), 0.002)
    check(f'charge rep{rep} {rate}/s {mode} CPU ms/renouvellement', mine[(rep, rate, mode)]['cpu'],
          float(r['backend_cpu_ms_per_refresh']), 1e-6)
ov = {int(o['rate']): o for o in rows('load_overhead.csv')}
for rate in (10, 50, 100):
    for k, col in (('p50', 'http_p50_ms'), ('p95', 'http_p95_ms'), ('p99', 'http_p99_ms'), ('cpu', 'backend_cpu_ms_per_refresh')):
        ds = [mine[(rep, rate, 'dpop')][k] - mine[(rep, rate, 'bearer')][k] for rep in (1, 2, 3)]
        rel = [100 * (mine[(rep, rate, 'dpop')][k] / mine[(rep, rate, 'bearer')][k] - 1) for rep in (1, 2, 3)]
        check(f'surcoût {rate}/s {k} Δ médian', st.median(ds), float(ov[rate][col + '_delta_median']), 0.005)
        check(f'surcoût {rate}/s {k} Δ % médian', st.median(rel), float(ov[rate][col + '_rel_pct_median']), 0.1)
        print(f'       paires positives : {sum(d > 0 for d in ds)}/3')

print()
if fails:
    print(f'{len(fails)} écart(s) : {fails}'); sys.exit(1)
print('Tous les chiffres contrôlés correspondent aux résultats bruts.')
