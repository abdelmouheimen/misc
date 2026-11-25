#!/usr/bin/env python3
"""Figures de publication, générées uniquement à partir des synthèses versionnées.

    python3 scripts/figures.py [--summary results/summary]

Entrées (produites par analyze.py, load_timeline.py et jfr_hotspots.sh) :
    browser_summary.csv, jvm_summary.csv, load_runs.csv, load_overhead.csv,
    load_timeline.csv, jfr_hotspots.txt
Sorties : figures/fig1_… à fig6_ et figS1_, en SVG et en PNG 300 dpi.

Aucune valeur n'est saisie à la main : chaque nombre affiché est lu dans ces fichiers.
Conventions :
- deux couleurs fixes, validées pour les daltonismes (protanopie, deutéranopie, tritanopie :
  ΔE OKLab ≥ 24,7) ; « Avec DPoP » porte en plus des hachures (lecture en niveaux de gris) ;
- microbenchmarks et bout en bout ne partagent jamais un axe ;
- les exécutions non valides ne sont jamais présentées comme des mesures de DPoP.
"""
import argparse, csv, os, re, statistics as st, sys

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Patch

sys.stdout.reconfigure(encoding='utf-8')
HERE = os.path.dirname(os.path.abspath(__file__))

# Créneaux catégoriels 1 et 2 de la palette de référence (validate_palette.js : toutes vérifications OK).
SANS, AVEC = '#2a78d6', '#eb6834'
AVEC_PALE = '#f6b89c'      # même teinte, pour la part qui dépend du format des jetons du PoC
INK, INK2, MUTED, GRID = '#0b0b0b', '#52514e', '#8a8984', '#e4e3df'
BASE = '#d9d8d4'           # octets communs aux deux modes
HATCH = '////'
LABEL = {'bearer': 'Sans DPoP', 'dpop': 'Avec DPoP'}
COLOR = {'bearer': SANS, 'dpop': AVEC}
RATES = (10, 50, 100)      # débits valides dans les deux modes (voir RESULTS.md)
LEGEND = [Patch(color=SANS, label='Sans DPoP'), Patch(facecolor=AVEC, hatch=HATCH, label='Avec DPoP')]

plt.rcParams.update({
    'font.family': 'DejaVu Sans', 'font.size': 9, 'axes.titlesize': 10, 'axes.labelsize': 9,
    'axes.edgecolor': MUTED, 'axes.labelcolor': INK2, 'xtick.color': INK2, 'ytick.color': INK2,
    'axes.spines.top': False, 'axes.spines.right': False, 'axes.grid': True, 'axes.axisbelow': True,
    'grid.color': GRID, 'grid.linewidth': 0.6, 'legend.frameon': False, 'hatch.color': 'white',
    'hatch.linewidth': 0.8, 'figure.facecolor': 'white', 'axes.facecolor': 'white',
    'savefig.facecolor': 'white', 'svg.fonttype': 'path', 'svg.hashsalt': 'dpop-bench',
})


def read(path):
    with open(path, encoding='utf-8') as f:
        return list(csv.DictReader(f))


def num(x):
    try: return float(x)
    except (TypeError, ValueError): return float('nan')


def fr(v, nd=2):
    """Nombre au format français : virgule décimale, espace fine insécable pour les milliers."""
    return f'{v:,.{nd}f}'.replace(',', ' ').replace('.', ',')


def signed(v, nd):
    return ('+' if v >= 0 else '−') + fr(abs(v), nd)


def save(fig, out, name, note):
    """Note de périmètre en pied de figure (lignes coupées à la main), puis SVG et PNG 300 dpi."""
    fig.text(0.01, 0.0, note, ha='left', va='top', fontsize=7, color=INK2, linespacing=1.4)
    for ext, kw in (('svg', {'metadata': {'Date': None}}), ('png', {'dpi': 300})):
        fig.savefig(os.path.join(out, f'{name}.{ext}'), bbox_inches='tight', pad_inches=0.12, **kw)
    plt.close(fig)
    print(f'  {name}.svg / .png')


