#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""接口快照录制 + 结构 diff（DDD 重构 P0 基线工具）

用法（在服务器上执行）：
    python3 api_snapshot.py record <输出目录>     # 录制快照
    python3 api_snapshot.py diff <旧目录> <新目录> # 结构比对

原理：
  - 登录获取 admin token，对全部只读 GET 端点发请求；
  - 每个端点保存：HTTP 状态码 + 响应头摘要 + 完整响应体；
  - diff 时把动态值归一化（数字→<N>、时间→<T>、其余保留），再逐键比对 ——
    结构或字段类型变化会报出，纯数值波动不会误报。
"""
import json
import os
import re
import sys
import urllib.request
import urllib.error

BASE = 'http://127.0.0.1:8091'
ADMIN = os.environ.get('ADMIN_PASSWORD', 'admin123')

# 只读 GET 端点（含必要查询参数）。文件类端点单独记录元信息。
ENDPOINTS = [
    ('auth_me',        '/api/auth/me'),
    ('user_page',      '/api/user?page=1&size=5'),
    ('user_me',        '/api/user/me'),
    ('company',        '/api/company'),
    ('factories',      '/api/factories'),
    ('lines',          '/api/lines'),
    ('cascade_all',    '/api/cascade/all'),
    ('cameras',        '/api/cameras'),
    ('cameras_stats',  '/api/cameras/stats'),
    ('cameras_recog',  '/api/cameras/recognize-stats'),
    ('alerts_active',  '/api/alerts/active'),
    ('holiday_range',  '/api/holiday/range?startDate=2026-11-01&endDate=2026-11-30'),
    ('holiday_date',   '/api/holiday/date?date=2026-11-05'),
    ('holiday_workday','/api/holiday/is-workday?date=2026-11-05'),
    ('stats_brands',   '/api/stats/brands'),
    ('stats_lines',    '/api/stats/lines'),
    ('stats_workstations', '/api/stats/workstations'),
    ('stats_dashboard_overview', '/api/stats/dashboard-overview'),
    ('stats_defect_overview',    '/api/stats/defect-overview?startDate=2026-11-01&endDate=2026-11-04'),
    ('stats_defect_trend',       '/api/stats/defect-trend?startDate=2026-11-01&endDate=2026-11-04'),
    ('stats_yield_trend',        '/api/stats/yield-trend?startDate=2026-11-01&endDate=2026-11-04'),
    ('stats_station_ranking',    '/api/stats/station-ranking?startDate=2026-11-01&endDate=2026-11-04'),
    ('stats_defect_heatmap',     '/api/stats/defect-heatmap?startDate=2026-11-01&endDate=2026-11-04'),
    ('stats_brand_compare',      '/api/stats/brand-compare?startDate=2026-11-01&endDate=2026-11-04'),
    ('stats_product_shift_yield','/api/stats/product-shift-yield?startDate=2026-11-01&endDate=2026-11-04'),
    ('stats_workstation_trend',  '/api/stats/workstation-trend?startDate=2026-11-01&endDate=2026-11-04'),
    ('stats_ws_defect_trend',    '/api/stats/workstation-defect-trend?startDate=2026-11-01&endDate=2026-11-04'),
    ('logs_op_page',   '/api/logs/operation?page=1&size=5'),
    ('logs_op_summary','/api/logs/operation/summary'),
    ('analytics_root', '/api/v1/analytics'),
    ('analytics_dashboards',     '/api/v1/analytics/dashboards'),
    ('analytics_dash_default',   '/api/v1/analytics/dashboards/default'),
    ('analytics_line_summary',   '/api/v1/analytics/line-summary'),
    ('analytics_metadata',       '/api/v1/analytics/metadata'),
    ('analytics_agg_jobs',       '/api/v1/analytics/aggregation/jobs'),
    ('analytics_agg_jobs_recent','/api/v1/analytics/aggregation/jobs/recent'),
    ('analytics_agg_schedule',   '/api/v1/analytics/aggregation/schedule'),
    ('di_camera_summaries', '/api/v1/defect-images/camera-summaries'),
    ('di_history',     '/api/v1/defect-images/history'),
    ('di_recent',      '/api/v1/defect-images/recent'),
    ('detection_query','/api/detection-history/query'),
    ('sys_settings',   '/api/v1/system/settings'),
    ('server_metrics', '/api/v1/system/server/metrics'),
    ('cfg_defect_types',      '/api/v1/config/defect-types'),
    ('cfg_products',          '/api/v1/config/products'),
    ('cfg_camera_mappings',   '/api/v1/config/camera-defect-mappings'),
    ('cfg_mapping_exceptions','/api/v1/config/mapping-exceptions'),
]

FILE_ENDPOINTS = [
    ('cfg_template',   '/api/config-import/template'),
]


def token():
    body = json.dumps({'username': 'admin', 'password': ADMIN}).encode()
    req = urllib.request.Request(BASE + '/api/auth/login', data=body,
                                 headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)['data']['token']


def fetch(path, tok):
    req = urllib.request.Request(BASE + path, headers={'Authorization': 'Bearer ' + tok})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def normalize(obj):
    """递归把动态值归一化：数字→N，疑似时间字符串→T，空数组/对象保留结构。"""
    if isinstance(obj, dict):
        return {k: normalize(v) for k, v in sorted(obj.items())}
    if isinstance(obj, list):
        return [normalize(v) for v in obj[:3]]          # 列表只取前 3 个元素的结构
    if isinstance(obj, bool):
        return 'B'
    if isinstance(obj, (int, float)):
        return 'N'
    if isinstance(obj, str):
        if re.match(r'^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}', obj) or re.match(r'^\d{10,13}$', obj):
            return 'T'
        return obj
    return str(obj)


def record(outdir):
    os.makedirs(outdir, exist_ok=True)
    tok = token()
    summary = []
    for name, path in ENDPOINTS:
        status, headers, body = fetch(path, tok)
        with open(os.path.join(outdir, name + '.json'), 'wb') as f:
            f.write(b'HTTP ' + str(status).encode() + b'\n' + body)
        try:
            norm = normalize(json.loads(body.decode('utf-8')))
            kind = 'json'
        except Exception:
            norm = {'_raw_len': len(body), '_ctype': headers.get('Content-Type', '')}
            kind = 'raw'
        with open(os.path.join(outdir, name + '.norm.json'), 'w', encoding='utf-8') as f:
            json.dump(norm, f, ensure_ascii=False, indent=1)
        summary.append('%s\t%s\t%s\t%d bytes' % (name, status, kind, len(body)))
    for name, path in FILE_ENDPOINTS:
        status, headers, body = fetch(path, tok)
        meta = {'status': status, 'ctype': headers.get('Content-Type', ''),
                'size': len(body)}
        with open(os.path.join(outdir, name + '.meta.json'), 'w') as f:
            json.dump(meta, f, indent=1)
        summary.append('%s\t%s\tfile\t%d bytes' % (name, status, len(body)))
    with open(os.path.join(outdir, '_summary.txt'), 'w') as f:
        f.write('\n'.join(summary) + '\n')
    print('已录制 %d 个端点 -> %s' % (len(ENDPOINTS) + len(FILE_ENDPOINTS), outdir))
    ok = sum(1 for s in summary if s.split('\t')[1] == '200')
    print('HTTP 200: %d / %d' % (ok, len(summary)))
    for s in summary:
        if not s.split('\t')[1].startswith('2'):
            print('  非成功:', s)


def diff(old, new):
    names = sorted(f[:-10] for f in os.listdir(old) if f.endswith('.norm.json'))
    changed, same = [], []
    for n in names:
        a = os.path.join(old, n + '.norm.json')
        b = os.path.join(new, n + '.norm.json')
        if not os.path.exists(b):
            changed.append((n, '新目录缺失'))
            continue
        sa = open(a, encoding='utf-8').read()
        sb = open(b, encoding='utf-8').read()
        if sa == sb:
            same.append(n)
        else:
            # 找出第一处差异
            la, lb = sa.splitlines(), sb.splitlines()
            for i in range(max(len(la), len(lb))):
                va = la[i] if i < len(la) else '<EOF>'
                vb = lb[i] if i < len(lb) else '<EOF>'
                if va != vb:
                    changed.append((n, '行%d: %s -> %s' % (i + 1, va.strip()[:80], vb.strip()[:80])))
                    break
    print('结构一致: %d / %d' % (len(same), len(names)))
    if changed:
        print('结构变化 %d 项:' % len(changed))
        for n, d in changed:
            print('  %s: %s' % (n, d))
        return 1
    print('✅ 全部端点结构指纹一致')
    return 0


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'record':
        record(sys.argv[2])
    elif cmd == 'diff':
        sys.exit(diff(sys.argv[2], sys.argv[3]))
    else:
        print(__doc__)
