#!/usr/bin/env python3
"""Analyse des résultats bruts : tableaux (CSV + Markdown) et graphiques.

    python3 scripts/analyze.py [--browser DIR] [--jvm DIR] [--load DIR] [--out DIR]

Par défaut : la dernière campagne de chaque type sous results/. Les résultats des
microbenchmarks (navigateur, JMH) et ceux de bout en bout (k6) sont analysés et présentés
séparément ; aucun percentile n'est additionné entre mesures indépendantes.
"""
import argparse, csv, glob, gzip, json, math, os, statistics as st, subprocess, sys
from collections import defaultdict

sys.stdout.reconfigure(encoding='utf-8')
HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, '..', 'results')
JFR = os.environ.get('JFR_BIN', r'C:\Program Files\Java\jdk-25.0.2\bin\jfr.exe')
JTI_TTL_S = 120  # DpopProofValidator.JTI_TTL_SECONDS


def latest(kind):
    d = sorted(x for x in glob.glob(os.path.join(RES, kind, '*')) if os.path.isdir(x)
               and not os.path.basename(x).startswith('smoke'))
    return d[-1] if d else None


def pct(xs, p):
    """Percentile par interpolation linéaire (méthode « inclusive », comme numpy par défaut)."""
    if not xs: return float('nan')
    s = sorted(xs); k = (len(s) - 1) * p / 100; f = math.floor(k); c = min(f + 1, len(s) - 1)
    return s[f] + (s[c] - s[f]) * (k - f)


def describe(xs):
    return {'n': len(xs), 'mean': st.fmean(xs), 'median': pct(xs, 50), 'min': min(xs), 'max': max(xs),
            'p95': pct(xs, 95), 'p99': pct(xs, 99), 'std': st.stdev(xs) if len(xs) > 1 else 0.0}


def write_csv(path, rows):
    if not rows: return
    keys = list(rows[0].keys())
    with open(path, 'w', newline='', encoding='utf-8') as f:
        w = csv.DictWriter(f, keys, lineterminator='\n'); w.writeheader(); w.writerows(rows)


def fmt(v, nd=3):
    if v is None or (isinstance(v, float) and math.isnan(v)): return '–'
    if isinstance(v, float):
        if v == 0: return '0'
        a = abs(v)
        return f'{v:.{nd}f}' if a >= 0.001 else f'{v:.2e}'
    return str(v)


def md_table(rows, cols, headers=None):
    headers = headers or cols
    out = ['| ' + ' | '.join(headers) + ' |', '|' + '|'.join('---' for _ in cols) + '|']
    for r in rows: out.append('| ' + ' | '.join(fmt(r.get(c)) for c in cols) + ' |')
    return '\n'.join(out)


# ---------------------------------------------------------------- navigateur
BROWSER_LABELS = {
    'baseline_await': 'Référence : await vide',
    'keygen_p256': '1. Génération paire ECDSA P-256',
    'export_jwk': '2. Export JWK clé publique',
    'initialize_dpop_keypair': '1+2. initializeDPopKeyPair() (PoC)',
    'jwk_thumbprint': '3. Thumbprint JWK, computeJkt() (PoC)',
    'ecdsa_sign_only': '4. Signature ECDSA seule',
    'build_dpop_proof': '5. Preuve complète, buildDPopProof() (PoC)',
}


