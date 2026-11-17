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
                # 权限判定：在映射注解**前后各 8 行**内找 @PreAuthorize 并解析角色。
                # ★ 2026-11-18 修正两个缺陷：
                #   ① 原来只**向上 3 行**找 → 注解写在 @XxxMapping **之后**（项目里很常见，
                #      如 @PostMapping 下一行才写 @PreAuthorize）或注解隔了 4 行以上就找不到，
                #      实测 8 个 ADMIN 端点被错标成「登录用户」（含 DailyReport 的 regenerate、
                #      4 个 Dim*Controller 的 /{id}、config-import 等）—— 读者会误以为普通用户能调。
                #   ② 原来只找裸字符串 'ADMIN' → 注释里写「管理员」也算命中，
                #      且 hasAnyRole('ADMIN','USER') 会被标成 ADMIN（实际两种角色都行）。
                #   现在：只认 @PreAuthorize 注解本身，按表达式解析角色。
                role = '登录用户'
                for k in range(max(0, i - 8), min(len(lines_in), i + 9)):
                    if '@PreAuthorize' not in lines_in[k]:
                        continue
                    expr = lines_in[k]
                    has_admin = re.search(r"hasRole\(\s*['\"]ADMIN['\"]\s*\)", expr) is not None \
                        or re.search(r"hasAnyRole\([^)]*['\"]ADMIN['\"]", expr) is not None
                    has_user = re.search(r"hasRole\(\s*['\"]USER['\"]\s*\)", expr) is not None \
                        or re.search(r"hasAnyRole\([^)]*['\"]USER['\"]", expr) is not None
                    if has_admin and has_user:
                        role = 'ADMIN/USER'
                    elif has_admin:
                        role = 'ADMIN'
                    elif has_user:
                        role = 'USER'
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
    '> - **设备侧协议（字段/校验/错误码/重试）见 [`相机设备接口协议.md`](相机设备接口协议.md)**，本文只列端点与权限',
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
# ★ 2026-11-18：拼接手工补充区。
#   本脚本是**全量覆盖**写文件 —— 之前手工写在生成结果里的说明（line-summary 的
#   诚实度键、daily-reports 的口径约定）在每次重新生成后都会被抹掉。
#   现在把手工部分单独放 `docs/scripts/api_doc_extra.md`，由本脚本在末尾拼接：
#   自动部分永远从源码重新生成（不会漂移），手工部分永远保留（不会丢）。
extra_path = os.path.join(REPO, 'docs', 'scripts', 'api_doc_extra.md')
extra = ''
if os.path.isfile(extra_path):
    with open(extra_path, encoding='utf-8') as f:
        extra = f.read().strip()
    lines.append('---')
    lines.append('')
    lines.append('## 补充说明（手工维护：`docs/scripts/api_doc_extra.md`）')
    lines.append('')
    lines.append(extra)
    lines.append('')
open(out_path, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))
print('生成完成: %s（%d 端点%s）' % (out_path, total, '，已拼接手工补充区' if extra else ''))

# 回写端点数到 docs/README.md —— 手写的数字迟早漂移（曾出现 135 vs 实际 139）。
# 约定：README 里用 <!--API_COUNT-->N<!--/API_COUNT--> 包裹，本脚本只改中间的数字。
readme_path = os.path.join(REPO, 'docs', 'README.md')
try:
    with open(readme_path, encoding='utf-8') as f:
        readme = f.read()
    new_readme, n = re.subn(r'(<!--API_COUNT-->)\d+(<!--/API_COUNT-->)',
                            r'\g<1>%d\g<2>' % total, readme)
    if n:
        with open(readme_path, 'w', encoding='utf-8', newline='\n') as f:
            f.write(new_readme)
        print('已回写 docs/README.md 端点数：%d' % total)
    else:
        print('⚠ docs/README.md 未找到 API_COUNT 标记，端点数未回写')
except OSError as exc:
    print('⚠ 回写 docs/README.md 失败：%s' % exc)
