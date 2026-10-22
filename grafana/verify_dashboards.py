#!/usr/bin/env python3
"""Re-run every panel query in dashboards/ against a live ADAS and report the result.

The dashboards themselves are the source of truth: this reads the SQL back out of
`dashboards/*.json`, resolves the same placeholders Grafana's frontend would (the `__from` /
`__to` date formatters and each dashboard's own template variable), and posts each statement to
`POST /v1/queries/execute-sql`. It needs no Grafana — only a reachable ADAS.

It verifies the SQL contract, not the rendering: a query that passes here can still render
badly, and panel transformations are a frontend concern this script never exercises.

    # local ADAS, security.mode=none
    python grafana/verify_dashboards.py

    # a secured deployment
    ADAS_BASE_URL=https://adas.example.com ADAS_API_KEY=$DIAL_API_KEY \
        python grafana/verify_dashboards.py

Exit status is 0 only when every query returns 200. A query returning exactly 1000 rows is
reported as AT CAP: ADAS's hard maximum is 1000 and it does not say when it truncated, so a panel
sitting on that number is silently showing partial data — see docs/grafana-dashboards.md, "The
operating envelope".
"""
import datetime
import glob
import json
import os
import re
import sys
import urllib.error
import urllib.request

BASE = os.environ.get('ADAS_BASE_URL', os.environ.get('ADAS_URL', 'http://localhost:5000')).rstrip('/')
KEY = os.environ.get('ADAS_API_KEY', '')
HERE = os.path.dirname(os.path.abspath(__file__))
DASHBOARDS = os.path.join(HERE, 'dashboards')
TIMEOUT = int(os.environ.get('ADAS_TIMEOUT', '180'))


def resolve_time(spec, now):
    """Resolve a Grafana dashboard time spec ('now', 'now-30d', or an absolute ISO instant)."""
    if spec == 'now':
        return now
    m = re.fullmatch(r'now-(\d+)([dhm])', spec or '')
    if m:
        n, unit = int(m.group(1)), m.group(2)
        delta = {'d': datetime.timedelta(days=n),
                 'h': datetime.timedelta(hours=n),
                 'm': datetime.timedelta(minutes=n)}[unit]
        return now - delta
    return datetime.datetime.strptime(spec, '%Y-%m-%dT%H:%M:%S.%fZ').replace(
        tzinfo=datetime.timezone.utc)


def variable_values(dash):
    """Every template variable's full option list, which is what `All` expands to."""
    out = {}
    for var in (dash.get('templating') or {}).get('list') or []:
        vals = [o['value'] for o in var.get('options') or [] if o.get('value') != '$__all']
        if not vals and isinstance(var.get('query'), str):
            vals = [v for v in var['query'].split(',') if v]
        out[var['name']] = vals
    return out


def interpolate(sql, t0, t1, variables):
    sql = sql.replace('${__from:date:YYYY-MM-DD HH:mm:ss}', t0.strftime('%Y-%m-%d %H:%M:%S'))
    sql = sql.replace('${__to:date:YYYY-MM-DD HH:mm:ss}', t1.strftime('%Y-%m-%d %H:%M:%S'))
    for name, vals in variables.items():
        sql = sql.replace('${%s:singlequote}' % name, ', '.join("'%s'" % v for v in vals))
    return sql


def run(sql):
    headers = {'Content-Type': 'application/json'}
    if KEY:
        headers['Api-Key'] = KEY
    req = urllib.request.Request(BASE + '/v1/queries/execute-sql',
                                 data=json.dumps({'sql': sql}).encode('utf-8'),
                                 headers=headers, method='POST')
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as err:
        body = err.read().decode('utf-8', 'replace')
        try:
            body = json.loads(body)
        except ValueError:
            pass
        return err.code, body
    except Exception as err:                                      # noqa: BLE001 - report, don't raise
        return 0, {'message': '%s: %s' % (type(err).__name__, err)}


def panels_of(dash):
    """Flatten the panel tree, including panels nested inside collapsed rows."""
    found = []

    def walk(panels):
        for panel in panels:
            if panel.get('type') == 'row':
                walk(panel.get('panels') or [])
                continue
            found.append(panel)
            if panel.get('panels'):
                walk(panel['panels'])

    walk(dash.get('panels') or [])
    return found


def sql_of(target):
    """Pull the statement out of an Infinity REST target, or a Flight SQL one."""
    if target.get('rawSql'):
        return target['rawSql']
    data = (target.get('url_options') or {}).get('data')
    if not data:
        return None
    try:
        return json.loads(data).get('sql')
    except ValueError:
        return None


def main():
    files = sorted(glob.glob(os.path.join(DASHBOARDS, '*.json')))
    if not files:
        print('no dashboards found in %s' % DASHBOARDS)
        return 1
    now = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
    failures, at_cap, empty, total = [], [], [], 0
    print('ADAS %s%s' % (BASE, '  (with Api-Key)' if KEY else '  (no credentials)'))

    for path in files:
        dash = json.load(open(path, encoding='utf-8'))
        rng = dash.get('time') or {'from': 'now-6h', 'to': 'now'}
        t0, t1 = resolve_time(rng['from'], now), resolve_time(rng['to'], now)
        variables = variable_values(dash)
        print('\n=== %s   %s .. %s' % (os.path.basename(path),
                                       t0.strftime('%Y-%m-%d %H:%M'), t1.strftime('%Y-%m-%d %H:%M')))
        for panel in panels_of(dash):
            for target in panel.get('targets') or []:
                sql = sql_of(target)
                if not sql:
                    continue
                total += 1
                label = 'p%s%s %s' % (panel.get('id'), target.get('refId', 'A'),
                                      str(panel.get('title'))[:44])
                code, body = run(interpolate(sql, t0, t1, variables))
                if code != 200:
                    msg = body.get('message', body) if isinstance(body, dict) else body
                    print('  FAIL %-52s %s %s' % (label, code, str(msg)[:150]))
                    failures.append((os.path.basename(path), label, code, str(msg)[:300]))
                    continue
                rows = body.get('rows') or []
                note = ''
                if len(rows) >= 1000:
                    note, _ = '  <-- AT CAP, panel may be truncated', at_cap.append(label)
                elif not rows:
                    note, _ = '  <-- no rows, panel will be empty', empty.append(label)
                print('  OK   %-52s rows=%-5d%s' % (label, len(rows), note))

    print('\n%s\n%d/%d queries returned 200' % ('=' * 78, total - len(failures), total))
    if at_cap:
        print('AT THE 1000-ROW CAP (%d): %s' % (len(at_cap), ', '.join(at_cap)))
    if empty:
        print('EMPTY (%d): %s' % (len(empty), ', '.join(empty)))
    for fname, label, code, msg in failures:
        print('\nFAILED %s %s (%s)\n  %s' % (fname, label, code, msg))
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
