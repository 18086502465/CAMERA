# -*- coding: utf-8 -*-
"""本地验证 recover/prune 脚本：产生重复条目即失败"""
import os, shutil, subprocess, sys, zipfile

BASE = os.path.join(os.environ.get('TEMP', '.'), 'jartest3')
shutil.rmtree(BASE, ignore_errors=True)
os.makedirs(BASE)
JAR = os.path.join(BASE, 'current.jar')
shutil.copy2(r'D:\031_45344camera\fact-product\target\time-bucket-demo.jar', JAR)

APP_DIR = BASE
PREFIX = "BOOT-INF/classes/static/assets/"


def make_backup(name, extra_chunk):
    """造一个"历史版本"的 JAR：把当前 assets 改名成旧 chunk 名"""
    out = os.path.join(BASE, name)
    with zipfile.ZipFile(JAR) as zin, zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            nm = item.filename
            if nm.startswith(PREFIX) and nm.endswith('.js') and 'index-' in nm:
                zout.writestr(PREFIX + extra_chunk, zin.read(nm))
            else:
                zout.writestr(item, zin.read(nm))
    return out


bak1 = make_backup('app.jar.bak-20261104_010000', 'ImagesView-OLD1.js')
bak2 = make_backup('app.jar.bak-20261104_020000', 'ImagesView-OLD2.js')
print('造出备份:', os.path.basename(bak1), os.path.basename(bak2))

# 改脚本里的路径指向本地
for script in ['recover_legacy_chunks.py', 'prune_legacy_chunks.py']:
    src = open('D:/031_45344camera/docs/scripts/' + script, encoding='utf-8').read()
    src = src.replace('APP_DIR = "/opt/camera-detection-system/app"',
                      'APP_DIR = %r' % BASE)
    src = src.replace('CURRENT_JAR = os.path.join(APP_DIR, "time-bucket-demo.jar")',
                      'CURRENT_JAR = %r' % JAR)
    src = src.replace('CURRENT_JAR = "/opt/camera-detection-system/app/time-bucket-demo.jar"',
                      'CURRENT_JAR = %r' % JAR)
    open(os.path.join(BASE, script), 'w', encoding='utf-8').write(src)

print('\n=== 执行 recover ===')
r = subprocess.run([sys.executable, os.path.join(BASE, 'recover_legacy_chunks.py')],
                   capture_output=True, text=True)
print(r.stdout[-1500:])
if r.returncode != 0:
    print('STDERR:', r.stderr[-800:])

with zipfile.ZipFile(JAR) as z:
    n = z.namelist()
    dup = len(n) - len(set(n))
    has_old = any('ImagesView-OLD1.js' in x for x in n)
    has_old2 = any('ImagesView-OLD2.js' in x for x in n)
    mf = z.read('META-INF/MANIFEST.MF').decode('utf-8', 'replace')
print('\n=== recover 后校验 ===')
print('  重复条目:', dup, '(必须为 0)')
print('  MANIFEST 完好:', 'JarLauncher' in mf and 'Start-Class' in mf)
print('  旧 chunk1 已找回:', has_old)
print('  旧 chunk2 已找回:', has_old2)
assert dup == 0, '有重复条目！'
assert 'JarLauncher' in mf
assert has_old and has_old2

print('\n=== 执行 prune（保留 1 版）===')
r = subprocess.run([sys.executable, os.path.join(BASE, 'prune_legacy_chunks.py'), '1'],
                   capture_output=True, text=True)
print(r.stdout[-1500:])
if r.returncode != 0:
    print('STDERR:', r.stderr[-800:])

with zipfile.ZipFile(JAR) as z:
    n = z.namelist()
    dup = len(n) - len(set(n))
    has_old = any('ImagesView-OLD1.js' in x for x in n)
    has_old2 = any('ImagesView-OLD2.js' in x for x in n)
    mf = z.read('META-INF/MANIFEST.MF').decode('utf-8', 'replace')
print('\n=== prune 后校验 ===')
print('  重复条目:', dup, '(必须为 0)')
print('  MANIFEST 完好:', 'JarLauncher' in mf and 'Start-Class' in mf)
print('  较旧版本已淘汰:', not has_old, '(OLD1 应被淘汰)')
print('  较新版本保留:', has_old2, '(OLD2 应保留)')
assert dup == 0, '有重复条目！'
assert 'JarLauncher' in mf
assert not has_old and has_old2
print('\n✅ 全部校验通过')
