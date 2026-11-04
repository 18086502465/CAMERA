#!/usr/bin/env python3
"""
通过 GitHub Git Data API 推送提交（绕过 github.com 的 CONNECT 隧道限制）。

背景：本机代理只对 api.github.com 放行 HTTPS，github.com 的 CONNECT 隧道返回 502，
      而 git 的 smart-HTTP 协议必须走 github.com。

策略：
  1. 取远端 main 的完整 tree（path -> sha）；
  2. 只上传**本次提交真正变更**的 blob（未变文件直接复用远端 sha，
     60MB 的 JAR / 100MB 的 exe 就靠这一步跳过重传）；
  3. 用变更后的条目构造新 tree、创建 commit、更新 main 引用。
"""
import base64
import json
import os
import subprocess
import sys
import time
import urllib.request

# ★ 凭据一律从环境变量读取，**禁止把 token 写进脚本**：
#   GitHub 的 secret scanning 会拦截含明文 PAT 的推送
#   （HTTP 422 "Repository rule violations found / Secret detected in content"），
#   而且明文 token 一旦入库就等于泄露，必须重置该 token。
PROXY = os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy') or ''
TOKEN = os.environ.get('GITHUB_TOKEN') or os.environ.get('GH_TOKEN') or ''
API = os.environ.get('GITHUB_API') or 'https://api.github.com/repos/18086502465/CAMERA'
# 本脚本位于 docs/scripts/ 下，仓库根需上溯三级
LOCAL = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

if not TOKEN:
    print('请先设置环境变量 GITHUB_TOKEN，例如：')
    print('  Git Bash   : export GITHUB_TOKEN=<你的 classic PAT>')
    print('  PowerShell: $env:GITHUB_TOKEN="<你的 classic PAT>"')
    raise SystemExit(2)

# PROXY 为空时直连，非空时走本地代理
_handlers = {'https': PROXY, 'http': PROXY} if PROXY else {}
opener = urllib.request.build_opener(urllib.request.ProxyHandler(_handlers))


def api(method, path, body=None, timeout=180):
    url = path if path.startswith('http') else API + path
    data = json.dumps(body).encode() if body is not None else None
    last = None
    for attempt in range(5):
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header('Authorization', 'token ' + TOKEN)
        req.add_header('Accept', 'application/vnd.github+json')
        req.add_header('User-Agent', 'workbuddy-push')
        if data:
            req.add_header('Content-Type', 'application/json')
        try:
            resp = opener.open(req, timeout=timeout)
            payload = resp.read()
            return json.loads(payload) if payload else {}
        except Exception as e:
            last = e
            detail = ''
            try:
                detail = e.read().decode('utf-8', 'replace')[:200]
            except Exception:
                pass
            code = getattr(e, 'code', 0)
            if code in (401, 403, 404):
                raise RuntimeError('API %s %s -> %s %s' % (method, path, code, detail))
            time.sleep(2 + attempt * 3)
    raise RuntimeError('API %s %s 失败: %s' % (method, path, last))


def git(*args):
    # strip() 很关键：subprocess 的 stdout 会带换行，不 strip 会导致
    # parent 与远端 sha 明明相同却判定为「不一致」
    return subprocess.run(['git'] + list(args), cwd=LOCAL,
                          capture_output=True, text=True, encoding='utf-8',
                          errors='replace').stdout.strip()


def git_raw(*args):
    return subprocess.run(['git'] + list(args), cwd=LOCAL, capture_output=True).stdout


def main():
    head = git('rev-parse', 'HEAD')
    parent = git('rev-parse', 'HEAD~1')

    branch = api('GET', '/branches/main')
    remote_head = branch['commit']['sha']
    print('本地 HEAD   :', head)
    print('本地 parent :', parent)
    print('远端 HEAD   :', remote_head)
    if remote_head != parent:
        print('!! 远端与本地 parent 不一致，终止（避免覆盖他人提交）')
        sys.exit(2)

    remote_tree = {}
    rt = api('GET', '/git/trees/%s?recursive=1' % remote_head)
    for t in rt.get('tree', []):
        remote_tree[t['path']] = (t['sha'], t['mode'], t['type'])

    ls = git('ls-tree', '-r', '-z', head)
    local_files = []
    for rec in ls.split('\0'):
        if not rec.strip():
            continue
        meta, path = rec.split('\t', 1)
        mode, otype, sha = meta.split()
        local_files.append((mode, otype, sha, path))
    print('本地文件数  :', len(local_files))

    new_entries = []
    uploaded = 0
    reused = 0
    for mode, otype, local_sha, path in local_files:
        if otype != 'blob':
            new_entries.append({'path': path, 'mode': mode, 'type': otype,
                                'sha': local_sha})
            continue
        r = remote_tree.get(path)
        if r and r[0] == local_sha and r[1] == mode:
            new_entries.append({'path': path, 'mode': mode, 'type': 'blob',
                                'sha': r[0]})
            reused += 1
            continue
        content = git_raw('cat-file', 'blob', local_sha)
        b = api('POST', '/git/blobs', {
            'content': base64.b64encode(content).decode(),
            'encoding': 'base64'
        })
        new_entries.append({'path': path, 'mode': mode, 'type': 'blob',
                            'sha': b['sha']})
        uploaded += 1
        if uploaded % 10 == 0:
            print('  已上传 blob %d 个（复用 %d 个）' % (uploaded, reused))
    print('blob 上传 %d 个，复用 %d 个' % (uploaded, reused))

    local_paths = {p for _, _, _, p in local_files}
    removed = [p for p in remote_tree if p not in local_paths]
    if removed:
        print('本次删除的文件 %d 个：' % len(removed))
        for p in removed[:8]:
            print('   -', p)
        if len(removed) > 8:
            print('   ... 共 %d 个' % len(removed))

    tree = api('POST', '/git/trees', {'tree': new_entries})
    print('新 tree sha :', tree['sha'])

    msg = git('log', '-1', '--format=%B', head)
    commit = api('POST', '/git/commits', {
        'message': msg,
        'tree': tree['sha'],
        'parents': [parent],
        'author': {
            'name': git('log', '-1', '--format=%an', head),
            'email': git('log', '-1', '--format=%ae', head),
            'date': git('log', '-1', '--format=%aI', head),
        }
    })
    print('新 commit   :', commit['sha'])

    ref = api('PATCH', '/git/refs/heads/main',
              {'sha': commit['sha'], 'force': False})
    print('main 已更新 ->', ref['object']['sha'])
    print('\n推送完成')


if __name__ == '__main__':
    main()