def analyze_browser(d, out):
    raw = json.load(open(os.path.join(d, 'raw.json'), encoding='utf-8'))
    meta, results = raw['meta'], raw['results']
    by_op = defaultdict(lambda: {'samples': [], 'series_medians': [], 'batch_means': []})
    for r in results:
        for s in r['series']:
            o = by_op[s['op']]
            o['samples'] += s['samplesMs']
            o['series_medians'].append(pct(s['samplesMs'], 50))
            o['batch_means'].append(s['batchMs'] / s['batchOps'])
    rows = []
    for op, o in by_op.items():
        d_ = describe(o['samples'])
        sm = o['series_medians']
        rows.append({'op': op, 'label': BROWSER_LABELS.get(op, op), **{k + '_ms': v for k, v in d_.items() if k != 'n'},
                     'n': d_['n'], 'series': len(sm),
                     'batch_mean_ms': st.fmean(o['batch_means']),
                     'series_median_min_ms': min(sm), 'series_median_max_ms': max(sm),
                     'series_median_cv_pct': 100 * st.stdev(sm) / st.fmean(sm) if len(sm) > 1 and st.fmean(sm) else 0.0})
    order = list(BROWSER_LABELS)
    rows.sort(key=lambda r: order.index(r['op']) if r['op'] in order else 99)
    write_csv(os.path.join(out, 'browser_summary.csv'), rows)
    return meta, rows


# ---------------------------------------------------------------- JMH
JMH_LABELS = {
    'parse': 'Parsing de la preuve (JWS + en-tête + JWK)',
    'publicKeyFromJwk': 'Reconstruction de la clé publique',
    'verifySignature': 'Vérification ES256',
    'jwkThumbprint': 'Thumbprint JWK (RFC 7638)',
    'claimsExtract': 'Extraction des claims',
    'claimsChecks': 'Contrôles htm / htu / iat',
    'replayDetection': 'Anti-rejeu (purge + insertion)',
    'fullValidation': 'Validation complète (DpopProofValidator)',
    'issueRefreshToken': 'Émission refresh token (JWS+JWE)',
    'verifyRefreshToken': 'Vérification refresh token (JWE+JWS)',
}


def open_jmh(d):
    """jmh.json (sortie brute de JMH) ou, à défaut, sa copie compressée jmh.json.gz (versionnée)."""
    p = os.path.join(d, 'jmh.json')
    return open(p, encoding='utf-8') if os.path.exists(p) else gzip.open(p + '.gz', 'rt', encoding='utf-8')


def analyze_jvm(d, out):
    data = json.load(open_jmh(d))
    rows = {}
    for b in data:
        name = b['benchmark'].rsplit('.', 1)[1]
        params = b.get('params') or {}
        key = (name, params.get('storeSize', ''), params.get('bound', ''))
        r = rows.setdefault(key, {'benchmark': name, 'label': JMH_LABELS.get(name, name),
                                  'storeSize': params.get('storeSize', ''), 'bound': params.get('bound', '')})
        pm, sec = b['primaryMetric'], b.get('secondaryMetrics', {})
        if b['mode'] == 'thrpt':
            f = 1e6 if pm['scoreUnit'] == 'ops/us' else 1.0
            r['thrpt_ops_s'] = pm['score'] * f
            r['thrpt_err_ops_s'] = pm['scoreError'] * f if pm['scoreError'] == pm['scoreError'] else float('nan')
            forks = [sum(it) / len(it) * f for it in pm['rawData']]
            r['thrpt_fork_min'] = min(forks); r['thrpt_fork_max'] = max(forks)
            a = sec.get('gc.alloc.rate.norm') or sec.get('·gc.alloc.rate.norm')
            if a: r['alloc_B_per_op'] = a['score']; r['alloc_err'] = a['scoreError']
        elif b['mode'] == 'sample':
            f = 1.0 if pm['scoreUnit'] == 'us/op' else float('nan')
            p = pm['scorePercentiles']
            r['lat_mean_us'] = pm['score'] * f
            r['lat_err_us'] = pm['scoreError'] * f
            r['lat_p50_us'] = p['50.0'] * f; r['lat_p95_us'] = p['95.0'] * f; r['lat_p99_us'] = p['99.0'] * f
            r['lat_max_us'] = p['100.0'] * f
    order = list(JMH_LABELS)
    res = sorted(rows.values(), key=lambda r: (order.index(r['benchmark']), int(r['storeSize'] or 0), r['bound']))
    write_csv(os.path.join(out, 'jvm_summary.csv'), res)
    meta = {k: data[0].get(k) for k in ('jmhVersion', 'jvm', 'jdkVersion', 'vmName', 'vmVersion', 'forks',
                                         'warmupIterations', 'warmupTime', 'measurementIterations',
                                         'measurementTime', 'threads', 'jvmArgs')}
    return meta, res


