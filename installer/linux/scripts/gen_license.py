#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""服务器授权（License）离线签发工具。

与后端 `com.wms.shared.license.LicenseSigner` 一一对应，**只依赖标准库**，
可在任何装了 Python 3.6+ 的机器上离线运行，不需要联网、不需要访问数据库。

用法
====
1) 查服务器机器码（在**服务器上**执行，用于绑定本机）：

    python3 gen_license.py fingerprint

   或直接读接口（需登录）：
    curl -s http://127.0.0.1:8091/api/license/machine-code -H "Authorization: Bearer <jwt>"

2) 签发（在**你自己的机器上**执行）：

    python3 gen_license.py issue \\
        --customer "杭州临平一期" \\
        --expire 2027-11-12 \\
        --max-cameras 30 \\
        --machine 3F9A1C77B2D4E580 \\
        -o camera-detection.lic

   不绑定机器（演示/试用）用 --machine '*'
   不限相机数用 --max-cameras -1

3) 校验签出来的文件（不装到服务器就能先验一遍）：

    python3 gen_license.py inspect camera-detection.lic

密钥
====
默认密钥由 SECRET_PART1 / SECRET_PART2 推导，必须与 Java 端完全一致。
若现场用 LICENSE_SECRET 环境变量覆盖了密钥，签发时也要给同一个值：

    python3 gen_license.py issue ... --secret <同一个值>

⚠ 这是对称密钥：拿到 jar 就能反编译出密钥并自行签发。它挡的是
「过期继续用 / 换机器拷过去 / 手改到期日」，不挡逆向。
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
from datetime import date, datetime

# ===== 必须与 LicenseSigner.java 保持一致 =====
SECRET_PART1 = "cds-lic"
SECRET_PART2 = "-2026-zlp"
SECRET_SALT = "wms.camera-detection/license/v1"

MACHINE_SALT = "wms-cds-machine-v1|"
MACHINE_SOURCES = [
    "/sys/class/dmi/id/product_uuid",
    "/etc/machine-id",
    "/var/lib/dbus/machine-id",
    "/sys/class/dmi/id/board_serial",
]
MACHINE_INVALID = {
    "", "not specified", "not applicable", "none", "null", "0",
    "to be filled by o.e.m.", "system serial number", "default string",
    "00000000-0000-0000-0000-000000000000",
}

LICENSE_VERSION = 1


# ---------------------------------------------------------------- 密钥与编解码

def derive_default_secret():
    raw = (SECRET_SALT + "|" + SECRET_PART1 + SECRET_PART2).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def resolve_secret(configured):
    """与 Java 的 LicenseSigner.resolveSecret 同语义：空则用内置默认。"""
    s = (configured or "").strip()
    return s if s else derive_default_secret()


def _b64u(raw):
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _unb64u(text):
    pad = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text + pad)


def encode(payload, secret):
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    payload_b64 = _b64u(body.encode("utf-8"))
    sig = hmac.new(secret.encode("utf-8"), payload_b64.encode("utf-8"),
                   hashlib.sha256).digest()
    return payload_b64 + "." + _b64u(sig)


def decode(text, secret):
    body = text.strip().replace("\r", "").replace("\n", "")
    if "." not in body:
        raise ValueError("授权文件格式错误：缺少签名段")
    payload_b64, sig_b64 = body.rsplit(".", 1)
    expect = _b64u(hmac.new(secret.encode("utf-8"), payload_b64.encode("utf-8"),
                            hashlib.sha256).digest())
    if not hmac.compare_digest(expect, sig_b64):
        raise ValueError("签名校验失败（内容被修改，或密钥与服务器不一致）")
    return json.loads(_unb64u(payload_b64).decode("utf-8"))


# ---------------------------------------------------------------- 机器码

def _read_clean(path):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            v = f.read().strip()
    except (OSError, IOError):
        return None
    if not v or len(v) > 128:
        return None
    if v.lower() in MACHINE_INVALID:
        return None
    return v


def machine_code():
    for p in MACHINE_SOURCES:
        raw = _read_clean(p)
        if raw:
            digest = hashlib.sha256((MACHINE_SALT + raw).encode("utf-8")).hexdigest()
            return digest[:16].upper(), os.path.basename(p)
    return None, None


# ---------------------------------------------------------------- 子命令

def cmd_fingerprint(_args):
    code, source = machine_code()
    if not code:
        print("无法采集机器码：四个来源都不可读（容器/精简系统常见）。", file=sys.stderr)
        print("此时可用 --machine '*' 签发不绑定机器的授权。", file=sys.stderr)
        return 2
    print("机器码 : %s" % code)
    print("来源   : %s" % source)
    return 0


