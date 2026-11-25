#!/usr/bin/env python3
"""Évolution dans le temps de chaque exécution k6 : débit réussi et latence médiane par tranche.

    python3 scripts/load_timeline.py [--load DIR] [--out DIR] [--bin 10]

Sert à diagnostiquer les exécutions non valides : un débit qui décroît pendant l'exécution
alors que la charge demandée est constante désigne un coût qui croît avec l'état du serveur
(ici, la table des jetons du PoC), pas une saturation immédiate du CPU.

Lit les k6.csv.gz complets (non versionnés) ; écrit load_timeline.csv et
figures/load_timeline.png (versionnés).
"""
import argparse, csv, glob, gzip, json, os, sys
from collections import defaultdict

sys.stdout.reconfigure(encoding='utf-8')
HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, '..', 'results')


def median(xs):
    s = sorted(xs); n = len(s)
    return float('nan') if not n else (s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2)


def timeline(run_dir, bin_s):
    """Par tranche de bin_s secondes depuis le début du scénario : renouvellements réussis/s, p50 HTTP."""
    meta = json.load(open(os.path.join(run_dir, 'run.json'), encoding='utf-8'))
    params = json.load(open(os.path.join(run_dir, '..', '..', 'parameters.json'), encoding='utf-8'))
    ok, lat = defaultdict(int), defaultdict(list)
    t_start = None
    with gzip.open(os.path.join(run_dir, 'k6.csv.gz'), 'rt', encoding='utf-8') as f:
        r = csv.reader(f); h = next(r)
        im, it, iv = h.index('metric_name'), h.index('timestamp'), h.index('metric_value')
        rows = [(row[im], float(row[it]), float(row[iv])) for row in r
                if row[im] in ('refresh_ok', 'refresh_http_ms', 'http_reqs')]
    t_start = min(t for _, t, _ in rows)
    for m, t, v in rows:
        b = int((t - t_start) // bin_s)
        if m == 'refresh_ok': ok[b] += v
        elif m == 'refresh_http_ms': lat[b].append(v)
    last = int((int(params['warmupS']) + int(params['durationS'])) // bin_s)  # tranche finale incomplète exclue
    return meta, [{'t_s': b * bin_s, 'ok_per_s': ok[b] / bin_s, 'p50_http_ms': median(lat[b])}
                  for b in range(last)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--load'); ap.add_argument('--out'); ap.add_argument('--bin', type=int, default=10)
    a = ap.parse_args()
    camp = a.load or sorted(d for d in glob.glob(os.path.join(RES, 'load', '*'))
                            if os.path.isdir(d) and not os.path.basename(d).startswith('smoke'))[-1]
    out = a.out or os.path.join(RES, 'summary'); os.makedirs(os.path.join(out, 'figures'), exist_ok=True)
    params = json.load(open(os.path.join(camp, 'parameters.json'), encoding='utf-8'))
    series, rows = {}, []
    for rd in sorted(glob.glob(os.path.join(camp, 'rep*', '*rps_*'))):
        if not os.path.exists(os.path.join(rd, 'k6.csv.gz')): continue
        meta, tl = timeline(rd, a.bin)
        series[(meta['rep'], meta['rate'], meta['mode'])] = tl
        rows += [{'rep': meta['rep'], 'rate': meta['rate'], 'mode': meta['mode'], **x} for x in tl]
    with open(os.path.join(out, 'load_timeline.csv'), 'w', newline='', encoding='utf-8') as f:
        w = csv.DictWriter(f, fieldnames=['rep', 'rate', 'mode', 't_s', 'ok_per_s', 'p50_http_ms'])
        w.writeheader(); w.writerows(rows)

    import matplotlib; matplotlib.use('Agg'); import matplotlib.pyplot as plt
    rates = sorted({k[1] for k in series if k[1] >= 100})
    colors = {'bearer': '#8a929e', 'dpop': '#1f6feb'}
    fig, axes = plt.subplots(2, len(rates), figsize=(4.2 * len(rates), 6.4), sharex=True, squeeze=False)
    warm = int(params['warmupS'])
    for j, rate in enumerate(rates):
        for (rep, r, mode), tl in sorted(series.items()):
            if r != rate: continue
            t = [x['t_s'] for x in tl]
            kw = dict(color=colors[mode], alpha=.8, lw=1.2, label=mode if rep == 1 else None)
            axes[0][j].plot(t, [x['ok_per_s'] for x in tl], **kw)
            axes[1][j].plot(t, [x['p50_http_ms'] for x in tl], **kw)
        axes[0][j].axhline(rate, color='k', ls=':', lw=1, label='débit demandé')
        axes[0][j].set_title(f'{rate} req/s demandées'); axes[0][j].set_ylim(0, rate * 1.15)
        axes[1][j].set_yscale('log'); axes[1][j].set_xlabel('temps depuis le début (s)')
        for i in (0, 1):
            axes[i][j].axvspan(0, warm, color='#f0f0f0', zorder=0); axes[i][j].grid(alpha=.3)
    axes[0][0].set_ylabel('renouvellements réussis/s'); axes[1][0].set_ylabel('p50 HTTP (ms)')
    axes[0][0].legend(fontsize=8)
    fig.suptitle(f'Évolution pendant chaque exécution (tranches de {a.bin} s ; zone grise : échauffement ; '
                 '3 répétitions par mode)', fontsize=10)
    fig.tight_layout(); fig.savefig(os.path.join(out, 'figures', 'load_timeline.png'), dpi=150); plt.close(fig)
    print(f'chronologie écrite dans {os.path.abspath(out)}')


if __name__ == '__main__':
    main()