def valid_runs(runs, rate, mode):
    return [r for r in runs if int(r['rate']) == rate and r['mode'] == mode and r['valid'] == 'True']


def grouped_bars(ax, runs, ov, key, unit, nd, ytop=None):
    """Barres groupées sans/avec DPoP aux débits valides : médiane des répétitions valides, trait min–max,
    valeur absolue au-dessus de chaque barre, surcoût apparié (médiane des paires) au-dessus du groupe."""
    w, top, notes = 0.36, 0, []
    for i, rate in enumerate(RATES):
        for j, mode in enumerate(('bearer', 'dpop')):
            xs = [num(r[key]) for r in valid_runs(runs, rate, mode)]
            m, lo, hi = st.median(xs), min(xs), max(xs)
            x = i + (j - 0.5) * w
            ax.bar(x, m, w * 0.94, color=COLOR[mode], hatch=HATCH if mode == 'dpop' else None, linewidth=0)
            ax.errorbar(x, m, yerr=[[m - lo], [hi - m]], fmt='none', ecolor=INK, elinewidth=0.8, capsize=2.5)
            ax.text(x, hi, fr(m, nd), ha='center', va='bottom', fontsize=7.5, color=INK)
            top = max(top, hi)
        o = next(o for o in ov if int(o['rate']) == rate)
        notes.append((i, num(o[key + '_delta_median']), num(o[key + '_rel_pct_median'])))
    top = ytop or top
    for i, d, rel in notes:
        ax.text(i, top * 1.13, f'{signed(d, nd)} {unit}\n({signed(rel, 0)} %)', ha='center', va='bottom',
                fontsize=7.5, color=INK2, linespacing=1.2)
    ax.set_ylim(0, top * 1.38)
    ax.set_xticks(range(len(RATES)), [f'{r}/s' for r in RATES])
    ax.grid(axis='x', visible=False)
    return top


def max_hi(runs, key):
    return max(num(r[key]) for rate in RATES for m in ('bearer', 'dpop') for r in valid_runs(runs, rate, m))


# ---------------------------------------------------------------- figure 1
def fig1_cpu(S, out):
    runs, ov = read(os.path.join(S, 'load_runs.csv')), read(os.path.join(S, 'load_overhead.csv'))
    fig, ax = plt.subplots(figsize=(6.4, 3.9))
    grouped_bars(ax, runs, ov, 'backend_cpu_ms_per_refresh', 'ms', 2)
    ax.set_xlabel('renouvellements par seconde (débit constant)')
    ax.set_ylabel('CPU du conteneur backend\n(ms par renouvellement)')
    fig.suptitle('Temps CPU serveur par renouvellement du refresh token', x=0.01, ha='left', fontsize=10.5, color=INK)
    fig.legend(handles=LEGEND, loc='upper left', ncol=2, bbox_to_anchor=(0.01, 0.95))
    fig.tight_layout(rect=(0, 0, 1, 0.88))
    save(fig, out, 'fig1_cpu_par_renouvellement',
         'Bout en bout (k6, POST /login/refresh). Barre : médiane de 3 répétitions valides ; trait : min–max. '
         'Au-dessus : surcoût apparié (médiane des 3 paires\n« même répétition, même débit »). '
         'Temps CPU cumulé de tous les threads du conteneur (cgroup) sur ≈ 117 s de mesure, divisé par le nombre de\n'
         'renouvellements : ce n\'est pas une latence. À 10/s, le CPU de fond de la JVM pèse davantage par renouvellement.')