def cmd_issue(args):
    if not args.customer:
        print("错误：--customer 不能为空", file=sys.stderr)
        return 1
    try:
        expire = datetime.strptime(args.expire, "%Y-%m-%d").date()
    except ValueError:
        print("错误：--expire 格式必须是 yyyy-MM-dd", file=sys.stderr)
        return 1
    if expire < date.today():
        print("警告：到期日 %s 早于今天，签出来就是过期授权" % expire, file=sys.stderr)

    if args.max_cameras == 0 or args.max_cameras < -1:
        print("错误：--max-cameras 必须 >= 1，或用 -1 表示不限", file=sys.stderr)
        return 1

    machine = (args.machine or "*").strip().upper()
    if machine != "*" and len(machine) != 16:
        print("警告：机器码通常为 16 位十六进制，当前是 %d 位：%s"
              % (len(machine), machine), file=sys.stderr)

    payload = {
        "version": LICENSE_VERSION,
        "licenseId": args.id or ("LIC-%s-%s" % (date.today().strftime("%Y%m%d"),
                                                datetime.now().strftime("%H%M%S"))),
        "customer": args.customer,
        "issuedAt": args.issued or date.today().strftime("%Y-%m-%d"),
        "expireAt": args.expire,
        "maxCameras": args.max_cameras,
        "features": args.features or [],
        "machineCode": machine,
    }

    secret = resolve_secret(args.secret)
    text = encode(payload, secret)

    if args.out:
        out_dir = os.path.dirname(os.path.abspath(args.out))
        if out_dir and not os.path.isdir(out_dir):
            os.makedirs(out_dir)
        with open(args.out, "w", encoding="utf-8", newline="\n") as f:
            f.write(text + "\n")
        print("已生成: %s" % os.path.abspath(args.out))
        print("客户    : %s" % payload["customer"])
        print("单号    : %s" % payload["licenseId"])
        print("有效期  : %s ~ %s" % (payload["issuedAt"], payload["expireAt"]))
        print("相机上限: %s" % ("不限" if args.max_cameras < 0 else args.max_cameras))
        print("绑定机器: %s" % ("不绑定" if machine == "*" else machine))
    else:
        print(text)
    return 0


def cmd_inspect(args):
    with open(args.file, "r", encoding="utf-8") as f:
        text = f.read()
    secret = resolve_secret(args.secret)
    try:
        payload = decode(text, secret)
    except Exception as e:  # noqa: BLE001 - CLI 边界，直接给可读原因
        print("校验失败：%s" % e, file=sys.stderr)
        return 1
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    try:
        expire = datetime.strptime(payload.get("expireAt", ""), "%Y-%m-%d").date()
        left = (expire - date.today()).days
        print("\n剩余天数: %d 天%s" % (left, "（已过期）" if left < 0 else ""))
    except ValueError:
        print("\n警告：expireAt 格式非法", file=sys.stderr)
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="服务器授权签发工具（离线，仅标准库）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__)
    sub = ap.add_subparsers(dest="cmd")

    p_fp = sub.add_parser("fingerprint", help="打印本机机器码（在服务器上执行）")
    p_fp.set_defaults(func=cmd_fingerprint)

    p_is = sub.add_parser("issue", help="签发授权")
    p_is.add_argument("--customer", required=True, help="客户名称")
    p_is.add_argument("--expire", required=True, help="到期日 yyyy-MM-dd（含当天）")
    p_is.add_argument("--max-cameras", type=int, default=-1,
                      help="相机数上限，-1 表示不限（默认）")
    p_is.add_argument("--machine", default="*",
                      help="绑定的机器码，'*' 表示不绑定（默认）")
    p_is.add_argument("--id", help="授权单号，默认按时间生成")
    p_is.add_argument("--issued", help="签发日 yyyy-MM-dd，默认今天")
    p_is.add_argument("--features", nargs="*", help="功能开关（预留，仅展示）")
    p_is.add_argument("--secret", help="覆盖密钥（须与服务器 LICENSE_SECRET 一致）")
    p_is.add_argument("-o", "--out", help="输出文件；不给则打印到标准输出")
    p_is.set_defaults(func=cmd_issue)

    p_in = sub.add_parser("inspect", help="校验并打印授权内容")
    p_in.add_argument("file", help="授权文件路径")
    p_in.add_argument("--secret", help="覆盖密钥")
    p_in.set_defaults(func=cmd_inspect)

    args = ap.parse_args(argv)
    if not getattr(args, "func", None):
        ap.print_help()
        return 1
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
