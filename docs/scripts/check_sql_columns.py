#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SQL 列名静态校验：检查 Repository 里的 SQL 是否引用了不存在的列。

背景（2026-11-05 实测）：
  DDD P3 批次④ 搬迁时发现既有 bug —— 产线统计的「缺陷图片兜底·工位排行」SQL
  写的是 `w.station_id`，但 `dim_workstation` 的主键列是 **`workstation_id`**
  （没有 station_id 列）→ MySQL 报 "Unknown column 'w.station_id' in 'on clause'"，
  走兜底路径（聚合表无数据时）必然 500。

  这个 bug 之所以长期没被发现，是因为它**只在特定分支触发**，
  且「SQL 文本搬运一致性比对」抓不到（搬迁前后文本相同 = 忠实搬运 = bug 一起搬过来）。

本脚本用「真实表结构」反查 SQL 里引用的列，尽早发现这类问题。

用法：
    python3 check_sql_columns.py <列名清单文件> [Repository 目录]
    列名清单文件格式：每行 "表名<TAB>列1,列2,列3"（由 mysql information_schema 导出）
"""
import os
import re
import sys

# 表别名 → 真实表名（SQL 里用的别名与表名不一定一致）
# 说明：同一个字母在不同 SQL 里可能指不同表（如 f 在缺陷图片 SQL 里是
# defect_image_file，在配置导出 SQL 里是 dim_factory），因此按「表名集合」
# 逐个候选表判定 —— 只要**所有候选表都没有该列**才判定为可疑。
ALIAS_CANDIDATES = {
    'f': ['defect_image_file', 'dim_factory'],
    'b': ['defect_image_box'],
    'c': ['dim_camera', 'camera_status'],
    'w': ['dim_workstation'],
    'ws': ['dim_workstation'],
    'pl': ['dim_production_line'],
    'cs': ['camera_status'],
    'm': ['cfg_camera_defect_mapping'],
    'l': ['dim_production_line'],
    'bt': ['defect_image_box'],
    'bs': ['defect_image_box'],
    'bkw': ['defect_image_box'],
    'agg': ['agg_quality_30m', 'agg_defect_type_30m', 'agg_quality_day', 'agg_defect_type_day'],
    'd': ['dim_defect_type'],
    'p': ['dim_product'],
    's': ['dim_shift'],
    'sc': ['cfg_analytics_dashboard'],
    'sw': ['cfg_analytics_widget'],
}


def load_cols(path):
    """加载 列名清单 → {表名: set(列名)}"""
    tables = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or '\t' not in line:
                continue
            tbl, cols = line.split('\t', 1)
            tables[tbl.strip()] = set(c.strip() for c in cols.split(',') if c.strip())
    return tables


def extract_sql_chains(src):
    """提取 Java 源码里的字符串拼接链（还原成完整 SQL）"""
    out = []
    lit = re.compile(r'"((?:[^"\\]|\\.)*)"')
    i = 0
    while True:
        m = lit.search(src, i)
        if not m:
            break
        parts = [m.group(1)]
        j = m.end()
        while True:
            mm = re.match(r'\s*\+\s*', src[j:])
            if not mm:
                break
            j2 = j + mm.end()
            m2 = lit.match(src, j2)
            if not m2:
                break
            parts.append(m2.group(1))
            j = m2.end()
        out.append(''.join(parts))
        i = j
    return out


def tables_in_sql(sql, tables):
    """找出 SQL 里出现的真实表名"""
    found = set()
    for t in tables:
        if re.search(r'\b' + re.escape(t) + r'\b', sql, re.I):
            found.add(t)
    return found


def alias_cols(sql):
    """找出 "别名.列名" 形式引用的列 → {别名: set(列名)}"""
    res = {}
    for m in re.finditer(r'\b([a-z][a-z0-9_]*)\.([a-z][a-z0-9_]*)\b', sql, re.I):
        alias, col = m.group(1).lower(), m.group(2).lower()
        res.setdefault(alias, set()).add(col)
    return res


def main():
    if len(sys.argv) < 2:
        print('用法: python3 check_sql_columns.py <列名清单文件> [Repository 目录]')
        return 2
    tables = load_cols(sys.argv[1])
    repo_dirs = sys.argv[2:] or [
        'fact-product/src/main/java/com/wms/analytics/infra/repository',
        'fact-product/src/main/java/com/wms/defect/infra/repository',
        'fact-product/src/main/java/com/wms/device/infra/repository',
        'fact-product/src/main/java/com/wms/inspection/infra/repository',
        'fact-product/src/main/java/com/wms/system/infra/repository',
    ]

    problems = []
    checked = 0
    for d in repo_dirs:
        if not os.path.isdir(d):
            continue
        for fn in sorted(os.listdir(d)):
            if not fn.endswith('.java'):
                continue
            path = os.path.join(d, fn)
            src = open(path, encoding='utf-8').read()
            for sql in extract_sql_chains(src):
                if not re.match(r'\s*(SELECT|INSERT|UPDATE|DELETE|WITH)\b', sql, re.I):
                    continue
                checked += 1
                real_tables = tables_in_sql(sql, tables)
                if not real_tables:
                    continue
                for alias, cols in alias_cols(sql).items():
                    # 别名 → 真实表
                    if alias in ('select', 'from', 'where', 'group', 'order', 'by', 'and',
                                 'or', 'not', 'null', 'as', 'on', 'left', 'inner', 'join',
                                 'case', 'when', 'then', 'else', 'end', 'limit', 'offset',
                                 'union', 'all', 'distinct', 'in', 'is', 'between', 'like',
                                 'asc', 'desc', 'count', 'sum', 'coalesce', 'ifnull', 'if',
                                 'round', 'max', 'min', 'avg', 'date', 'format', 'now', 'values',
                                 'insert', 'update', 'delete', 'set', 'duplicate', 'key',
                                 'information_schema', 'columns', 'tables', 'statistics',
                                 'schema', 'database', 'table_name', 'column_name',
                                 'index_name', 'table_schema', 'current_timestamp', 'null'):
                        continue
                    candidates = ALIAS_CANDIDATES.get(alias)
                    if candidates is None:
                        if alias in tables:
                            candidates = [alias]
                        else:
                            continue
                    candidates = [t for t in candidates if t in tables]
                    if not candidates:
                        continue
                    # 只有当**所有候选表都没有**该列时才算可疑
                    missing_all = [c for c in cols
                                   if all(c not in tables[t] for t in candidates)]
                    if missing_all:
                        problems.append((path, sorted(missing_all),
                                         '/'.join(candidates), sql[:110]))

    print('检查 SQL 语句: %d 条' % checked)
    if not problems:
        print('OK  未发现引用不存在的列')
        return 0
    print('!! 发现 %d 处可疑列引用：' % len(problems))
    seen = set()
    for path, missing, target, snippet in problems:
        key = (path, tuple(missing))
        if key in seen:
            continue
        seen.add(key)
        print('   %s' % path)
        print('     表 %s 缺少列: %s' % (target, ', '.join(missing)))
        print('     SQL: %s...' % snippet.replace('\n', ' '))
    return 1


if __name__ == '__main__':
    sys.exit(main())
