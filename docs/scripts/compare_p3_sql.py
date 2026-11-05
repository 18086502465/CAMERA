#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P3 批次② SQL 搬运一致性校验。

把 Java 源码里的字符串字面量拼接（"a" + "b"）还原成完整 SQL，
再比对「原 Controller」与「新 Repository」的 SQL 集合是否一致。

用法：
    python3 compare_sql.py <原Controller目录> <新Repository目录>
"""
import os
import re
import sys


def extract_sqls(path):
    """从 Java 文件中提取所有字符串字面量（还原 + 拼接）。"""
    src = open(path, encoding='utf-8').read()
    # 去掉行注释与块注释，避免注释里的 SQL 干扰
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)

    # 找出所有双引号字符串字面量序列
    out = []
    for m in re.finditer(r'"((?:[^"\\]|\\.)*)"', src):
        out.append(m.group(1))

    # 按 " + " 连续拼接：重新扫描源码找字面量链
    chains = []
    i = 0
    lit = re.compile(r'"((?:[^"\\]|\\.)*)"')
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
        chains.append(''.join(parts))
        i = j
    return chains


def normalize_sql(s):
    """归一化：去空白、去尾部分号、转小写（仅用于比对，不改实际执行文本）。"""
    s = re.sub(r'\s+', ' ', s).strip().rstrip(';')
    return s.lower()


def is_sql_like(s):
    t = s.strip()
    if not t:
        return False
    up = t.upper()
    return (up.startswith('SELECT ') or up.startswith('UPDATE ') or
            up.startswith('INSERT ') or up.startswith('DELETE ') or
            up.startswith('CREATE TABLE'))


def collect(files):
    sqls = []
    for f in files:
        for c in extract_sqls(f):
            if is_sql_like(c):
                sqls.append((os.path.basename(f), normalize_sql(c), c))
    return sqls


def main():
    old_dir, new_dir = sys.argv[1], sys.argv[2]
    old_files = [os.path.join(old_dir, f) for f in sorted(os.listdir(old_dir)) if f.endswith('.java')]
    new_files = [os.path.join(new_dir, f) for f in sorted(os.listdir(new_dir)) if f.endswith('.java')]

    old = collect(old_files)
    new = collect(new_files)

    old_set = {}
    for f, n, raw in old:
        old_set.setdefault(n, []).append((f, raw))
    new_set = {}
    for f, n, raw in new:
        new_set.setdefault(n, []).append((f, raw))

    print('原 Controller SQL 条数: %d (去重 %d)' % (len(old), len(old_set)))
    print('新 Repository SQL 条数: %d (去重 %d)' % (len(new), len(new_set)))
    print()

    missing = [n for n in old_set if n not in new_set]
    extra = [n for n in new_set if n not in old_set]

    if missing:
        print('!! 缺失（原有但新无） %d 条:' % len(missing))
        for n in missing:
            print('   -', old_set[n][0][1][:160])
    if extra:
        print('!! 新增（新有但原有无） %d 条:' % len(extra))
        for n in extra:
            print('   +', new_set[n][0][1][:160])
    if not missing and not extra:
        print('OK  SQL 集合完全一致（逐条文本相同）')

    # 重复项提示
    print()
    for name, d in (('原', old_set), ('新', new_set)):
        dups = {n: v for n, v in d.items() if len(v) > 1}
        if dups:
            print('注: %s文件中有 %d 条重复 SQL（同一段文本出现多次）:' % (name, len(dups)))
            for n, v in list(dups.items())[:5]:
                print('   x%d  %s' % (len(v), v[0][1][:120]))

    return 1 if (missing or extra) else 0


if __name__ == '__main__':
    sys.exit(main())
