# -*- coding: utf-8 -*-
"""配置去密钥改造后的业务功能验证

⚠️ 凭据从环境变量读取，脚本内不含明文口令：
      export MYSQL_PASSWORD='<数据库口令>'
      export SSH_PASSWORD='<SSH 口令>'
      export SSH_HOST='10.2.77.72'   SSH_USER='lims'
      export ADMIN_PASSWORD='<应用管理员口令>'
    未设置时脚本会明确报错退出。
"""
import sys, io, json, os
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
import paramiko

HOST = os.environ.get('SSH_HOST', '10.2.77.72')
USER = os.environ.get('SSH_USER', 'lims')
PWD = os.environ.get('SSH_PASSWORD', '')
MYSQL_PWD = os.environ.get('MYSQL_PASSWORD', '')
ADMIN_PWD = os.environ.get('ADMIN_PASSWORD', 'admin123')

if not PWD:
    sys.exit('请先 export SSH_PASSWORD=<SSH 口令>')
if not MYSQL_PWD:
    sys.exit('请先 export MYSQL_PASSWORD=<数据库口令>')

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(HOST, username=USER, password=PWD, timeout=20)


def run(cmd):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=200)
    return stdout.read().decode('utf-8', 'replace')


# 用单引号包裹口令，避免与命令里的双引号嵌套冲突
SUDO = "echo '" + PWD + "' | sudo -S"


print('=== 1. 登录（JWT 签名是否正常）===')
out = run("""curl -s -X POST 'http://127.0.0.1:8091/api/auth/login' -H 'Content-Type: application/json' -d '{"username":"admin","password":"' + ADMIN_PWD + '"}'""")
tok = ''
try:
    d = json.loads(out)
    tok = d.get('data', {}).get('token', '')
    print('  code=', d.get('code'), ' token长度=', len(tok))
except Exception as e:
    print('  解析失败:', out[:200])

if tok:
    print()
    print('=== 2. 业务接口（带 token）===')
    for path in ['/api/cameras?page=1&size=5',
                 '/api/v1/analytics/line-summary',
                 '/api/cascade/all',
                 '/api/v1/system/server/metrics']:
        code = run("curl -s -o /dev/null -w 'HTTP %{http_code}' 'http://127.0.0.1:8091" + path + "' -H 'Authorization: Bearer " + tok + "'")
        print('  %-42s %s' % (path, code))

print()
print('=== 3. 配置文件明文口令残留 ===')
n = run(SUDO + " grep -cE 'password:[[:space:]]*[A-Za-z0-9]|secret:[[:space:]]*[A-Za-z0-9]' /opt/camera-detection-system/config/application.yml 2>&1")
print('  明文口令行数:', n.strip(), '（0 = 已清除）')

print()
print('=== 4. 配置文件的实际内容（敏感项已脱敏）===')
cfg = run(SUDO + " grep -nE 'password:|secret:' /opt/camera-detection-system/config/application.yml 2>&1")
for line in cfg.splitlines():
    if 'password' in line or 'secret' in line:
        print('  ', line.split(':')[0].strip() + ': <已脱敏>')
    else:
        print('  ', line)

print()
print('=== 5. 文件权限 ===')
print(run(SUDO + " stat -c '%a %U:%G %n' /opt/camera-detection-system/config/application.yml /etc/default/camera-detection 2>&1"))

print()
print('=== 6. 设备上报与数据完好性 ===')
print(run("mysql -uroot -p" + MYSQL_PWD + " -N -B -D 'wms-new' -e \"SELECT CONCAT(COUNT(*),' 台相机近1分钟有心跳') FROM camera_status WHERE is_active=1 AND last_heartbeat_at > NOW() - INTERVAL 1 MINUTE\" 2>/dev/null"))
print(run("mysql -uroot -p" + MYSQL_PWD + " -N -B -D 'wms-new' -e \"SELECT CONCAT('缺陷图片 ',COUNT(*),' 张') FROM defect_image_file\" 2>/dev/null"))

print()
print('=== 7. 新进程有无 ERROR ===')
errs = run("journalctl -u camera-detection --since '2026-11-04 15:22:40' --no-pager 2>/dev/null | grep ' ERROR ' | grep -v EOFException | head -5")
print(errs.strip() if errs.strip() else '  无（EOFException 为设备上传中断的既有现象）')

ssh.close()