# ---------------------------------------------------------------- figure 2
def fig2_crypto(S, out):
    br = {r['op']: r for r in read(os.path.join(S, 'browser_summary.csv'))}
    jv = read(os.path.join(S, 'jvm_summary.csv'))
    jm = lambda b, store='': next(r for r in jv if r['benchmark'] == b and r['storeSize'] == store and not r['bound'])
    left = [('Génération de la paire P-256\n(subtle.generateKey)', br['keygen_p256']),
            ('Signature ECDSA seule\n(subtle.sign)', br['ecdsa_sign_only']),
            ('Preuve complète\n(buildDPopProof du PoC)', br['build_dpop_proof'])]
    right = [('Vérification de la signature ES256\n(Nimbus ECDSAVerifier)', jm('verifySignature')),
             ('Validation complète d\'une preuve\n(DpopProofValidator, store vide)', jm('fullValidation', '0'))]
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(10.5, 3.3))
    panels = (
        (a1, left, 'Navigateur : Chromium 159, Web Crypto',
         lambda r: num(r['median_ms']) * 1000,
         lambda r: (num(r['median_ms']) * 1000, num(r['p95_ms']) * 1000),
         'µs par opération : MÉDIANE (trait jusqu\'au p95)'),
        (a2, right, 'Serveur : JDK 25, Nimbus 10.9 (JMH, 1 thread)',
         lambda r: num(r['lat_mean_us']),
         lambda r: (num(r['lat_mean_us']) - num(r['lat_err_us']), num(r['lat_mean_us']) + num(r['lat_err_us'])),
         'µs par opération : MOYENNE (trait : IC 99,9 %)'))
    for ax, rows, title, val, lo_hi, xl in panels:
        y = list(range(len(rows)))
        hi_max = max(lo_hi(r)[1] for _, r in rows)
        ax.barh(y, [val(r) for _, r in rows], 0.55, color=AVEC, hatch=HATCH, linewidth=0)
        for yi, (_, r) in zip(y, rows):
            lo, hi = lo_hi(r)
            ax.plot([lo, hi], [yi, yi], color=INK, lw=0.9)
            ax.text(hi + hi_max * 0.03, yi, f'{fr(val(r), 0)} µs', va='center', fontsize=8, color=INK)
        ax.set_yticks(y, [n for n, _ in rows]); ax.invert_yaxis()
        ax.set_xlim(0, hi_max * 1.3)
        ax.set_xlabel(xl); ax.set_title(title, loc='left', color=INK, fontsize=9.5); ax.grid(axis='y', visible=False)
    fig.tight_layout(w_pad=3)
    save(fig, out, 'fig2_operations_cryptographiques',
         'Deux environnements et deux statistiques : les panneaux ne se comparent pas entre eux, et les barres ne '
         's\'additionnent pas (benchmarks indépendants).\nNavigateur : 5 séries × 2 000 mesures, '
         'résolution du timer 5 µs, Chromium sans interface épinglé sur les cœurs P. Serveur : 3 forks × 10 × 5 s, '
         'JVM épinglée\nsur les cœurs P. La validation complète inclut la vérification ES256 ; mesurées par deux '
         f'benchmarks distincts,\nles deux valeurs ({fr(num(right[1][1]["lat_mean_us"]), 0)} et '
         f'{fr(num(right[0][1]["lat_mean_us"]), 0)} µs) restent dans l\'ordre de la variation entre benchmarks.')


# ---------------------------------------------------------------- figure 3
def fig3_latency(S, out):
    runs, ov = read(os.path.join(S, 'load_runs.csv')), read(os.path.join(S, 'load_overhead.csv'))
    fig, axes = plt.subplots(1, 3, figsize=(10.5, 3.6), sharey=True)
    top = max(max_hi(runs, f'http_{p}_ms') for p in ('p50', 'p95', 'p99'))
    for ax, p in zip(axes, ('p50', 'p95', 'p99')):
        grouped_bars(ax, runs, ov, f'http_{p}_ms', 'ms', 2, ytop=top)
        ax.set_title(f'p{p[1:]}', loc='left', color=INK)
        ax.set_xlabel('renouvellements par seconde')
    axes[0].set_ylabel('latence HTTP (ms, valeurs absolues)')
    fig.suptitle('Latence HTTP du renouvellement, avec et sans DPoP', x=0.01, ha='left', fontsize=10.5, color=INK)
    fig.legend(handles=LEGEND, loc='upper left', ncol=2, bbox_to_anchor=(0.01, 0.95))
    fig.tight_layout(rect=(0, 0, 1, 0.88))
    save(fig, out, 'fig3_latence_http',
         'Latence HTTP mesurée par k6 (timings.duration : envoi, attente, réception ; génération de la preuve exclue), '
         'réseau Docker interne, sans TLS.\nBarres : valeurs absolues, médiane de 3 répétitions valides (trait : min–max). '
         'Au-dessus : écart apparié, absolu et relatif (médiane des 3 paires).\nPercentiles calculés '
         'par exécution (1 200 à 12 000 échantillons) ; à 10/s, le p99 repose sur ≈ 12 valeurs par exécution.')


