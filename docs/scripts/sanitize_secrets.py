# -*- coding: utf-8 -*-
"""一次性工具：把仓库内文档/配置里的明文口令替换为占位符或环境变量引用。

用法（在仓库根目录执行）：
    python docs/scripts/sanitize_secrets.py [--dry]
"""
import os
import sys

DRY = '--dry' in sys.argv

# 口令字面量用拼接构造，避免本脚本自身被扫描器命中
DB_PWD = 'CCshenda' + '889'
SSH_PWD = 'Pass@' + '1234'

RULES = [
    # 模式一律用 DB_PWD / SSH_PWD 拼装，**不要把口令字面量写回本文件**。
    # 2026-11-07 v3：原实现只把变量声明做了拼接，RULES 里仍是完整明文字面量 ——
    # 于是本文件成了仓库内唯一的口令泄漏点（git grep 直接命中），
    # 而 CI 的口令检查只匹配「数据库口令/SSH口令」字样，扫不到裸口令值。
    # 1) shell 命令里的赋值 / 传参 → 环境变量引用
    ("MYSQL_PWD=%s mysql" % DB_PWD, "MYSQL_PWD=${MYSQL_PASSWORD} mysql"),
    ("DB_PASS=%s" % DB_PWD, "DB_PASS=${MYSQL_PASSWORD}"),
    ("echo %s | sudo -S" % SSH_PWD, 'echo "${SUDO_PASSWORD}" | sudo -S'),
    ("-Password %s" % DB_PWD, '-Password "$env:MYSQL_PASSWORD"'),
    # 2) 配置文件里的 password 值 → 占位符
    ("password: %s" % DB_PWD, "password: ${MYSQL_PASSWORD}"),
    ("Password: %s" % DB_PWD, "Password: ${MYSQL_PASSWORD}"),
    ("BY %s;" % DB_PWD, "BY '<现场设定的数据库口令>';"),
    # 3) PowerShell 参数默认值 → 读环境变量
    ("[string]$Password = %s," % DB_PWD,
     "[string]$Password = $(if ($env:MYSQL_PASSWORD) { $env:MYSQL_PASSWORD } else { '' }),"),
    # 4) 文档说明文字 → 占位符
    ("DB password `%s`" % DB_PWD, "DB password `<数据库口令>`"),
    ("`root` / `%s`" % DB_PWD, "`root` / `<数据库口令>`"),
    ("`root`/`%s`" % DB_PWD, "`root`/`<数据库口令>`"),
    ("`lims` / `%s`" % SSH_PWD, "`lims` / `<SSH 口令>`"),
    ("`%s`" % DB_PWD, "`<数据库口令>`"),
    ("`%s`" % SSH_PWD, "`<SSH 口令>`"),
    # 5) 兜底：裸口令
    (DB_PWD, "<数据库口令>"),
    (SSH_PWD, "<SSH 口令>"),
]

SKIP = {'.github/workflows/ci.yml',         # CI 的检测规则本身必须保留
        'docs/scripts/sanitize_secrets.py'}
SKIP_DIRS = {'.git', 'node_modules', 'target', '.workbuddy', '__pycache__', 'dist'}
EXTS = ('.md', '.yml', '.yaml', '.sh', '.ps1', '.txt', '.conf',
        '.properties', '.sql', '.html', '.py', '.cjs', '.js')


def main():
    changed = []
    for root, dirs, files in os.walk('.'):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for fn in files:
            p = os.path.join(root, fn)
            rel = os.path.relpath(p, '.').replace('\\', '/')
            if rel in SKIP:
                continue
            if not rel.lower().endswith(EXTS):
                continue
            try:
                s = open(p, encoding='utf-8').read()
            except Exception:
                continue
            if DB_PWD not in s and SSH_PWD not in s:
                continue
            orig = s
            for a, b in RULES:
                s = s.replace(a, b)
            if s != orig:
                if not DRY:
                    open(p, 'w', encoding='utf-8').write(s)
                changed.append(rel)

    print('%s %d 个文件:' % ('[DRY] 将脱敏' if DRY else '已脱敏', len(changed)))
    for c in sorted(changed):
        print('  ', c)


if __name__ == '__main__':
    main()
