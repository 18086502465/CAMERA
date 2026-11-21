#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""授权签发工具（研发侧，绝不进客户交付包 —— LIC-08）。

《服务器授权产品设计-20261121》产品二：CLI 为主、GUI 为辅
（GUI 见同目录 issue_license_gui.py）。四步流程第 ③ 步：
读客户的机器码包 → 填单号/期限/相机数/策略 → 生成授权包 license-*.zip。

命令：
    issue   从机器码包（或 --machine 兜底）签发授权包，并追加台账
    inspect 自检授权包（结构 + MANIFEST + 可选验签）
    ledger  按到期日排序列出台账（30 天内到期标记 ⚠）
    keygen  生成一客户一密钥（32 字节 hex，写文件并置 600）

密钥纪律：
    密钥只走 --secret-file（600），绝不放命令行（会进 shell history）；
    台账只存 sha256 指纹前 8 位，不存密钥本身。

与平台的一致性由 LicenseSignerTest 的 GOLDEN 用例锁死：
    payload = base64url(json)
    text    = payload + "." + base64url(hmac_sha256(payload_bytes, secret))
    secret  = 默认时 sha256(SALT + "|" + PART1 + PART2) hex；客户密钥则直接用文件内容
"""

import argparse
import base64
import csv
import datetime
import glob
import hashlib
import hmac
import json
import os
import sys
import zipfile

MACHINE_SCHEMA = "camera-detection-machine-info/1"
ORDER_SCHEMA = "camera-detection-license-order/1"
TOOL_VERSION = "2.0.0"
LEDGER_COLUMNS = ["orderNo", "customer", "machineCode", "expireAt", "maxCameras",
                  "expiredMode", "issuedAt", "issuedBy", "secretFp", "licSha256", "note"]
LEDGER_NAME = "ledger.csv"

# 与 LicenseSigner.java 的常量逐字一致（仅作默认密钥；一客户一密钥时不用它）
SECRET_SALT = "wms.camera-detection/license/v1"
SECRET_PART1 = "cds-lic"
SECRET_PART2 = "-2026-zlp"

MODE_PLAIN = {
    "warn": "只提示",
    "readonly": "到期停设备上报",
    "block": "到期只留授权页",
}


def default_secret():
    raw = hashlib.sha256((SECRET_SALT + "|" + SECRET_PART1 + SECRET_PART2)
                         .encode("utf-8")).hexdigest()
    return raw


def sha256_text(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def sign_license(payload, secret):
    """与 LicenseSigner.encode 完全同构。"""
    json_bytes = json.dumps(payload, ensure_ascii=False,
                            separators=(",", ":"), sort_keys=False).encode("utf-8")
    payload_b64 = base64.urlsafe_b64encode(json_bytes).rstrip(b"=")
    sig = hmac.new(secret.encode("utf-8"), payload_b64, hashlib.sha256).digest()
    sig_b64 = base64.urlsafe_b64encode(sig).rstrip(b"=")
    return payload_b64.decode("ascii") + "." + sig_b64.decode("ascii")


def verify_license(text, secret):
    """自检用：解析 + 验签，返回 payload dict 或抛 ValueError。"""
    body = text.strip().replace("\r", "").replace("\n", "")
    if "." not in body:
        raise ValueError("缺少签名段")
    payload_b64, sig_b64 = body.split(".", 1)
    expect = base64.urlsafe_b64encode(
        hmac.new(secret.encode("utf-8"), payload_b64.encode("ascii"),
                 hashlib.sha256).digest()).rstrip(b"=").decode("ascii")
    if not hmac.compare_digest(expect, sig_b64):
        raise ValueError("签名不符（密钥不匹配或内容被改）")
    pad = "=" * (-len(payload_b64) % 4)
    return json.loads(base64.urlsafe_b64decode(payload_b64 + pad).decode("utf-8"))


def read_machine_zip(path):
    """读客户的机器码包：校验 schema 与 MANIFEST，返回 machine-info dict。"""
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        if "machine-info.json" not in names:
            raise SystemExit("[错误] 机器码包缺少 machine-info.json（确认拿到的是『下载机器码包』产物）")
        info = json.loads(z.read("machine-info.json").decode("utf-8"))
        if info.get("schema") != MACHINE_SCHEMA:
            raise SystemExit("[错误] 机器码包 schema 不匹配: %s（期望 %s）"
                             % (info.get("schema"), MACHINE_SCHEMA))
        if not info.get("machineCode"):
            raise SystemExit("[错误] 机器码包内 machineCode 为空")
        # MANIFEST 校验（防传输损坏）
        if "MANIFEST.sha256" in names:
            for line in z.read("MANIFEST.sha256").decode("utf-8").splitlines():
                line = line.strip()
                if not line:
                    continue
                digest, name = line.split(None, 1)
                if name in names and name != "MANIFEST.sha256":
                    actual = hashlib.sha256(z.read(name)).hexdigest()
                    if actual != digest:
                        raise SystemExit("[错误] 机器码包损坏：MANIFEST 校验失败（%s），请让客户重新下载"
                                         % name)
    return info


def add_months(day, months):
    month_index = day.year * 12 + day.month - 1 + months
    year, month0 = divmod(month_index, 12)
    month = month0 + 1
    # 日取该月最后一天与原日的较小值（1/31 + 1 月 = 2/28 这类）
    next_month = datetime.date(year + (1 if month == 12 else 0),
                               1 if month == 12 else month + 1, 1)
    last_day = (next_month - datetime.timedelta(days=1)).day
    return datetime.date(year, month, min(day.day, last_day))


def build_license_zip(payload, lic_text, secret_fp, out_path):
    order = {
        "schema": ORDER_SCHEMA,
        "orderNo": payload["licenseId"],
        "customer": payload["customer"],
        "machineCode": payload["machineCode"],
        "issuedAt": payload["issuedAt"],
        "expireAt": payload["expireAt"],
        "maxCameras": payload["maxCameras"],
        "expiredMode": payload.get("expiredMode", "warn"),
        "issuedBy": payload.get("issuedBy", ""),
        "toolVersion": TOOL_VERSION,
        "licSha256": sha256_text(lic_text),
    }
    note = (
        "授权导入说明\n"
        "========================================\n"
        "1. 以管理员身份登录平台 → 系统管理 → 服务器授权；\n"
        "2. 「导入授权包」处选择本 zip → 核对预览摘要（单号/到期日/相机上限/策略）；\n"
        "3. 点「确认导入」，立即生效，无需重启。\n"
        "\n"
        "到期后行为：%s。\n"
        "授权到期不影响功能（warn 策略）；续期请把新的机器码包发给研发。\n"
        "联系：%s\n" % (MODE_PLAIN.get(payload.get("expiredMode", "warn"), "只提示"),
                       payload.get("issuedBy", "供应商"))
    )
    files = [
        ("license.lic", lic_text),
        ("order.json", json.dumps(order, ensure_ascii=False, indent=2)),
        ("授权说明.txt", note),
    ]
    manifest = "".join(sha256_text(c) + "  " + n + "\n" for n, c in files)
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, content in files:
            z.writestr(name, content)
        z.writestr("MANIFEST.sha256", manifest)
    return order


def read_secret_file(path):
    if not os.path.isfile(path):
        raise SystemExit("[错误] 密钥文件不存在: %s" % path)
    with open(path, "r", encoding="utf-8") as f:
        secret = f.read().strip()
    if not secret:
        raise SystemExit("[错误] 密钥文件为空")
    return secret


# ==================== 命令：issue ====================

def cmd_issue(args):
    if args.machine_zip:
        info = read_machine_zip(args.machine_zip)
        machine = info["machineCode"].upper()
        camera_count = info.get("cameraCount")
        hint = info.get("orderHint")
        if not args.customer and info.get("hostname"):
            print("[提示] 机器来自主机 %s，现场相机数 %s"
                  % (info.get("hostname"), camera_count if camera_count is not None else "未知"))
        if camera_count is not None and 0 < args.max_cameras <= camera_count:
            print("[警告] 相机上限 %d ≤ 现场实际 %s 台，客户导入时会要求确认降档；"
                  "如非有意，请加大 --max-cameras" % (args.max_cameras, camera_count))
        if hint and not args.order:
            args.order = hint
            print("[提示] 未指定 --order，采用机器码包内 orderHint: %s" % hint)
    elif args.machine:
        machine = args.machine.strip().upper()
        if len(machine) != 16 or not all(c in "0123456789ABCDEF" for c in machine):
            raise SystemExit("[错误] --machine 须为 16 位大写十六进制机器码")
    else:
        raise SystemExit("[错误] --machine-zip 与 --machine 必须二选一")

    if not args.order or not args.customer:
        raise SystemExit("[错误] --order 与 --customer 必填")
    if args.max_cameras == 0 or args.max_cameras < -1:
        raise SystemExit("[错误] --max-cameras 须为正数或 -1（不限）")
    if args.expired_mode not in MODE_PLAIN:
        raise SystemExit("[错误] --expired-mode 仅支持 warn/readonly/block")

    today = datetime.date.today()
    if args.expire:
        try:
            expire = datetime.datetime.strptime(args.expire, "%Y-%m-%d").date()
        except ValueError:
            raise SystemExit("[错误] --expire 格式应为 yyyy-MM-dd")
    elif args.months and args.months > 0:
        expire = add_months(today, args.months)
    else:
        raise SystemExit("[错误] --months 与 --expire 必须二选一")

    secret = read_secret_file(args.secret_file)
    secret_fp = hashlib.sha256(secret.encode("utf-8")).hexdigest()[:8]

    payload = {
        "version": 2,
        "licenseId": args.order,
        "customer": args.customer,
        "issuedAt": today.isoformat(),
        "expireAt": expire.isoformat(),
        "maxCameras": args.max_cameras,
        "features": [],
        "machineCode": machine,
        "expiredMode": args.expired_mode,
    }
    lic_text = sign_license(payload, secret)

    out = args.out or "license-%s.zip" % args.order
    order = build_license_zip(payload, lic_text, secret_fp, out)

    append_ledger(args, payload, machine, secret_fp, order["licSha256"])

    print("[OK] 授权包已生成: %s" % out)
    print("     单号 %s ｜ 客户 %s ｜ 机器 %s" % (args.order, args.customer, machine))
    print("     到期 %s ｜ 上限 %s ｜ 策略 %s"
          % (payload["expireAt"], "不限" if args.max_cameras == -1 else str(args.max_cameras) + " 台",
             MODE_PLAIN[args.expired_mode]))
    print("[建议] 发给客户前先自检: python issue_license.py inspect %s --secret-file %s"
          % (out, args.secret_file))


def append_ledger(args, payload, machine, secret_fp, lic_sha):
    """台账只存指纹不存密钥；orderNo 重复时仅告警不阻断（续期常见）。"""
    path = LEDGER_NAME
    exists = os.path.isfile(path)
    prev = find_ledger_rows(args.order)
    if prev:
        print("[警告] 台账中已存在单号 %s（%d 条），请确认是新合同而非重复签发" % (args.order, len(prev)))
    with open(path, "a", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        if not exists or os.path.getsize(path) == 0:
            w.writerow(LEDGER_COLUMNS)
        w.writerow([payload["licenseId"], payload["customer"], machine,
                    payload["expireAt"], payload["maxCameras"], payload.get("expiredMode", "warn"),
                    payload["issuedAt"], args.issued_by or "", secret_fp, lic_sha,
                    args.note or ""])


def find_ledger_rows(order_no):
    if not os.path.isfile(LEDGER_NAME):
        return []
    rows = []
    with open(LEDGER_NAME, newline="", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if row.get("orderNo") == order_no:
                rows.append(row)
    return rows


# ==================== 命令：inspect ====================

def cmd_inspect(args):
    path = args.zip_path
    if not os.path.isfile(path):
        raise SystemExit("[错误] 文件不存在: %s" % path)
    problems = []
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        if any("/" in n or ".." in n or "\\" in n for n in names):
            problems.append("包内含非法路径条目")
        lics = [n for n in names if n.lower().endswith(".lic")]
        if len(lics) != 1:
            problems.append("应恰好 1 个 .lic，实际 %d 个" % len(lics))
            raise SystemExit("[FAIL] " + "；".join(problems))
        lic_text = z.read(lics[0]).decode("utf-8")
        order_json = None
        if "order.json" in names:
            try:
                order_json = json.loads(z.read("order.json").decode("utf-8"))
            except Exception as e:
                problems.append("order.json 解析失败: %s" % e)
        if "MANIFEST.sha256" in names:
            for line in z.read("MANIFEST.sha256").decode("utf-8").splitlines():
                line = line.strip()
                if not line:
                    continue
                digest, name = line.split(None, 1)
                if name in names and name != "MANIFEST.sha256":
                    if hashlib.sha256(z.read(name)).hexdigest() != digest:
                        problems.append("MANIFEST 校验失败: %s" % name)

        secret = read_secret_file(args.secret_file) if args.secret_file else None
        try:
            payload = verify_license(lic_text, secret or default_secret())
        except ValueError as e:
            if secret is None:
                print("[警告] 未提供 --secret-file，按内置默认密钥验签: %s" % e)
                payload = None
            else:
                raise SystemExit("[FAIL] 授权验签失败: %s" % e)

    if payload is not None:
        print("[OK] 结构与签名均通过: %s" % path)
        print("     单号 %s ｜ 客户 %s ｜ 机器 %s" % (payload.get("licenseId"),
                                                    payload.get("customer"), payload.get("machineCode")))
        left = (datetime.datetime.strptime(payload["expireAt"], "%Y-%m-%d").date()
                - datetime.date.today()).days
        print("     到期 %s（剩余 %d 天）｜ 上限 %s ｜ 策略 %s"
              % (payload["expireAt"], left,
                 "不限" if payload.get("maxCameras") == -1 else str(payload.get("maxCameras")) + " 台",
                 MODE_PLAIN.get(payload.get("expiredMode", "warn"), "?")))
        if payload.get("version", 1) > 2:
            problems.append("载荷版本 %s 高于平台支持（需先升级平台）" % payload["version"])
        if order_json and order_json.get("orderNo") != payload.get("licenseId"):
            problems.append("order.json 单号与票据不一致（仅提示）")
    else:
        print("[OK] 结构通过（未验签）: %s" % path)

    if problems:
        print("[警告] " + "；".join(problems))
        sys.exit(2)
    print("[DONE] 自检完成")


# ==================== 命令：ledger ====================

def cmd_ledger(args):
    if not os.path.isfile(LEDGER_NAME):
        raise SystemExit("[错误] 台账不存在（%s）；签发时会自动生成" % LEDGER_NAME)
    rows = []
    with open(LEDGER_NAME, newline="", encoding="utf-8-sig") as f:
        rows = [r for r in csv.DictReader(f) if r.get("orderNo")]
    if args.customer:
        rows = [r for r in rows if args.customer in (r.get("customer") or "")]
    rows.sort(key=lambda r: r.get("expireAt") or "")
    today = datetime.date.today()
    print("%-18s %-14s %-17s %-11s %-6s %-9s %s" %
          ("单号", "客户", "机器码", "到期", "上限", "策略", "备注"))
    warn = 0
    for r in rows:
        try:
            left = (datetime.datetime.strptime(r["expireAt"], "%Y-%m-%d").date() - today).days
        except Exception:
            left = None
        mark = " ⚠" if left is not None and left <= 30 else ""
        warn += 1 if mark else 0
        print("%-18s %-14s %-17s %-11s %-6s %-9s %s%s" %
              (r.get("orderNo", ""), (r.get("customer") or "")[:14],
               r.get("machineCode", ""), r.get("expireAt", ""),
               r.get("maxCameras", ""), r.get("expiredMode", ""),
               (r.get("note") or "")[:20], mark))
    print("\n共 %d 条，30 天内到期 %d 条" % (len(rows), warn))


# ==================== 命令：keygen ====================

def cmd_keygen(args):
    secret = os.urandom(32).hex()
    path = args.out or ("secrets/%s.key" % args.customer)
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(secret + "\n")
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass  # Windows 无 chmod 语义，靠目录权限兜底
    fp = hashlib.sha256(secret.encode("utf-8")).hexdigest()[:8]
    print("[OK] 客户密钥已生成: %s" % path)
    print("     指纹 sha256[:8] = %s（记入台账，用于核对）" % fp)
    print("[提醒] 一客户一密钥：此文件只放厂商侧 secrets/ 目录，绝不进客户交付包。")


# ==================== CLI 入口 ====================

def main():
    ap = argparse.ArgumentParser(description="授权签发工具（研发侧专用）")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("issue", help="签发授权包")
    p.add_argument("--machine-zip", help="客户机器码包（首选）")
    p.add_argument("--machine", help="手工机器码（兜底）")
    p.add_argument("--order", help="商务单号 LIC-YYYYMMDD-NNN")
    p.add_argument("--customer", help="客户名称")
    p.add_argument("--months", type=int, help="期限（月，按签发日加）")
    p.add_argument("--expire", help="精确到期日 yyyy-MM-dd（含当天）")
    p.add_argument("--max-cameras", type=int, default=-1, help="相机上限，-1 不限")
    p.add_argument("--expired-mode", default="warn", choices=["warn", "readonly", "block"])
    p.add_argument("--secret-file", required=True, help="客户密钥文件（600）")
    p.add_argument("--issued-by", default="", help="签发人（进台账与 order.json）")
    p.add_argument("--note", default="", help="备注（进台账）")
    p.add_argument("--out", help="输出 zip 路径")
    p.set_defaults(func=cmd_issue)

    p = sub.add_parser("inspect", help="自检授权包")
    p.add_argument("zip_path")
    p.add_argument("--secret-file", help="客户密钥文件（提供则验签）")
    p.set_defaults(func=cmd_inspect)

    p = sub.add_parser("ledger", help="查看台账")
    p.add_argument("--customer", help="按客户过滤")
    p.set_defaults(func=cmd_ledger)

    p = sub.add_parser("keygen", help="生成客户密钥")
    p.add_argument("--customer", required=True, help="客户标识（作文件名 slug）")
    p.add_argument("--out", help="密钥输出路径")
    p.set_defaults(func=cmd_keygen)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
