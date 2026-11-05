#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""接口快照「键结构树」严格比对（DDD 重构验证）。

与 api_snapshot.py 的 diff 区别：
  - api_snapshot.py 的 diff 比对「归一化后的值」，值变了会报出来（需人工判定是否为数据漂移）；
  - 本脚本**只比对键路径集合**（忽略所有值），用于证明「响应结构零变化」——
    这才是 DDD 重构「行为不变」的硬指标。

用法：
    python3 compare_structure.py <旧快照目录> <新快照目录>
"""
import json
import os
import sys


def load_norm(d):
    """加载 <name>.norm.json（归一化后的快照）。"""
    out = {}
    for fn in os.listdir(d):
        if fn.endswith('.norm.json'):
            name = fn[:-len('.norm.json')]
            path = os.path.join(d, fn)
            try:
                with open(path, encoding='utf-8') as f:
                    out[name] = json.load(f)
            except Exception as e:
                out[name] = {'__error__': str(e)}
    return out


def keys_of(obj, prefix='', acc=None):
    """递归收集所有键路径（忽略值）。"""
    if acc is None:
        acc = set()
    if isinstance(obj, dict):
        for k, v in obj.items():
            p = prefix + '/' + str(k)
            acc.add(p)
            keys_of(v, p, acc)
    elif isinstance(obj, list):
        # 列表只展开第一项（快照本身只保留前 3 项，结构相同）
        if obj:
            keys_of(obj[0], prefix + '[]', acc)
    return acc


def main():
    old_dir, new_dir = sys.argv[1], sys.argv[2]
    old = load_norm(old_dir)
    new = load_norm(new_dir)

    common = sorted(set(old) & set(new))
    only_old = sorted(set(old) - set(new))
    only_new = sorted(set(new) - set(old))

    same = 0
    diffs = []
    for name in common:
        ko = keys_of(old[name])
        kn = keys_of(new[name])
        if ko == kn:
            same += 1
        else:
            diffs.append((name, sorted(ko - kn), sorted(kn - ko)))

    print('比对端点数: %d' % len(common))
    print('键结构完全一致: %d' % same)
    print('键结构有差异: %d' % len(diffs))
    if only_old:
        print('仅旧快照存在: %s' % ', '.join(only_old))
    if only_new:
        print('仅新快照存在: %s' % ', '.join(only_new))
    print()
    for name, miss, extra in diffs:
        print('!! %s' % name)
        if miss:
            print('   缺失键路径: %s' % ', '.join(miss[:10]))
        if extra:
            print('   新增键路径: %s' % ', '.join(extra[:10]))
    if not diffs and not only_old and not only_new:
        print('OK  所有端点响应键结构零变化 —— 行为不变')
        return 0
    return 1


if __name__ == '__main__':
    sys.exit(main())