# ---------------------------------------------------------------- figure 4
def fig4_network(S, out):
    runs = read(os.path.join(S, 'load_runs.csv'))
    med = lambda mode, k: st.median(num(r[k]) for rate in RATES for r in valid_runs(runs, rate, mode))
    sent_b, sent_d = med('bearer', 'sent_bytes_per_req'), med('dpop', 'sent_bytes_per_req')
    recv_b, recv_d = med('bearer', 'recv_bytes_per_req'), med('dpop', 'recv_bytes_per_req')
    cookie = med('dpop', 'cookie_header_bytes_median') - med('bearer', 'cookie_header_bytes_median')
    header = med('dpop', 'dpop_header_bytes_median') + len('DPoP: \r\n')
    req_rest = (sent_d - sent_b) - header - cookie
    body = (recv_d - recv_b) - cookie       # Set-Cookie : même croissance des deux jetons que l'en-tête Cookie
    rows = [(f'Requête\n{fr(sent_b, 0)} → {fr(sent_d, 0)} o\n(+{fr(100 * (sent_d / sent_b - 1), 1)} %)',
             [(header, 'en-tête DPoP (la preuve)', 'inh'), (cookie, 'cnf.jkt dans les 2 cookies', 'poc')]),
            (f'Réponse\n{fr(recv_b, 0)} → {fr(recv_d, 0)} o\n(+{fr(100 * (recv_d / recv_b - 1), 1)} %)',
             [(cookie, 'cnf.jkt dans les 2 Set-Cookie', 'poc'), (body, 'cnf.jkt dans le corps JSON', 'poc')])]
    fig, ax = plt.subplots(figsize=(8.6, 2.9))
    for y, (_, segs) in enumerate(rows):
        x = 0
        for w_, lab, kind in segs:
            ax.barh(y, w_, 0.58, left=x, color=AVEC if kind == 'inh' else AVEC_PALE,
                    hatch=None if kind == 'inh' else HATCH, edgecolor='white', linewidth=2)
            ax.text(x + w_ / 2, y, f'{lab}\n+{fr(w_, 0)} o', ha='center', va='center', fontsize=7.5,
                    color='white' if kind == 'inh' else INK,
                    bbox=None if kind == 'inh' else dict(boxstyle='square,pad=0.15', fc=AVEC_PALE, ec='none'))
            x += w_
        ax.text(x + 8, y, f'+{fr(x, 0)} o', va='center', fontsize=8.5, color=INK)
    ax.set_yticks(range(len(rows)), [r[0] for r in rows]); ax.invert_yaxis()
    ax.set_xlim(0, max(sent_d - sent_b, recv_d - recv_b) * 1.15)
    ax.set_xlabel('octets ajoutés par DPoP à chaque renouvellement (en-têtes et corps HTTP, hors TCP et TLS)')
    ax.grid(axis='y', visible=False)
    fig.suptitle('Surcoût réseau d\'un renouvellement : ce qui tient à DPoP, ce qui tient au PoC',
                 x=0.01, ha='left', fontsize=10.5, color=INK)
    fig.legend(handles=[Patch(color=AVEC, label='inhérent à DPoP : une preuve par requête'),
                        Patch(facecolor=AVEC_PALE, hatch=HATCH, label='dépend du format et du transport des jetons du PoC')],
               loc='upper left', ncol=2, bbox_to_anchor=(0.01, 0.92), fontsize=7.5)
    fig.tight_layout(rect=(0, 0, 1, 0.84))
    rest = f' Reste non attribué côté requête : {signed(req_rest, 0)} o.' if abs(req_rest) >= 0.5 else ''
    save(fig, out, 'fig4_surcout_reseau',
         'Octets data_sent / data_received de k6 rapportés au nombre de requêtes ; médiane des 9 exécutions valides par '
         'mode (10 à 100/s).' + rest + '\nEn-tête DPoP : ligne « DPoP: <preuve> », preuve ES256 de '
         f'{fr(header - 8, 0)} o avec une clé P-256 et une URL htu courte. cnf.jkt : ≈ +{fr(cookie / 2, 0)} o par jeton, car le PoC '
         'imbrique\nun JWS dans un JWE (double Base64URL) ; les deux jetons partent dans les cookies (Path=/) et reviennent '
         'deux fois (Set-Cookie et corps JSON).\nLa part du corps JSON est déduite (écart total de la réponse moins '
         'l\'écart des Set-Cookie).')


