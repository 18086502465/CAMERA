# -*- coding: utf-8 -*-
"""把 static/assets 中 dist 里没有的旧 chunk 删掉（逐文件删，避免整目录删除超时）"""
import os

DIST = r'D:\031_45344camera\frontend\dist\assets'
STATIC = r'D:\031_45344camera\fact-product\src\main\resources\static\assets'

dist_files = set(os.listdir(DIST))
static_files = set(os.listdir(STATIC))

extra = static_files - dist_files
missing = dist_files - static_files

print("dist:", len(dist_files), " static:", len(static_files))
print("需删除的旧文件:", len(extra))
for f in sorted(extra):
    print("  -", f)
print("缺失需补拷:", sorted(missing))

for f in sorted(extra):
    try:
        os.remove(os.path.join(STATIC, f))
    except Exception as e:
        print("删除失败", f, e)

# 补拷缺失文件
import shutil
for f in sorted(missing):
    shutil.copy2(os.path.join(DIST, f), os.path.join(STATIC, f))

static_files = set(os.listdir(STATIC))
print("同步后 static:", len(static_files), " 完全一致:", static_files == dist_files)
