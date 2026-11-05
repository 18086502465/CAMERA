#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""清理 P3 批次② 验证产生的 ZZTEST 测试数据（服务器端执行）。

只删除 ZZTEST 前缀的行，不触碰任何现场数据。
口令从环境变量读取，不写入仓库。

用法（在服务器上）：
    export MYSQL_PASSWORD='<库口令>'
    python3 cleanup_p3b2_testdata.py
"""
import os
import subprocess
import sys

PWD = os.environ.get('MYSQL_PASSWORD', '')
DB = 'wms-new'

if not PWD:
    sys.exit('请先 export MYSQL_PASSWORD=<库口令>')

SQL = """
SELECT 'dim_product' AS t, COUNT(*) AS n FROM dim_product
  WHERE product_code LIKE 'ZZTEST%' OR product_name LIKE 'ZZTEST%'
UNION ALL
SELECT 'dim_defect_type', COUNT(*) FROM dim_defect_type
  WHERE defect_code LIKE 'ZZTEST%' OR defect_name LIKE 'ZZTEST%'
UNION ALL
SELECT 'cfg_camera_defect_mapping', COUNT(*) FROM cfg_camera_defect_mapping
  WHERE source_code LIKE 'ZZTEST%';

DELETE FROM cfg_camera_defect_mapping WHERE source_code LIKE 'ZZTEST%';
DELETE FROM dim_defect_type WHERE defect_code LIKE 'ZZTEST%' OR defect_name LIKE 'ZZTEST%';
DELETE FROM dim_product WHERE product_code LIKE 'ZZTEST%' OR product_name LIKE 'ZZTEST%';

SELECT '--- 清理后 ---' AS '';
SELECT 'dim_product' AS t, COUNT(*) AS n FROM dim_product
  WHERE product_code LIKE 'ZZTEST%' OR product_name LIKE 'ZZTEST%'
UNION ALL
SELECT 'dim_defect_type', COUNT(*) FROM dim_defect_type
  WHERE defect_code LIKE 'ZZTEST%' OR defect_name LIKE 'ZZTEST%'
UNION ALL
SELECT 'cfg_camera_defect_mapping', COUNT(*) FROM cfg_camera_defect_mapping
  WHERE source_code LIKE 'ZZTEST%';
"""

cmd = ['mysql', '-uroot', '-p' + PWD, DB, '-e', SQL]
r = subprocess.run(cmd, capture_output=True, text=True)
out = (r.stdout or '') + (r.stderr or '')
# 过滤 mysql 的 password 警告行
out = '\n'.join(l for l in out.splitlines() if 'Using a password' not in l)
print(out)
sys.exit(r.returncode)