# ---------------------------------------------------------------- charge HTTP
def jfr_window(path, t0, t1):
    """GC et allocations du backend pendant la fenêtre [t0, t1] (epoch s), depuis l'enregistrement JFR."""
    if not os.path.exists(path) or os.path.getsize(path) == 0: return {}
    r = subprocess.run([JFR, 'print', '--json', '--events', 'jdk.GarbageCollection,jdk.GCHeapSummary', path],
                       capture_output=True)
    ev = json.loads(r.stdout.decode('utf-8', 'replace'))['recording']['events']
    from datetime import datetime
    ts = lambda e: datetime.fromisoformat(e['values']['startTime'].replace('Z', '+00:00')).timestamp()
    dur_ms = lambda s: (float(s.rstrip('ns').rstrip('m').rstrip('u') if False else 0))
    def parse_dur(v):  # JFR JSON : "PT0.0123S"
        if isinstance(v, (int, float)): return v / 1e6
        v = v.replace('PT', '').replace('S', '')
        return float(v) * 1000 if v else 0.0
    gcs = [e for e in ev if e['type'] == 'jdk.GarbageCollection' and t0 <= ts(e) <= t1]
    heap = sorted(((ts(e), e['values']['when'], e['values']['heapUsed'], e['values']['gcId']) for e in ev
                   if e['type'] == 'jdk.GCHeapSummary'), key=lambda x: (x[3], x[1] != 'Before GC'))
    # Allocation entre deux GC = occupation avant le GC n − occupation après le GC n−1.
    alloc, after_prev, after_vals = 0, None, []
    for t, when, used, gid in heap:
        if when == 'Before GC':
            if after_prev is not None and t0 <= t <= t1: alloc += max(0, used - after_prev[1])
        else:
            after_prev = (t, used)
            if t0 <= t <= t1: after_vals.append(used)
    return {'gc_count': len(gcs), 'gc_pause_ms': sum(parse_dur(e['values']['sumOfPauses']) for e in gcs),
            'alloc_bytes_window': alloc, 'heap_after_gc_mib_mean': st.fmean(after_vals) / 2**20 if after_vals else float('nan'),
            'gc_names': sorted({e['values']['name'] for e in gcs})}


