#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""轻量化更新包工具（构建端与部署端共用的唯一实现，防两端算法漂移）。

子命令：
    lib <jar>                        输出依赖层指纹（sha256）
    classes <jar>                    输出应用层指纹（sha256）
    make-update <fat-jar> <outdir>   从 fat jar 抽取 classes 段生成 update-<时间戳>.zip
    merge <current-jar> <update.zip> <out.jar>
                                     把 update 的 classes 段合成进 current jar（增量部署核心）
                                     lib 指纹不一致时 exit 2（必须走全量）

指纹算法（两端一致的关键）：取该前缀下全部文件条目的 (文件名, 原始大小, CRC)，
按文件名排序后 json 序列化（sort_keys）再 sha256 —— 与压缩方式无关。

安全性说明：
  - merge 不改 MANIFEST / Main-Class（JarLauncher 启动要素原样保留）
  - classes 段【整段替换】：跳过 current 全部 classes 条目再写入 update 全集，
    天然无旧类残留（类移动/删除场景安全，P2 搬包已验证该特性）
  - 合成产物自检：条目无重复、条目数守恒、MANIFEST 含 JarLauncher
"""
import json
import os
import re
import sys
import time
import zipfile

LIB_PREFIX = 'BOOT-INF/lib/'
CLS_PREFIX = 'BOOT-INF/classes/'


def _fingerprint(zf, prefix):
    items = [
        (i.filename, i.file_size, i.CRC)
        for i in zf.infolist()
        if i.filename.startswith(prefix) and not i.filename.endswith('/')
    ]
    items.sort(key=lambda t: t[0])
    blob = json.dumps(items, sort_keys=True).encode('utf-8')
    import hashlib
    return hashlib.sha256(blob).hexdigest()


def _read_manifest(update_zip):
    with zipfile.ZipFile(update_zip) as z:
        return json.loads(z.read('manifest.json').decode('utf-8'))


def cmd_lib(jar):
    with zipfile.ZipFile(jar) as z:
        print(_fingerprint(z, LIB_PREFIX))


def cmd_classes(jar):
    with zipfile.ZipFile(jar) as z:
        print(_fingerprint(z, CLS_PREFIX))


def cmd_make_update(fat_jar, outdir):
    stamp = time.strftime('%Y%m%d-%H%M%S')
    out = os.path.join(outdir, 'update-%s.zip' % stamp)
    os.makedirs(outdir, exist_ok=True)
    with zipfile.ZipFile(fat_jar) as z:
        lib_fp = _fingerprint(z, LIB_PREFIX)
        cls_fp = _fingerprint(z, CLS_PREFIX)
        n = 0
        with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zo:
            for i in z.infolist():
                # ★ 目录条目（BOOT-INF/classes/、com/、static/ 等）必须一并打包 ——
                #   JarLauncher 靠 BOOT-INF/classes/ 目录条目识别类路径根，
                #   丢了它（只搬文件条目）会导致整个 classes 不进 classpath：
                #   症状是 lib 正常、主类 ClassNotFoundException（实机踩过）
                if i.filename.startswith(CLS_PREFIX):
                    info = zipfile.ZipInfo(i.filename, date_time=time.localtime()[:6])
                    info.compress_type = zipfile.ZIP_DEFLATED
                    info.external_attr = i.external_attr      # 保留目录/权限位
                    zo.writestr(info, z.read(i.filename))
                    n += 1
            zo.writestr('manifest.json', json.dumps({
                'version': stamp,
                'builtAt': time.strftime('%Y-%m-%d %H:%M:%S'),
                'sourceJar': os.path.basename(fat_jar),
                'sourceJarSha256': _file_sha256(fat_jar),
                'libSha256': lib_fp,
                'classesSha256': cls_fp,
                'classesCount': n,
            }, ensure_ascii=False, indent=2).encode('utf-8'))
    print('update 包: %s（classes 条目 %d（含目录）, %.1f MB）' % (out, n, os.path.getsize(out) / 1048576))
    print('libSha256: %s' % lib_fp)


def cmd_merge(current_jar, update_zip, out_jar):
    man = _read_manifest(update_zip)
    with zipfile.ZipFile(current_jar) as cur:
        cur_lib_fp = _fingerprint(cur, LIB_PREFIX)
        if cur_lib_fp != man.get('libSha256'):
            sys.stderr.write('依赖层指纹不一致：update 要求 %s，服务器当前 %s\n'
                             '→ 依赖已变更，本次必须走【全量 fat jar】部署\n' % (man.get('libSha256'), cur_lib_fp))
            sys.exit(2)

        tmp = out_jar + '.tmp'
        replaced = 0
        with zipfile.ZipFile(update_zip) as upd, \
             zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as out:
            # 1) 原样保留：除 classes 段外的所有条目（含 MANIFEST、lib、classpath.idx）
            for item in cur.infolist():
                if item.filename.startswith(CLS_PREFIX):
                    continue
                out.writestr(item, cur.read(item.filename))
            # 2) 写入 update 的 classes 全集（整段替换；★含目录条目，见 make-update 注释）
            for item in upd.infolist():
                if item.filename.startswith(CLS_PREFIX):
                    out.writestr(item, upd.read(item.filename))
                    replaced += 1

        # 3) 合成产物自检
        with zipfile.ZipFile(tmp) as z:
            names = z.namelist()
            dup = len(names) - len(set(names))
            if dup:
                os.remove(tmp)
                sys.exit('合成产物有 %d 个重复条目，已丢弃' % dup)
            mf = z.read('META-INF/MANIFEST.MF').decode('utf-8', 'replace')
            if 'JarLauncher' not in mf:
                os.remove(tmp)
                sys.exit('合成产物丢失 Main-Class: JarLauncher，已丢弃')
            if _fingerprint(z, LIB_PREFIX) != cur_lib_fp:
                os.remove(tmp)
                sys.exit('合成后 lib 指纹变化，已丢弃')
            expect = man.get('classesCount')
            if expect is not None and replaced != expect:
                os.remove(tmp)
                sys.exit('classes 条目数不符（期望 %s，实际 %d），已丢弃' % (expect, replaced))

        os.replace(tmp, out_jar)
        print('合成完成: %s（classes 替换 %d 条, %.1f MB，lib 原样保留）'
              % (out_jar, replaced, os.path.getsize(out_jar) / 1048576))


def _file_sha256(path):
    import hashlib
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(1)
    cmd, rest = args[0], args[1:]
    try:
        {'lib': cmd_lib, 'classes': cmd_classes,
         'make-update': cmd_make_update, 'merge': cmd_merge}[cmd](*rest)
    except zipfile.BadZipFile as e:
        sys.exit('❌ 包文件损坏或不是合法的 zip/jar：%s' % e)


if __name__ == '__main__':
    main()
