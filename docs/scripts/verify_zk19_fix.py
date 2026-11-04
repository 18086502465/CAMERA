# -*- coding: utf-8 -*-
"""部署后验证：ZK19 已下线 / 相机绑定数正确 / 临平二期数据完好"""
import sys, io, os, json, collections
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
import paramiko

HOST = os.environ.get('CAMERA_HOST', '10.2.77.72')
USER = os.environ.get('CAMERA_SSH_USER', 'lims')
PWD = os.environ.get('CAMERA_SSH_PASSWORD', '')
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(HOST, username=USER, password=PWD, timeout=20)


def run(cmd):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=180)
    return stdout.read().decode('utf-8', 'replace'), stderr.read().decode('utf-8', 'replace')


admin_pwd = os.environ.get('CAMERA_ADMIN_PASSWORD', '')
login, _ = run("curl -s -X POST 'http://127.0.0.1:8091/api/auth/login' -H 'Content-Type: application/json' "
               "-d '{\"username\":\"admin\",\"password\":\"" + admin_pwd + "\"}'")
token = json.loads(login).get('data', {}).get('token') or json.loads(login).get('token')
print("登录:", "成功" if token else "失败")


def api(path):
    out, _ = run("curl -s 'http://127.0.0.1:8091%s' -H 'Authorization: Bearer %s'" % (path, token))
    try:
        return json.loads(out)
    except Exception:
        return {"_raw": out[:400]}


print("\n" + "=" * 90)
print("1. 级联树：ZK19 应已消失，ZK23/ZK54 保留")
print("=" * 90)
d = api("/api/cascade/all").get('data') or {}
for f in d.get('factories', []):
    print("  工厂 %s (%s)" % (f['factoryName'], f['factoryCode']))
    for l in f.get('lines', []):
        print("    产线 %s" % l['lineName'])
        for s in l.get('stations', []):
            print("      工位 %-14s 绑定相机数=%s" % (s['stationName'], s.get('cameraCount')))

print("\n" + "=" * 90)
print("2. 相机接口：workstationId 分布（应无 21/22）")
print("=" * 90)
d = api("/api/cameras?page=1&size=999").get('data') or {}
recs = d.get('records', [])
cnt = collections.Counter(r.get('workstationId') for r in recs)
print("  total=%s" % d.get('total'))
print("  分布 =", dict(sorted(cnt.items(), key=lambda kv: (kv[0] is None, kv[0]))))
print("  含 workstationId=21/22 的记录:", [r['cameraCode'] for r in recs if r.get('workstationId') in (21, 22)] or "无")

print("\n" + "=" * 90)
print("3. 按工位过滤")
print("=" * 90)
for sid in (11, 23, 24, 21):
    d = api("/api/cameras?page=1&size=10&stationId=%d" % sid).get('data') or {}
    names = [r['cameraName'] + '/' + r['cameraCode'] for r in d.get('records', [])]
    print("  stationId=%-3d total=%-3s -> %s" % (sid, d.get('total'), names or "无"))

print("\n" + "=" * 90)
print("4. 临平二期（ZK54）数据完好性")
print("=" * 90)
d = api("/api/cameras?page=1&size=100&lineId=3").get('data') or {}
print("  ZK54 相机数 =", d.get('total'))
d = api("/api/v1/defect-images/camera-summaries?page=1&size=100").get('data')
if isinstance(d, dict):
    items = d.get('records') or d.get('items') or []
elif isinstance(d, list):
    items = d
else:
    items = []
print("  图片聚合返回相机数 =", len(items))
tot = sum((it.get('totalCount') or it.get('imageCount') or 0) for it in items if isinstance(it, dict))
print("  图片总数 =", tot)

print("\n" + "=" * 90)
print("5. 服务状态")
print("=" * 90)
print(run("systemctl is-active camera-detection; systemctl show camera-detection -p NRestarts --value; curl -s -o /dev/null -w 'HTTP %{http_code}\\n' http://127.0.0.1:8091/")[0])

print("\n" + "=" * 90)
print("6. 前端入口与 chunk")
print("=" * 90)
print(run("curl -s http://127.0.0.1:8091/ | grep -o 'assets/index-[A-Za-z0-9_-]*\\.js'")[0].strip())
print(run("curl -s -o /dev/null -w 'ConfigView chunk HTTP %{http_code}\\n' http://127.0.0.1:8091/assets/ConfigView-BVj6v9z6.js")[0].strip())

ssh.close()