def analyze_run(rd):
    run = json.load(open(os.path.join(rd, 'run.json')))
    summary = json.load(open(os.path.join(rd, 'summary.json')))
    rate, mode = run['rate'], run['mode']
    http_ms, total_ms, sent, recv, dropped, accepted, hdr, cookie = [], [], [], [], [], [], [], []
    ok = failed = logins = 0
    meas_ts = []
    rows = []
    with gzip.open(os.path.join(rd, 'k6.csv.gz'), 'rt', encoding='utf-8') as f:
        rd_ = csv.reader(f); next(rd_)
        for m, ts, val, *_rest in rd_:
            extra = _rest[5] if len(_rest) > 5 else ''  # colonne extra_tags
            meas = 'phase=measure' in extra
            ts = int(ts); val = float(val)
            if m == 'accepted_proofs': accepted.append(ts)
            if m in ('data_sent', 'data_received', 'dropped_iterations', 'http_reqs'): rows.append((m, ts, val)); continue
            if not meas: continue
            meas_ts.append(ts)
            if m == 'refresh_http_ms': http_ms.append(val)
            elif m == 'refresh_total_ms': total_ms.append(val)
            elif m == 'refresh_ok': ok += 1
            elif m == 'refresh_failed': failed += 1
            elif m == 'logins': logins += 1
            elif m == 'dpop_header_bytes': hdr.append(val)
            elif m == 'cookie_header_bytes': cookie.append(val)
    w0, w1 = (min(meas_ts), max(meas_ts)) if meas_ts else (0, 0)
    # k6 émet data_sent / data_received par lots, pas par requête : on rapporte le total de la
    # fenêtre au nombre de requêtes HTTP de la même fenêtre (horodatages à la seconde).
    nreq = 0
    for m, ts, val in rows:
        if w0 <= ts <= w1:
            if m == 'data_sent': sent.append(val)
            elif m == 'data_received': recv.append(val)
            elif m == 'dropped_iterations': dropped.append(val)
            else: nreq += 1
    # Les itérations de la phase de mesure sont celles planifiées pendant DURATION secondes.
    window_s = summary['durationS']
    cw = run['cpuWindow']; dt = cw['t1'] - cw['t0']
    be_cores = (cw['backendUsageUsec'][1] - cw['backendUsageUsec'][0]) / 1e6 / dt
    k6_cores = (cw['k6UsageUsec'][1] - cw['k6UsageUsec'][0]) / 1e6 / dt
    achieved = ok / window_s
    # Occupation du store anti-rejeu, déduite du code : chaque preuve acceptée y reste 120 s.
    acc = sorted(accepted)
    occ = []
    if mode == 'dpop' and acc:
        import bisect
        for t in range(w0, w1 + 1):
            occ.append(bisect.bisect_right(acc, t) - bisect.bisect_right(acc, t - JTI_TTL_S))
    jfr = jfr_window(os.path.join(rd, 'backend.jfr'), cw['t0'], cw['t1'])
    total_req = ok + failed
    r = {
        'rep': run['rep'], 'rate': rate, 'mode': mode,
        'requested_rps': rate, 'achieved_ok_rps': achieved,
        'refresh_ok': ok, 'refresh_failed': failed, 'error_rate': failed / total_req if total_req else float('nan'),
        'dropped_iterations': sum(dropped), 'logins_in_window': logins,
        'http_p50_ms': pct(http_ms, 50), 'http_p95_ms': pct(http_ms, 95), 'http_p99_ms': pct(http_ms, 99),
        'http_mean_ms': st.fmean(http_ms) if http_ms else float('nan'),
        'total_p50_ms': pct(total_ms, 50), 'total_p95_ms': pct(total_ms, 95), 'total_p99_ms': pct(total_ms, 99),
        'backend_cpu_cores': be_cores, 'backend_cpu_ms_per_refresh': be_cores * 1000 / achieved if achieved else float('nan'),
        'k6_cpu_cores': k6_cores,
        'backend_mem_mib_end': cw['backendMemBytes'][1] / 2**20,
        'sent_bytes_per_req': sum(sent) / nreq if nreq else float('nan'),
        'recv_bytes_per_req': sum(recv) / nreq if nreq else float('nan'),
        'dpop_header_bytes_median': pct(hdr, 50) if hdr else float('nan'),
        'cookie_header_bytes_median': pct(cookie, 50) if cookie else float('nan'),
        'replay_store_mean': st.fmean(occ) if occ else 0, 'replay_store_max': max(occ) if occ else 0,
        'token_table_total_end': run['tokenTable']['total'],
        'gc_count': jfr.get('gc_count'), 'gc_pause_ms': jfr.get('gc_pause_ms'),
        'alloc_mib_per_s': jfr.get('alloc_bytes_window', 0) / 2**20 / dt if jfr else float('nan'),
        'alloc_kib_per_refresh': jfr.get('alloc_bytes_window', 0) / 1024 / (achieved * dt) if jfr and achieved else float('nan'),
        'heap_after_gc_mib_mean': jfr.get('heap_after_gc_mib_mean'),
        'k6_proof_gen_mean_us': (summary.get('setup') or {}).get('proofGenMeanUs'),
    }
    # Validité : mesures exploitables seulement si la charge demandée est tenue sans erreur
    # et si le générateur n'est pas saturé (4 CPU alloués à k6).
    reasons = []
    if not (r['error_rate'] <= 0.01): reasons.append('erreurs > 1 %')
    if achieved < 0.95 * rate: reasons.append('débit atteint < 95 % du débit demandé')
    if k6_cores > 3.2: reasons.append('générateur k6 > 80 % de ses 4 CPU')
    r['valid'] = not reasons
    r['invalid_reason'] = '; '.join(reasons)
    # Échantillons compacts de la phase de mesure, versionnés avec les synthèses.
    with gzip.open(os.path.join(rd, 'measure_samples.csv.gz'), 'wt', encoding='utf-8', newline='') as f:
        w = csv.writer(f, lineterminator='\n'); w.writerow(['metric', 'value'])
        for v in http_ms: w.writerow(['refresh_http_ms', f'{v:.3f}'])
        for v in total_ms: w.writerow(['refresh_total_ms', int(v)])
    return r