# ---------------------------------------------------------------- figure 5
def fig5_replay(S, out):
    jv = read(os.path.join(S, 'jvm_summary.csv'))
    fig, ax = plt.subplots(figsize=(6.8, 3.8))
    for b, style, lab in (('fullValidation', '-', 'Validation complète d\'une preuve (DpopProofValidator)'),
                          ('replayDetection', '--', 'Purge + insertion anti-rejeu seule')):
        rs = sorted((r for r in jv if r['benchmark'] == b), key=lambda r: int(r['storeSize']))
        x = [int(r['storeSize']) for r in rs]; y = [num(r['lat_mean_us']) for r in rs]
        ax.errorbar(x, y, yerr=[num(r['lat_err_us']) for r in rs], fmt=style, marker='o', ms=5, lw=1.8,
                    color=AVEC, capsize=2.5, label=lab)
        for xi, yi in zip(x, y):
            txt = f'{fr(yi, 0) if yi >= 10 else fr(yi, 2 if yi < 1 else 1)} µs'
            if xi == 0: ax.annotate(txt, (xi, yi), xytext=(-6, 4), textcoords='offset points', ha='right', fontsize=7.5)
            elif xi == 1200: ax.annotate(f'{txt} (1 200 jti)', (xi, yi), xytext=(7, -12) if b == 'fullValidation' else (4, 16),
                                         textcoords='offset points', ha='left', fontsize=7.5)
            else: ax.annotate(txt, (xi, yi), xytext=(0, 7), textcoords='offset points', ha='center', fontsize=7.5)
    ax.set_xticks([0, 12000, 60000], ['0', '12 000\n(100/s × 120 s)', '60 000\n(500/s × 120 s)'])
    ax.set_ylim(0, None); ax.set_xlim(-6000, 64000)
    ax.set_xlabel('jti conservés dans le store anti-rejeu (≈ preuves acceptées par seconde × TTL de 120 s)', labelpad=8)
    ax.set_ylabel('µs par opération\n(moyenne, IC 99,9 %)')
    fig.suptitle('Coût du store anti-rejeu du PoC : une purge en O(N) à chaque validation', x=0.01, ha='left',
                 fontsize=10.5, color=INK)
    ax.legend(loc='upper left', fontsize=8)
    fig.tight_layout(rect=(0, 0, 1, 0.95))
    save(fig, out, 'fig5_store_anti_rejeu',
         'JMH, 1 thread, vrai DpopProofValidator, taille du store maintenue constante. purgeExpiredJtis() parcourt toute '
         'la table (removeIf) à chaque preuve :\nle coût croît linéairement avec N. C\'est un choix d\'implémentation du '
         'PoC, pas une propriété de DPoP : un store ordonné par date d\'expiration\nou un cache à expiration native purge '
         'sans parcourir les entrées encore valides (non mesuré ici).\nPoint à 1 200 jti : régime de 10 preuves/s.')


