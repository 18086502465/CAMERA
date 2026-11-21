#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""授权签发工具 GUI（研发/商务侧，绝不进客户交付包）。

《服务器授权产品设计-20261121》§4.3 的单窗口实现，复用 issue_license.py
的全部逻辑（打包 exe 时用 PyInstaller 把两个文件一起打进去）。

用法：python issue_license_gui.py
"""

import datetime
import json
import os
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import issue_license as core  # noqa: E402

MODE_ITEMS = [("只提示（默认）", "warn"), ("到期停设备上报", "readonly"), ("到期只留授权页", "block")]
MONTH_ITEMS = [("6 个月", 6), ("12 个月", 12), ("24 个月", 24), ("36 个月", 36), ("自定义到期日", 0)]


class IssueApp:
    def __init__(self, root):
        self.root = root
        root.title("授权签发工具 %s（厂商专用，勿外发）" % core.TOOL_VERSION)
        root.geometry("560x520")
        root.resizable(False, False)

        self.machine_info = None
        self.machine_zip_path = ""
        self.secret_path = ""

        pad = dict(padx=10, pady=6)
        frm = ttk.Frame(root)
        frm.pack(fill="both", expand=True, padx=12, pady=10)

        # ---- 机器码包 ----
        row = ttk.Frame(frm); row.grid(row=0, column=0, sticky="ew", **pad)
        ttk.Button(row, text="选择机器码包…", command=self.pick_machine_zip).pack(side="left")
        ttk.Label(row, textvariable=tk.StringVar(value=""), text="").pack(side="left")
        self.machine_label = ttk.Label(frm, text="未选择（把客户发来的 machine-*.zip 拖进来或点上面按钮）",
                                       foreground="#888")
        self.machine_label.grid(row=1, column=0, sticky="w", **pad)

        grid = ttk.Frame(frm); grid.grid(row=2, column=0, sticky="ew", **pad)
        grid.columnconfigure(1, weight=1)

        ttk.Label(grid, text="商务单号").grid(row=0, column=0, sticky="w")
        self.order = tk.StringVar(); ttk.Entry(grid, textvariable=self.order).grid(row=0, column=1, sticky="ew")

        ttk.Label(grid, text="客户名称").grid(row=1, column=0, sticky="w")
        self.customer = tk.StringVar(); ttk.Entry(grid, textvariable=self.customer).grid(row=1, column=1, sticky="ew")

        ttk.Label(grid, text="授权期限").grid(row=2, column=0, sticky="w")
        self.month_choice = ttk.Combobox(grid, values=[m[0] for m in MONTH_ITEMS],
                                         state="readonly", width=16)
        self.month_choice.current(1)
        self.month_choice.grid(row=2, column=1, sticky="w")
        self.month_choice.bind("<<ComboboxSelected>>", lambda _e: self.update_expire())
        self.expire_label = ttk.Label(grid, text="")
        self.expire_label.grid(row=2, column=2, sticky="w", padx=(8, 0))

        ttk.Label(grid, text="相机上限").grid(row=3, column=0, sticky="w")
        self.max_cameras = tk.StringVar(value="30")
        ttk.Entry(grid, textvariable=self.max_cameras, width=10).grid(row=3, column=1, sticky="w")
        self.camera_hint = ttk.Label(grid, text="", foreground="#888")
        self.camera_hint.grid(row=3, column=2, sticky="w", padx=(8, 0))

        ttk.Label(grid, text="到期策略").grid(row=4, column=0, sticky="w")
        self.mode_choice = ttk.Combobox(grid, values=[m[0] for m in MODE_ITEMS],
                                        state="readonly", width=20)
        self.mode_choice.current(0)
        self.mode_choice.grid(row=4, column=1, sticky="w")

        ttk.Label(grid, text="客户密钥").grid(row=5, column=0, sticky="w")
        row2 = ttk.Frame(grid); row2.grid(row=5, column=1, columnspan=2, sticky="ew")
        ttk.Button(row2, text="选择密钥文件…", command=self.pick_secret).pack(side="left")
        self.secret_label = ttk.Label(row2, text="未选择（secrets/<客户>.key，600）", foreground="#888")
        self.secret_label.pack(side="left", padx=8)

        ttk.Label(grid, text="签发人").grid(row=6, column=0, sticky="w")
        self.issued_by = tk.StringVar(value="研发")
        ttk.Entry(grid, textvariable=self.issued_by).grid(row=6, column=1, sticky="w")

        btns = ttk.Frame(frm); btns.grid(row=3, column=0, pady=(12, 0), **pad)
        ttk.Button(btns, text="生成授权包", command=self.issue).pack(side="left", padx=6)
        ttk.Button(btns, text="自检", command=self.inspect).pack(side="left", padx=6)

        self.result = tk.StringVar(value="")
        ttk.Label(frm, textvariable=self.result, foreground="#0a7d32",
                  wraplength=520, justify="left").grid(row=4, column=0, sticky="w", **pad)

        self.update_expire()
        # 记住上次密钥目录
        last_dir = os.path.expanduser("~/.license-tool")
        if os.path.isfile(os.path.join(last_dir, "last_secret_dir")):
            self.last_secret_dir = open(os.path.join(last_dir, "last_secret_dir")).read().strip()
        else:
            self.last_secret_dir = os.path.expanduser("~")

    # ---- 交互 ----

    def pick_machine_zip(self):
        path = filedialog.askopenfilename(filetypes=[("机器码包", "*.zip")])
        if not path:
            return
        try:
            self.machine_info = core.read_machine_zip(path)
        except SystemExit as e:
            messagebox.showerror("机器码包不合法", str(e))
            return
        self.machine_zip_path = path
        info = self.machine_info
        self.machine_label.config(
            text="机器码 %s ｜ 现场相机 %s 台 ｜ 主机 %s" % (
                info["machineCode"],
                info.get("cameraCount") if info.get("cameraCount") is not None else "未知",
                info.get("hostname") or "-"),
            foreground="#0a58c2")
        if not self.order.get() and info.get("orderHint"):
            self.order.set(info["orderHint"])
        # 默认上限 = 现场台数 × 1.2 向上取整（避免顶格）
        count = info.get("cameraCount")
        if count and not self.max_cameras.get().strip():
            import math
            self.max_cameras.set(str(int(math.ceil(count * 1.2))))

    def pick_secret(self):
        path = filedialog.askopenfilename(initialdir=self.last_secret_dir,
                                          filetypes=[("密钥文件", "*.key"), ("全部文件", "*.*")])
        if not path:
            return
        self.secret_path = path
        self.secret_label.config(text=os.path.basename(path), foreground="#0a58c2")
        # 记住目录（只记路径不记内容）
        d = os.path.dirname(path)
        try:
            os.makedirs(os.path.expanduser("~/.license-tool"), exist_ok=True)
            with open(os.path.expanduser("~/.license-tool/last_secret_dir"), "w") as f:
                f.write(d)
        except OSError:
            pass

    def current_expire(self):
        months = MONTH_ITEMS[self.month_choice.current()][1]
        if months:
            return core.add_months(datetime.date.today(), months).isoformat()
        # 自定义：弹输入框
        return None

    def update_expire(self):
        months = MONTH_ITEMS[self.month_choice.current()][1]
        if months:
            self.expire_label.config(
                text="到期 %s" % core.add_months(datetime.date.today(), months).isoformat())
        else:
            self.expire_label.config(text="生成时输入到期日")

    def validate(self):
        if not self.machine_info:
            messagebox.showwarning("缺少机器码包", "请先选择客户的 machine-*.zip")
            return None
        if not self.order.get().strip():
            messagebox.showwarning("缺少单号", "请填写商务单号")
            return None
        if not self.customer.get().strip():
            messagebox.showwarning("缺少客户", "请填写客户名称")
            return None
        if not self.secret_path:
            messagebox.showwarning("缺少密钥", "请选择该客户的密钥文件（secrets/<客户>.key）")
            return None
        try:
            max_cameras = int(self.max_cameras.get().strip())
            if max_cameras == 0 or max_cameras < -1:
                raise ValueError
        except ValueError:
            messagebox.showwarning("相机上限非法", "请填写正整数或 -1（不限）")
            return None
        months = MONTH_ITEMS[self.month_choice.current()][1]
        expire = None
        if not months:
            from tkinter.simpledialog import askstring
            expire = askstring("自定义到期日", "到期日（yyyy-MM-dd，含当天）：")
            if not expire:
                return None
        mode = MODE_ITEMS[self.mode_choice.current()][1]
        return dict(order=self.order.get().strip(), customer=self.customer.get().strip(),
                    max_cameras=max_cameras, months=months, expire=expire, mode=mode,
                    issued_by=self.issued_by.get().strip())

    def issue(self):
        v = self.validate()
        if not v:
            return
        months = v["months"]
        def work():
            cmd = [sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                "issue_license.py"), "issue",
                   "--machine-zip", self.machine_zip_path,
                   "--order", v["order"], "--customer", v["customer"],
                   "--max-cameras", str(v["max_cameras"]),
                   "--expired-mode", v["mode"],
                   "--secret-file", self.secret_path,
                   "--issued-by", v["issued_by"]]
            if months:
                cmd += ["--months", str(months)]
            else:
                cmd += ["--expire", v["expire"]]
            proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
            out = (proc.stdout or "") + (proc.stderr or "")
            self.root.after(0, lambda: self.finish_issue(proc.returncode == 0, out))
        threading.Thread(target=work, daemon=True).start()

    def finish_issue(self, ok, out):
        self.result.set(out.strip()[-600:])
        if ok:
            messagebox.showinfo("已生成", "授权包已生成，自检通过后即可发给客户")

    def inspect(self):
        order = self.order.get().strip()
        if not order:
            messagebox.showwarning("缺少单号", "自检按文件名 license-<单号>.zip 查找，请先填单号")
            return
        path = "license-%s.zip" % order
        if not os.path.isfile(path):
            messagebox.showwarning("找不到产物", "不存在 %s，请先生成" % path)
            return
        cmd = [sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                            "issue_license.py"), "inspect", path]
        if self.secret_path:
            cmd += ["--secret-file", self.secret_path]
        proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
        self.result.set(((proc.stdout or "") + (proc.stderr or "")).strip()[-600:])


def main():
    root = tk.Tk()
    IssueApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