def analyze_load(d, out):
    runs = [analyze_run(rd) for rd in sorted(glob.glob(os.path.join(d, 'rep*', '*rps_*')))
            if os.path.exists(os.path.join(rd, 'run.json'))]
    write_csv(os.path.join(out, 'load_runs.csv'), runs)
    agg = []
    for (rate, mode) in sorted({(r['rate'], r['mode']) for r in runs}):
        rs = [r for r in runs if r['rate'] == rate and r['mode'] == mode]
        val = [r for r in rs if r['valid']]
        row = {'rate': rate, 'mode': mode, 'runs': len(rs), 'valid_runs': len(val)}
        for k in ('achieved_ok_rps', 'error_rate', 'http_p50_ms', 'http_p95_ms', 'http_p99_ms', 'total_p50_ms',
                  'total_p95_ms', 'total_p99_ms', 'backend_cpu_cores', 'backend_cpu_ms_per_refresh', 'k6_cpu_cores',
                  'gc_count', 'gc_pause_ms', 'alloc_mib_per_s', 'alloc_kib_per_refresh', 'heap_after_gc_mib_mean',
                  'sent_bytes_per_req', 'recv_bytes_per_req', 'replay_store_mean',
                  'token_table_total_end', 'dropped_iterations', 'dpop_header_bytes_median',
                  'cookie_header_bytes_median'):
            xs = [r[k] for r in (val or rs) if r[k] is not None and not (isinstance(r[k], float) and math.isnan(r[k]))]
            row[k + '_median'] = pct(xs, 50) if xs else float('nan')
            row[k + '_min'] = min(xs) if xs else float('nan')
            row[k + '_max'] = max(xs) if xs else float('nan')
        row['stats_from'] = 'valides' if val else 'TOUTES (aucune valide)'
        agg.append(row)
    write_csv(os.path.join(out, 'load_aggregate.csv'), agg)
    # Surcoût DPoP apparié : même répétition, même débit, deux exécutions valides.
    pairs = []
    for rate in sorted({r['rate'] for r in runs}):
        for rep in sorted({r['rep'] for r in runs}):
            a = [r for r in runs if r['rate'] == rate and r['rep'] == rep and r['mode'] == 'dpop']
            b = [r for r in runs if r['rate'] == rate and r['rep'] == rep and r['mode'] == 'bearer']
            if not (a and b): continue
            a, b = a[0], b[0]
            p = {'rate': rate, 'rep': rep, 'both_valid': a['valid'] and b['valid']}
            for k in ('http_p50_ms', 'http_p95_ms', 'http_p99_ms', 'total_p50_ms', 'total_p95_ms', 'total_p99_ms',
                      'backend_cpu_ms_per_refresh', 'alloc_kib_per_refresh', 'sent_bytes_per_req',
                      'recv_bytes_per_req', 'cookie_header_bytes_median'):
                p[k + '_delta'] = a[k] - b[k]
                p[k + '_rel_pct'] = 100 * (a[k] / b[k] - 1) if b[k] else float('nan')
            pairs.append(p)
    write_csv(os.path.join(out, 'load_pairs.csv'), pairs)
    ov = []
    for rate in sorted({p['rate'] for p in pairs}):
        ps = [p for p in pairs if p['rate'] == rate and p['both_valid']]
        row = {'rate': rate, 'valid_pairs': len(ps)}
        for k in ('http_p50_ms', 'http_p95_ms', 'http_p99_ms', 'total_p50_ms', 'backend_cpu_ms_per_refresh',
                  'alloc_kib_per_refresh', 'sent_bytes_per_req', 'recv_bytes_per_req'):
            for suf in ('_delta', '_rel_pct'):
                xs = [p[k + suf] for p in ps if not math.isnan(p[k + suf])]
                row[k + suf + '_median'] = pct(xs, 50) if xs else float('nan')
                row[k + suf + '_min'] = min(xs) if xs else float('nan')
                row[k + suf + '_max'] = max(xs) if xs else float('nan')
        ov.append(row)
    write_csv(os.path.join(out, 'load_overhead.csv'), ov)
    return runs, agg, ov


