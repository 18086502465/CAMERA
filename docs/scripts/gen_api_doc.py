# -*- coding: utf-8 -*-
"""P6 接口文档生成器 v2：逐行扫描 Controller，提取全部端点（宽容版）。"""
import io, os, re, sys, datetime
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

# 仓库根由脚本位置推导（docs/scripts/ 上两级），避免硬编码绝对路径导致换机器即失效
REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BASE = os.path.join(REPO, 'fact-product', 'src', 'main', 'java', 'com', 'wms')
DOMAINS = ['iam', 'masterdata', 'device', 'defect', 'inspection', 'analytics', 'system', 'shared']
DOMAIN_TITLE = {
    'iam': 'iam —— 认证与用户', 'masterdata': 'masterdata —— 主数据',
    'device': 'device —— 相机设备', 'defect': 'defect —— 缺陷图片（现场主通道）',
    'inspection': 'inspection —— 批量上报（冻结契约）',
    'analytics': 'analytics —— 统计分析', 'system': 'system —— 系统管理',
    'shared': 'shared —— 共享（SPA 兜底）',
}
MAP_RE = re.compile(r'@(Get|Post|Put|Delete|Request)Mapping')
ROLE_RE = re.compile(r'@PreAuthorize\("[^"]*(ADMIN|USER)[^"]*"\)')
PUB_RE = re.compile(r'public\s+[\w<>,\s\[\].]+?\s+(\w+)\s*\(')
STR_RE = re.compile(r'"([^"]*)"')

rows_by_domain = {}
total = 0
for dom in DOMAINS:
    dom_dir = os.path.join(BASE, dom)
    if not os.path.isdir(dom_dir):
        continue
    for root, dirs, files in os.walk(dom_dir):
        for fn in sorted(files):
            if not fn.endswith('Controller.java'):
                continue
            path = os.path.join(root, fn)
            lines_in = open(path, encoding='utf-8').read().splitlines()
            cls = fn[:-5]
            base = ''
            # 类级 base：类声明前最近的 @RequestMapping
            cls_line = len(lines_in)
            for i, ln in enumerate(lines_in):
                if re.search(r'\b(class|interface)\s+' + cls + r'\b', ln):
                    cls_line = i
                    break
                m = re.search(r'@RequestMapping\("([^"]+)"\)', ln)
                if m:
                    base = m.group(1)
            eps = []
            for i in range(cls_line + 1, len(lines_in)):
                ln = lines_in[i]
                m = MAP_RE.search(ln)
                if not m:
                    continue
                verb = m.group(1).upper()
                if verb == 'REQUEST':
                    verb = 'ANY'
                sub = ''
                mm = STR_RE.search(ln)
                if mm:
                    sub = mm.group(1)
                else:
                    # 多行 value = { "/a", "/b" }
                    j = i
                    buf = ln
                    while '{' in buf and '}' not in buf and j < len(lines_in) - 1:
                        j += 1
                        buf += lines_in[j]
                    paths = STR_RE.findall(buf)
                    if paths:
                        eps.append((verb, base + paths[0], '?', '?'))
                        for extra in paths[1:]:
                            eps.append((verb, base + extra, '?', '?'))
                        continue
                # 向上 3 行找 @PreAuthorize
                role = '登录用户'
                for k in range(max(0, i - 3), i):
                    if 'ADMIN' in lines_in[k]:
                        role = 'ADMIN'
                        break
                # 向下 10 行找第一个 public 方法名
                mname = '?'
                for k in range(i, min(len(lines_in), i + 12)):
                    pm = PUB_RE.search(lines_in[k])
                    if pm:
                        mname = pm.group(1)
                        break
                eps.append((verb, base + sub, mname, role))
            if eps:
                rows_by_domain.setdefault(dom, []).append((cls, eps))
                total += len(eps)

lines = [
    '# 产线缺陷在线 AI 检测系统 —— 接口文档',
    '',
    '> 版本：1.0（DDD 重构定稿）｜**生成日期 %s** ｜由 `docs/scripts/gen_api_doc.py` 从 Controller 源码自动提取（再生成命令：`python docs/scripts/gen_api_doc.py`）' % datetime.date.today().isoformat(),
    '>',
    '> 通用约定：',
    '> - baseURL `/api`（前端 `request.js`）；文档内路径已含 `/api` 前缀',
    '> - 响应统一 `ResultVO`：`{"code":200,"message":"...","data":...}`；业务失败 code=500、校验失败 code=422',
    '> - 认证：除登录/注册外均需 `Authorization: Bearer <token>`',
    '> - 权限语义：未认证 → **401** `AUTHENTICATION_REQUIRED`；已登录角色不足 → **403** `ACCESS_DENIED`',
    '> - 设备上报端点（heartbeat/upload/batch-upload）token 为设备令牌或白名单放行，见《架构说明》§3.4',
    '',
    '**端点总数：%d**' % total,
    '',
]
for dom in DOMAINS:
    if dom not in rows_by_domain:
        continue
    lines.append('## ' + DOMAIN_TITLE[dom])
    lines.append('')
    for cls, eps in rows_by_domain[dom]:
        lines.append('### %s' % cls)
        lines.append('')
        lines.append('| 方法 | 路径 | 处理方法 | 权限 |')
        lines.append('|---|---|---|---|')
        for verb, full, mname, role in eps:
            lines.append('| %s | `%s` | %s | %s |' % (verb, full, mname, role))
        lines.append('')

out_path = os.path.join(REPO, 'docs', '接口文档.md')
open(out_path, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))
print('生成完成: %s（%d 端点）' % (out_path, total))
