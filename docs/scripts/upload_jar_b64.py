#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""上传大文件到服务器（base64 分块 + 纯命令通道）。

背景：本机到服务器的 SSH 通道在传输 60MB 级二进制时会被服务端断开
（paramiko 报 "Socket is closed"，SFTP 子系统也不可用），
因此改为「base64 编码 → 分块 → 逐块通过 exec_command 追加写入」，
只依赖普通 SSH 命令通道，不依赖大流量数据通道。

用法：
    export SSH_PASSWORD='<SSH 口令>'
    python3 upload_jar_b64.py <本地文件> <远程路径>
"""
import base64
import os
import sys

import paramiko

HOST = os.environ.get('SSH_HOST', '10.2.77.72')
USER = os.environ.get('SSH_USER', 'lims')
PWD = os.environ.get('SSH_PASSWORD', '')

# 每块 base64 字符数（传输的是 ASCII，命令长度可控）
CHUNK = 48 * 1024


def run(ssh, cmd, timeout=120):
    _, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    rc = stdout.channel.recv_exit_status()
    out = stdout.read().decode('utf-8', 'replace')
    err = stderr.read().decode('utf-8', 'replace')
    if rc != 0:
        raise RuntimeError('命令失败 rc=%d: %s\n%s' % (rc, cmd[:120], err))
    return out


def main():
    if not PWD:
        sys.exit('请先 export SSH_PASSWORD=<SSH 口令>')
    if len(sys.argv) < 3:
        sys.exit('用法: python3 upload_jar_b64.py <本地文件> <远程路径>')

    local_path = os.path.abspath(sys.argv[1])
    remote_path = sys.argv[2]
    if not os.path.isfile(local_path):
        sys.exit('本地文件不存在: ' + local_path)
    # ⚠️ Git Bash 会把 /home/... 转换成 Windows 路径（MSYS 路径转换），
    #    必须转成 POSIX 形式再传给远端 shell。
    if remote_path.startswith('/') and not remote_path.startswith('//'):
        remote_path = '/' + remote_path
    remote_path = remote_path.replace('\\', '/')

    size = os.path.getsize(local_path)
    print('本地文件: %s (%d 字节)' % (local_path, size))

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username=USER, password=PWD, timeout=30)
    try:
        # 1) 清空目标文件
        run(ssh, 'rm -f %s && : > %s' % (remote_path, remote_path))

        # 2) base64 编码后分块追加
        with open(local_path, 'rb') as f:
            raw = f.read()
        b64 = base64.b64encode(raw).decode('ascii')
        total = len(b64)
        n = 0
        for i in range(0, total, CHUNK):
            piece = b64[i:i + CHUNK]
            # printf '%s' 保证不追加换行，避免 base64 被换行污染
            run(ssh, "printf '%%s' '%s' >> %s.b64" % (piece, remote_path))
            n += 1
            if n % 50 == 0:
                print('  已传 %d/%d 块 (%.0f%%)' % (n, (total + CHUNK - 1) // CHUNK,
                                                 i * 100.0 / total))

        # 3) 解码回二进制
        run(ssh, 'base64 -d %s.b64 > %s && rm -f %s.b64 && chmod 644 %s' % (
            remote_path, remote_path, remote_path, remote_path))

        # 4) 校验大小
        out = run(ssh, 'stat -c %%s %s' % remote_path).strip()
        remote_size = int(out)
        print('远程文件: %s (%d 字节)' % (remote_path, remote_size))
        if remote_size != size:
            sys.exit('大小不一致！本地 %d / 远程 %d' % (size, remote_size))
        print('✓ 上传成功且大小一致')
    finally:
        ssh.close()


if __name__ == '__main__':
    main()
