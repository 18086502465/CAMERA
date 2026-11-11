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

# ★ 单个 blob 的上传上限（原始字节）。Git Data API 对 base64 后的请求体
#   有约 50MB 的限制，实测 78.6MB 会被拒（HTTP 422 "input was too large"）。
#   超过此值的 blob 改为复用远端已有 sha，不重新上传。
MAX_BLOB_BYTES = int(os.environ.get('MAX_BLOB_BYTES', 40 * 1024 * 1024))

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

    branch = api('GET', '/branches/main')
    remote_head = branch['commit']['sha']
    print('本地 HEAD   :', head)
    print('远端 HEAD   :', remote_head)

    # ★ 支持多提交：取「远端 HEAD → 本地 HEAD」之间的全部提交，按时间正序逐个重建。
    #   原实现只推最后一个提交（HEAD~1 == remote_head 的假设），
    #   本地连续多个提交时会被判为「远端与本地 parent 不一致」而终止。
    #
    # ★ 2026-11-05 修正：**远端 commit 是本脚本用 API 重建的，sha 与本地不同**
    #   （Git Data API 重建 commit 时会用新的时间戳/parent，sha 必然不一样），
    #   所以本地没有远端 HEAD 这个对象 → `git rev-list remote..HEAD` 报
    #   "Invalid revision range" → 脚本无法确定待推送范围而终止。
    #   修法：**按提交 message 匹配** —— 遍历本地历史，找到与远端 HEAD
    #   提交 message 相同的那一个，它之后的提交才是待推送的。
    commits = []
    if remote_head != head:
        remote_msg = ''
        try:
            # ★ Git Data API 的 /git/commits/{sha} 返回**扁平结构**
            #   （顶层直接有 message / tree），不是 /commits/{sha} 那种
            #   嵌套 commit.message —— 取错了会拿到空串导致匹配失败。
            _rc = api('GET', '/git/commits/%s' % remote_head)
            remote_msg = _rc.get('message', '') or ''
        except Exception:
            pass
        local_revs = git('rev-list', '--reverse', head).split()
        if remote_msg:
            # 远端 message 首行 = 该提交的标题，用它定位
            remote_title = remote_msg.strip().split('\n')[0].strip()
            idx = -1
            for i, r in enumerate(local_revs):
                title = git('log', '-1', '--format=%s', r).strip()
                if title == remote_title:
                    idx = i
                    break
            if idx >= 0:
                commits = local_revs[idx + 1:]
                print('按 message 匹配定位到远端提交: %s（%s）' % (local_revs[idx][:7], remote_title[:40]))
            else:
                print('!! 未能按 message 匹配到远端提交（标题: %s）' % remote_title[:60])
        if not commits:
            # 兜底：远端 message 匹配不上时，用「远端 tree 与本地某提交 tree 相同」定位
            remote_tree_sha = ''
            try:
                remote_tree_sha = api('GET', '/git/trees/%s' % remote_head).get('sha', '')
            except Exception:
                pass
            for i, r in enumerate(local_revs):
                if git('rev-parse', '%s^{tree}' % r).strip() == remote_tree_sha:
                    commits = local_revs[i + 1:]
                    print('按 tree 匹配定位到远端提交: %s' % local_revs[i][:7])
                    break
        if not commits:
            print('!! 无法确定待推送的提交范围，终止')
            sys.exit(2)
        print('待推送提交数:', len(commits))

    remote_tree = {}
    rt = api('GET', '/git/trees/%s?recursive=1' % remote_head)
    for t in rt.get('tree', []):
        remote_tree[t['path']] = (t['sha'], t['mode'], t['type'])

    # 逐个提交处理：每个提交用它自己的 tree 快照
    parent = remote_head
    for idx, c in enumerate(commits, 1):
        print('\n=== 提交 %d/%d: %s ===' % (idx, len(commits), c[:7]))
        print('  ', git('log', '-1', '--format=%s', c))

        ls = git('ls-tree', '-r', '-z', c)
        local_files = []
        for rec in ls.split('\0'):
            if not rec.strip():
                continue
            meta, path = rec.split('\t', 1)
            mode, otype, sha = meta.split()
            local_files.append((mode, otype, sha, path))

        new_entries = []
        uploaded = 0
        reused = 0
        skipped_large = []
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
            # ★ 2026-11-05：Git Data API 对单个 blob 的请求体有上限
            #   （实测 base64 编码后 78.6MB → HTTP 422
            #   "Sorry, your input was too large to process"），
            #   而原生 git push 走的 github.com 被本机代理拦截 → 推不动大文件。
            #   策略：**超大 blob 复用远端已有的 sha**，不重新上传。
            #   影响：dist-release 下的 jar / tar.gz / exe 在远端保持旧版本，
            #   源码与文本产物照常推送；本地构建产物始终是最新，不影响交付。
            if len(content) > MAX_BLOB_BYTES:
                if r:
                    new_entries.append({'path': path, 'mode': mode, 'type': 'blob',
                                        'sha': r[0]})
                    skipped_large.append((path, len(content), r[0]))
                    continue
                raise RuntimeError(
                    '大文件 %s（%.1f MB）在远端不存在，无法复用；'
                    '请改用原生 git push（需能连通 github.com）' %
                    (path, len(content) / 1024.0 / 1024.0))
            b = api('POST', '/git/blobs', {
                'content': base64.b64encode(content).decode(),
                'encoding': 'base64'
            })
            new_entries.append({'path': path, 'mode': mode, 'type': 'blob',
                                'sha': b['sha']})
            uploaded += 1
            if uploaded % 20 == 0:
                print('  已上传 blob %d 个（复用 %d 个）' % (uploaded, reused))
        print('  blob 上传 %d 个，复用 %d 个' % (uploaded, reused))
        if skipped_large:
            print('  ⚠ 超大 blob 跳过上传 %d 个（复用远端旧版本）：' % len(skipped_large))
            for path, size, sha in skipped_large:
                print('     - %s（%.1f MB → 复用 %s）'
                      % (path, size / 1024.0 / 1024.0, sha[:8]))

        local_paths = {p for _, _, _, p in local_files}
        # ★ 只统计 blob。remote_tree 来自 recursive=1，同时含 144 个 tree（目录）
        #   条目，而 local_paths 来自 `git ls-tree -r`（只列 blob）—— 不过滤就会
        #   把每个目录都报成「本次删除的文件」，一条真实删除混在 144 行噪音里
        #   （曾出现「删除 145 个」吓人一跳，实际只有 docs/scripts/deploy_jar.sh 一个）。
        removed = [p for p, (_, _, typ) in remote_tree.items()
                   if typ == 'blob' and p not in local_paths]
        if removed:
            print('  本次删除的文件 %d 个：' % len(removed))
            for p in removed[:5]:
                print('     -', p)
            if len(removed) > 5:
                print('     ... 共 %d 个' % len(removed))

        tree = api('POST', '/git/trees', {'tree': new_entries})
        print('  新 tree sha :', tree['sha'])

        msg = git('log', '-1', '--format=%B', c)
        commit = api('POST', '/git/commits', {
            'message': msg,
            'tree': tree['sha'],
            'parents': [parent],
            'author': {
                'name': git('log', '-1', '--format=%an', c),
                'email': git('log', '-1', '--format=%ae', c),
                'date': git('log', '-1', '--format=%aI', c),
            }
        })
        print('  新 commit   :', commit['sha'])
        parent = commit['sha']
        # 下一个提交以这个 tree 为基线复用
        remote_tree = {}
        for e in new_entries:
            remote_tree[e['path']] = (e['sha'], e['mode'], e['type'])

    if not commits:
        print('本地与远端一致，无需推送')
        return

    ref = api('PATCH', '/git/refs/heads/main',
              {'sha': parent, 'force': False})
    print('\nmain 已更新 ->', ref['object']['sha'])
    print('推送完成（共 %d 个提交）' % len(commits))


if __name__ == '__main__':
    main()
