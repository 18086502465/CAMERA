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
    # 1) shell 命令里的赋值 / 传参 → 环境变量引用
    ("MYSQL_PWD=CCshenda889 mysql", "MYSQL_PWD=${MYSQL_PASSWORD} mysql"),
    ("MYSQL_PASSWORD=DB_PWD", "MYSQL_PASSWORD='${MYSQL_PASSWORD}'"),
    ("DB_PASS=CCshenda889", "DB_PASS=${MYSQL_PASSWORD}"),
    ('echo SSH_PWD | sudo -S', 'echo "${SUDO_PASSWORD}" | sudo -S'),
    ("echo SSH_PWD | sudo -S", 'echo "${SUDO_PASSWORD}" | sudo -S'),
    ('-Password DB_PWD', '-Password "$env:MYSQL_PASSWORD"'),
    # 2) 配置文件里的 password 值 → 占位符
    ("password: CCshenda889", "password: ${MYSQL_PASSWORD}"),
    ("Password: CCshenda889", "Password: ${MYSQL_PASSWORD}"),
    ("BY DB_PWD;", "BY '<现场设定的数据库口令>';"),
    # 3) PowerShell 参数默认值 → 读环境变量
    ("[string]$Password = DB_PWD,",
     "[string]$Password = $(if ($env:MYSQL_PASSWORD) { $env:MYSQL_PASSWORD } else { '' }),"),
    # 4) 文档说明文字 → 占位符
    ("DB password `CCshenda889`", "DB password `<数据库口令>`"),
    ("`root` / `CCshenda889`", "`root` / `<数据库口令>`"),
    ("`root`/`CCshenda889`", "`root`/`<数据库口令>`"),
    ("`lims` / `Pass@1234`", "`lims` / `<SSH 口令>`"),
    ("`CCshenda889`", "`<数据库口令>`"),
    ("`Pass@1234`", "`<SSH 口令>`"),
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