# ---------------------------------------------------------------- graphiques
# Voir scripts/figures.py : les figures sont produites à partir des seules synthèses versionnées.


# ---------------------------------------------------------------- rapport
def report(out, browser, jvm, load, dirs):
    L = ['# Synthèse des résultats', '',
         'Généré par `scripts/analyze.py` à partir des résultats bruts :', '']
    for k, v in dirs.items(): L.append(f'- {k} : `{os.path.relpath(v, os.path.join(RES, ".."))}`' if v else f'- {k} : (absent)')
    if browser:
        meta, rows = browser
        L += ['', '## 1. Navigateur (Web Crypto, Chromium)', '',
              f"Chromium {meta['browserVersion']}, Playwright {meta['playwrightVersion']}, "
              f"{meta['params']['series']} séries × {meta['params']['iterations']} mesures "
              f"(+{meta['params']['warmup']} d'échauffement), résolution de performance.now() : "
              f"{min(meta['timerResolutionMs']) * 1000:.0f} µs (origine isolée : {meta['crossOriginIsolated']}).", '',
              md_table(rows, ['label', 'median_ms', 'mean_ms', 'p95_ms', 'p99_ms', 'min_ms', 'max_ms', 'std_ms',
                              'batch_mean_ms', 'series_median_cv_pct'],
                       ['Opération', 'Médiane (ms)', 'Moyenne', 'p95', 'p99', 'Min', 'Max', 'Écart-type',
                        'Moyenne en lot', 'CV médianes inter-séries (%)']), '',
              f"Taille d'une preuve : {meta['proofSize']['min']}–{meta['proofSize']['max']} octets "
              f"(ligne d'en-tête « DPoP: … » : {meta['proofSize']['headerLineBytes']} octets)."]
    if jvm:
        meta, rows = jvm
        L += ['', '## 2. Serveur (JMH)', '',
              f"JMH {meta['jmhVersion']}, {meta['vmName']} {meta['vmVersion']}, {meta['forks']} forks, "
              f"{meta['warmupIterations']}×{meta['warmupTime']} d'échauffement, "
              f"{meta['measurementIterations']}×{meta['measurementTime']} de mesure, {meta['threads']} thread.", '',
              md_table(rows, ['label', 'storeSize', 'bound', 'lat_mean_us', 'lat_err_us', 'lat_p50_us', 'lat_p95_us',
                              'lat_p99_us', 'thrpt_ops_s', 'thrpt_err_ops_s', 'thrpt_fork_min', 'thrpt_fork_max',
                              'alloc_B_per_op'],
                       ['Opération', 'Store', 'cnf.jkt', 'Moyenne (µs)', '± IC99,9', 'p50', 'p95', 'p99',
                        'Débit (ops/s)', '± IC99,9', 'Fork min', 'Fork max', 'Alloc (o/op)'])]
    if load and load[0]:
        runs, agg, ov = load
        L += ['', '## 3. Bout en bout (k6)', '', '### Par configuration (médiane des répétitions valides [min–max])', '']
        def mm(a, k, nd=2):
            return f"{a[k + '_median']:.{nd}f} [{a[k + '_min']:.{nd}f}–{a[k + '_max']:.{nd}f}]"
        L.append('| Débit | Mode | Valides | Atteint (req/s) | p50 HTTP (ms) | p95 | p99 | Total p50 (ms) | CPU backend (cœurs) | CPU ms/refresh | Alloc KiB/refresh | Pauses GC (ms) | Store anti-rejeu | k6 (cœurs) |')
        L.append('|' + '---|' * 14)
        for a in agg:
            L.append(f"| {a['rate']} | {a['mode']} | {a['valid_runs']}/{a['runs']} | {mm(a, 'achieved_ok_rps', 1)} | "
                     f"{mm(a, 'http_p50_ms')} | {mm(a, 'http_p95_ms')} | {mm(a, 'http_p99_ms')} | {mm(a, 'total_p50_ms', 0)} | "
                     f"{mm(a, 'backend_cpu_cores')} | {mm(a, 'backend_cpu_ms_per_refresh')} | {mm(a, 'alloc_kib_per_refresh', 0)} | "
                     f"{mm(a, 'gc_pause_ms', 0)} | {mm(a, 'replay_store_mean', 0)} | {mm(a, 'k6_cpu_cores')} |")
        L += ['', '### Surcoût DPoP apparié (même répétition, même débit ; médiane des paires valides [min–max])', '',
              '| Débit | Paires | Δ p50 HTTP (ms) | Δ p50 % | Δ p95 % | Δ p99 % | Δ CPU ms/refresh | Δ CPU % | Δ octets envoyés/req | Δ octets reçus/req |',
              '|' + '---|' * 10]
        for o in ov:
            def m2(k, nd=2):
                return f"{o[k + '_median']:.{nd}f} [{o[k + '_min']:.{nd}f}–{o[k + '_max']:.{nd}f}]" if o['valid_pairs'] else '–'
            L.append(f"| {o['rate']} | {o['valid_pairs']} | {m2('http_p50_ms_delta')} | {m2('http_p50_ms_rel_pct', 1)} | "
                     f"{m2('http_p95_ms_rel_pct', 1)} | {m2('http_p99_ms_rel_pct', 1)} | {m2('backend_cpu_ms_per_refresh_delta')} | "
                     f"{m2('backend_cpu_ms_per_refresh_rel_pct', 1)} | {m2('sent_bytes_per_req_delta', 0)} | "
                     f"{m2('recv_bytes_per_req_delta', 0)} |")
        bad = [r for r in runs if not r['valid']]
        if bad:
            L += ['', '### Exécutions non valides (exclues des comparaisons)', '']
            for r in bad:
                L.append(f"- rep {r['rep']}, {r['rate']} req/s, {r['mode']} : {r['invalid_reason']} "
                         f"(atteint {r['achieved_ok_rps']:.1f} req/s, erreurs {100 * r['error_rate']:.2f} %, "
                         f"dropped_iterations {r['dropped_iterations']:.0f}, k6 {r['k6_cpu_cores']:.2f} cœurs)")
    open(os.path.join(out, 'RESULTS.md'), 'w', encoding='utf-8').write('\n'.join(L) + '\n')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--browser'); ap.add_argument('--jvm'); ap.add_argument('--load'); ap.add_argument('--out')
    a = ap.parse_args()
    pick = lambda v, kind: None if v == 'none' else (v or latest(kind))  # « none » : ignorer ce benchmark
    dirs = {'navigateur': pick(a.browser, 'browser'), 'JMH': pick(a.jvm, 'jvm'), 'charge': pick(a.load, 'load')}
    out = a.out or os.path.join(RES, 'summary'); os.makedirs(out, exist_ok=True)
    browser = analyze_browser(dirs['navigateur'], out) if dirs['navigateur'] else None
    jvm = analyze_jvm(dirs['JMH'], out) if dirs['JMH'] and glob.glob(os.path.join(dirs['JMH'], 'jmh.json*')) else None
    load = analyze_load(dirs['charge'], out) if dirs['charge'] else None
    report(out, browser, jvm, load, dirs)
    print(f'synthèse écrite dans {os.path.abspath(out)}')



if __name__ == '__main__':
    main()
