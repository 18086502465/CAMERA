#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""实体 ↔ 建库脚本 列名静态对账（NEW-09，2026-11-06 v5 评审）。

背景
----
`fact_anomaly_alert` 有两套互不一致的结构描述：
  * `sql/deploy/01_init_schema_nodb.sql` 里是旧形状（station_id / severity / message ...）；
  * 实体 `FactAnomalyAlert` 与 Repository 里写的是新形状（workstation_id / alert_type /
    is_resolved / resolved_by / resolved_at ...）。
新列全靠 `04_align_entity_columns.sql` 的裸 `ALTER TABLE ADD COLUMN` 补上，
而 04 不可重入（MySQL 不支持 ADD COLUMN IF NOT EXISTS），现场是手工迁移过的库。

后果：全新库能跑、现场库能不能跑取决于「04 有没有跑全」，
而一旦缺列，`resolveCameraAlert` 的 UPDATE 会被监听器的
`catch (DataAccessException)` 吞掉 —— 表现为「告警永远不恢复」，日志里只有一行 WARN。

本脚本不连数据库，纯静态对账：把实体声明的列 vs 建库脚本声明的列做差集。

用法
----
    python3 docs/scripts/check_entity_vs_ddl.py            # 报告差异，退出码 0/1
    python3 docs/scripts/check_entity_vs_ddl.py --all      # 顺带列出全部逐表结果

退出码：0 = 无差异；1 = 存在「实体有、DDL 无」的列（部署前必修）。
"""
import argparse
import glob
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
MODEL_GLOB = os.path.join(ROOT, 'fact-product', 'src', 'main', 'java', 'com', 'wms',
                          '**', 'domain', 'model', '*.java')
DDL_GLOB = os.path.join(ROOT, 'fact-product', 'src', 'main', 'resources', 'sql', 'deploy', '*.sql')

# @TableName 之外还需要扫描的 mapper 注释/Repository INSERT 列（可选，暂不覆盖）


def camel_to_snake(name):
    s = re.sub(r'(?<!^)(?=[A-Z])', '_', name).lower()
    return s


def parse_entities():
    """返回 [(文件, 表名, {列名: 来源说明})]

    规则（与 MyBatis-Plus 的映射规则对齐）：
      * `@TableField("col")` / `@TableField(value="col")` → 列名 col
      * `@TableField(exist = false)` → **不是表字段，跳过**（含联表展示字段、confirmPassword 之类）
      * `@TableId(value="col")` → 列名 col（`imageId`/`detectionId` 这类字段名与列名不同）
      * 其余普通 `private Xxx yyy;` → 驼峰转下划线
    """
    entities = []
    for path in glob.glob(MODEL_GLOB, recursive=True):
        src = open(path, encoding='utf-8').read()
        m = re.search(r'@TableName\(\s*(?:value\s*=\s*)?"([^"]+)"', src)
        if not m:
            continue
        table = m.group(1)
        columns = {}
        pending = []          # 字段声明前的注解缓冲
        for raw in src.splitlines():
            line = raw.strip()
            if not line:
                continue
            if line.startswith('/*') or line.startswith('*') or line.startswith('//'):
                continue
            if line.startswith('@'):
                pending.append(line)
                continue
            dm = re.match(r'(?:private|protected|public)\s+(?!static)[\w<>,\[\]\.\s]+?\s+(\w+)\s*(?:=[^;]*)?;', line)
            if not dm:
                # 方法声明/其他语句清空缓冲，避免注解误配到后面的字段
                if not line.startswith('}'):
                    pending = []
                continue
            field = dm.group(1)
            ann = ' '.join(pending)
            pending = []
            if re.search(r'exist\s*=\s*false', ann):
                continue
            idm = re.search(r'@TableId\([^)]*?(?:value\s*=\s*)?"([^"]+)"', ann)
            if idm:
                columns[idm.group(1)] = 'tableId:' + field
                continue
            fm = re.search(r'@TableField\(\s*(?:value\s*=\s*)?"([^"]+)"', ann)
            if fm:
                columns[fm.group(1)] = 'explicit:' + field
                continue
            columns.setdefault(camel_to_snake(field), 'field:' + field)
        entities.append((os.path.relpath(path, ROOT), table, columns))
    return entities


def parse_ddl():
    """返回 {表名: set(列名)}，覆盖 CREATE TABLE 与 ALTER TABLE ... ADD COLUMN"""
    tables = {}
    for path in sorted(glob.glob(DDL_GLOB)):
        src = open(path, encoding='utf-8').read()
        # ---- CREATE TABLE `x` ( ... ); ----
        for m in re.finditer(r'CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?\s*\((.*?)\n\)\s*[^;]*;',
                             src, re.S | re.I):
            table, body = m.group(1), m.group(2)
            cols = tables.setdefault(table, set())
            for raw in body.split('\n'):
                line = raw.strip().rstrip(',')
                if not line:
                    continue
                low = line.lower()
                if low.startswith(('primary ', 'unique ', 'key ', 'index ', 'constraint ', 'foreign ', '--')):
                    continue
                cm = re.match(r'`?(\w+)`?\s+\w', line)
                if cm:
                    cols.add(cm.group(1))
        # ---- ALTER TABLE `x` ADD COLUMN `y` ----
        for m in re.finditer(r'ALTER\s+TABLE\s+`?(\w+)`?\s+ADD\s+(?:COLUMN\s+)?`?(\w+)`?', src, re.I):
            tables.setdefault(m.group(1), set()).add(m.group(2))
    return tables


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--all', action='store_true', help='列出所有实体的逐表结果')
    args = ap.parse_args()

    entities = parse_entities()
    ddl = parse_ddl()
    if not entities:
        print('未找到任何实体（检查 MODEL_GLOB）')
        return 1

    missing_tables, missing_cols, covered = [], [], 0
    for rel_path, table, columns in sorted(entities, key=lambda x: x[1]):
        if table not in ddl:
            missing_tables.append((table, rel_path, len(columns)))
            continue
        diff = {c: src for c, src in columns.items() if c not in ddl[table]}
        if diff:
            missing_cols.append((table, rel_path, diff))
        else:
            covered += 1
            if args.all:
                print(f'OK   {table:34s} {len(columns):3d} 列  ({rel_path})')

    print()
    print('=' * 78)
    print(f'实体 {len(entities)} 个 ｜ 建库脚本覆盖表 {len(ddl)} 个 ｜ 完全对得上 {covered} 个')
    print('=' * 78)

    if missing_tables:
        print('\n【建库脚本里完全没有这张表】（可能是运行期 ensure 建表，需人工确认）')
        for table, rel_path, n in missing_tables:
            print(f'  - {table:34s} 实体声明 {n} 列  ({rel_path})')

    if missing_cols:
        print('\n【实体有、建库脚本没有的列】（全新库安装后必定 Unknown column）')
        for table, rel_path, diff in missing_cols:
            print(f'  - {table}  ({rel_path})')
            for col, origin in sorted(diff.items()):
                print(f'      · {col}   <- {origin}')
    else:
        print('\n✅ 所有实体列都能在建库脚本里找到')

    bad = len(missing_cols)
    print()
    if bad:
        print(f'❌ {bad} 张表存在缺列，部署前请在 sql/deploy/ 补 ALTER（或在实体上删列）')
    else:
        print('✅ 无缺列')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