# ---------------------------------------------------------------- figure 6
def parse_jfr(path):
    """Événements de contention et principal point d'attente dans TokenStore, par (débit, mode)."""
    res, cur = {}, None
    if not os.path.exists(path): return res
    for line in open(path, encoding='utf-8'):
        m = re.match(r'=== (\d+) req/s, (\w+)', line)
        if m: cur = (int(m.group(1)), m.group(2)); res[cur] = {}; continue
        if cur is None: continue
        m = re.match(r'\s*jdk\.JavaMonitorEnter\s+(\d+)', line)
        if m: res[cur]['monitor'] = int(m.group(1))
        m = re.match(r'\s*(\d+) com\.example\.dpoplab\.TokenStore\.(\w+)\(.*line: (\d+)', line)
        if m and 'wait' not in res[cur]: res[cur]['wait'] = (int(m.group(1)), m.group(2), m.group(3))
    return res


def fig6_saturation(S, out):
    tl = read(os.path.join(S, 'load_timeline.csv'))
    jfr = parse_jfr(os.path.join(S, 'jfr_hotspots.txt'))
    fig, axes = plt.subplots(2, 2, figsize=(10, 5.6), sharex=True)
    for j, rate in enumerate((250, 500)):
        for mode in ('bearer', 'dpop'):
            for rep in sorted({r['rep'] for r in tl}):
                rs = [r for r in tl if int(r['rate']) == rate and r['mode'] == mode and r['rep'] == rep]
                t = [num(r['t_s']) for r in rs]
                kw = dict(color=COLOR[mode], lw=1.2, alpha=0.9, ls='-' if mode == 'bearer' else (0, (4, 1.5)))
                axes[0][j].plot(t, [num(r['ok_per_s']) for r in rs], **kw)
                axes[1][j].plot(t, [num(r['p50_http_ms']) for r in rs], **kw)
        axes[0][j].axhline(rate, color=INK, ls=':', lw=1)
        axes[0][j].annotate('débit demandé', (232, rate), xytext=(0, 3), textcoords='offset points', ha='right',
                            va='bottom', fontsize=7.5, color=INK2)
        axes[0][j].set_ylim(0, rate * 1.2)
        axes[0][j].set_title(f'{rate}/s demandés : exécutions NON VALIDES', loc='left', color=INK, fontsize=9.5)
        axes[1][j].set_yscale('log'); axes[1][j].set_ylim(1, 2e4)
        axes[1][j].set_xlabel('secondes depuis le début de l\'exécution')
        for i in (0, 1):
            axes[i][j].axvspan(0, 120, color='#f3f2ef', zorder=0, lw=0)
        axes[0][j].text(60, rate * 0.06, 'échauffement', ha='center', fontsize=7.5, color=INK2)
        b, d = jfr.get((rate, 'bearer'), {}), jfr.get((rate, 'dpop'), {})
        if b.get('wait') and d.get('wait'):
            pos = (0.03, 0.95, 'left', 'top') if rate == 250 else (0.97, 0.05, 'right', 'bottom')
            axes[1][j].text(pos[0], pos[1],
                            f"JFR, répétition 1 : attentes de verrou > 20 ms\nsur TokenStore.{b['wait'][1]} "
                            f"(ligne {b['wait'][2]}, synchronized)\nsans DPoP : {fr(b['wait'][0], 0)}  ·  "
                            f"avec DPoP : {fr(d['wait'][0], 0)}",
                            transform=axes[1][j].transAxes, fontsize=7, color=INK, ha=pos[2], va=pos[3],
                            bbox=dict(boxstyle='round,pad=0.35', fc='white', ec=GRID))
    axes[0][0].set_ylabel('renouvellements\nréussis par seconde'); axes[1][0].set_ylabel('latence HTTP p50\n(ms, échelle log)')
    fig.suptitle('Saturation du PoC à forte charge, avec ou sans DPoP : la table des jetons, pas la cryptographie',
                 x=0.01, ha='left', fontsize=10.5, color=INK)
    fig.legend(handles=[Line2D([], [], color=SANS, lw=1.5, label='Sans DPoP'),
                        Line2D([], [], color=AVEC, lw=1.5, ls=(0, (4, 1.5)), label='Avec DPoP')],
               loc='upper right', ncol=2, bbox_to_anchor=(0.99, 1.0))
    fig.tight_layout(rect=(0, 0, 1, 0.95))
    save(fig, out, 'fig6_saturation_poc',
         'Tranches de 10 s, 3 répétitions par mode. Le débit baisse alors que la charge demandée est constante : le coût '
         'par renouvellement croît avec l\'état du serveur.\nJFR : contention sur le verrou global de TokenStore.refresh ; '
         'CPU dans revokeAccessForUser, qui parcourt toute la table des jetons (jamais purgée) à chaque\nrenouvellement. '
         'Ces exécutions sont exclues des comparaisons ; aucun débit maximal de DPoP n\'en est déduit.')


# ---------------------------------------------------------------- figure S1
def figS1_jvm_steps(S, out):
    jv = read(os.path.join(S, 'jvm_summary.csv'))
    names = {'parse': 'Parsing (JWS, en-tête, JWK)', 'publicKeyFromJwk': 'Reconstruction de la clé publique',
             'verifySignature': 'Vérification ES256', 'jwkThumbprint': 'Thumbprint JWK (RFC 7638)',
             'claimsExtract': 'Extraction des claims', 'claimsChecks': 'Contrôles htm / htu / iat',
             'replayDetection': 'Anti-rejeu, store vide'}
    rows = [next(r for r in jv if r['benchmark'] == b and r['storeSize'] in ('', '0')) for b in names]
    full = num(next(r for r in jv if r['benchmark'] == 'fullValidation' and r['storeSize'] == '0')['lat_mean_us'])
    fig, ax = plt.subplots(figsize=(7.2, 3.3))
    y = list(range(len(rows)))
    vals = [num(r['lat_mean_us']) for r in rows]
    ax.barh(y, vals, 0.55, color=AVEC, hatch=HATCH, linewidth=0)
    for yi, v in zip(y, vals):
        ax.text(v * 1.15, yi, f'{fr(v, 1 if v < 10 else 0) if v >= 1 else fr(v, 2)} µs', va='center', fontsize=8, color=INK)
    ax.set_xscale('log'); ax.set_xlim(0.08, 4000)
    ax.set_yticks(y, [names[r['benchmark']] for r in rows]); ax.invert_yaxis()
    ax.set_xlabel('µs par opération : moyenne JMH (échelle logarithmique)'); ax.grid(axis='y', visible=False)
    fig.suptitle('Étapes de la validation d\'une preuve, mesurées séparément', x=0.01, ha='left', fontsize=10.5, color=INK)
    fig.tight_layout(rect=(0, 0, 1, 0.95))
    save(fig, out, 'figS1_etapes_validation',
         f'Benchmarks JMH indépendants (1 thread) : ces valeurs ne s\'additionnent pas. La validation complète, mesurée '
         f'directement, vaut {fr(full, 0)} µs\navec un store vide (figure 2) ; la vérification ES256 en représente '
         f'l\'essentiel.')


def main(summary=None):
    S = summary or os.path.join(HERE, '..', 'results', 'summary')
    out = os.path.join(S, 'figures'); os.makedirs(out, exist_ok=True)
    print(f'figures dans {os.path.abspath(out)}')
    fig1_cpu(S, out); fig2_crypto(S, out); fig3_latency(S, out); fig4_network(S, out); fig5_replay(S, out)
    if os.path.exists(os.path.join(S, 'load_timeline.csv')): fig6_saturation(S, out)
    figS1_jvm_steps(S, out)


if __name__ == '__main__':
    ap = argparse.ArgumentParser(); ap.add_argument('--summary'); a = ap.parse_args()
    main(a.summary)
