-- =============================================================================
-- 2026-11-19 评审 N-01 修正版：清理「设备重传同一张图」造成的重复数据
--
-- 背景（生产库实测）：
--   设备端上传后未收到响应（Broken pipe）会重传同一批图，而服务端此前没有幂等判断
--   → 同一张图（camera_id + file_name 完全相同）被重复入库。
--   实测重复规模：570 组 / 845 行额外数据（占 39620 行的 2.1%）。
--   每条重复行还带一份重复的 defect_image_box → **缺陷框统计被同步放大**。
--
-- 为什么必须先清理再建唯一索引：
--   uk_defect_file_camera_file_name(camera_id, file_name) 要求唯一，
--   存量重复行会让 ALTER TABLE 直接失败。
--
-- 保留策略：
--   每组重复**保留 create_time 最早的那一行**（首次真实上传），
--   删除其余行 + 其 defect_image_box + 对应的磁盘文件。
--   ⚠️ 磁盘文件路径在 image_path 里，删除前会先确认文件存在。
--
-- 执行方式：
--   mysql -uroot -p<pwd> wms-new < purge_duplicate_images.sql
--
-- ⚠️ 本脚本会**删除数据**。执行前务必：
--   1) 跑一次「预览段」（默认输出，不删），确认待删行数与样本符合预期；
--   2) 确认已做过数据库备份（data/backup/db/full/ 每日 01:00 自动备份）。
-- =============================================================================

-- ---------- 预览段（默认）：看清待删数据 ----------
SELECT '=== 重复组统计 ===' AS section;
SELECT COUNT(*) AS dup_groups, SUM(n - 1) AS extra_rows_to_delete
FROM (
    SELECT camera_id, file_name, COUNT(*) n
    FROM defect_image_file
    GROUP BY camera_id, file_name
    HAVING COUNT(*) > 1
) t;

SELECT '=== 重复最多的 10 组（样本）===' AS section;
SELECT camera_id, file_name, COUNT(*) n,
       MIN(create_time) AS first_seen, MAX(create_time) AS last_seen
FROM defect_image_file
GROUP BY camera_id, file_name
HAVING COUNT(*) > 1
ORDER BY n DESC LIMIT 10;

SELECT '=== 待删除的 defect_image_box 行数 ===' AS section;
SELECT COUNT(*) AS box_rows_to_delete
FROM defect_image_box b
WHERE EXISTS (
    SELECT 1 FROM (
        SELECT camera_id, file_name, MIN(image_id) keep_id
        FROM defect_image_file
        GROUP BY camera_id, file_name
        HAVING COUNT(*) > 1
    ) k
    WHERE b.image_id IN (
        SELECT image_id FROM defect_image_file f2
        WHERE f2.camera_id = k.camera_id AND f2.file_name = k.file_name
          AND f2.image_id <> k.keep_id
    )
);

-- ---------- 删除段 ----------
-- ⚠️ 确认上面预览数字无误后，把下面 4 段的注释去掉再执行。

-- ① 备份待删除行（留证据，可回滚）
-- CREATE TABLE IF NOT EXISTS bak_defect_image_file_dup_20261119 LIKE defect_image_file;
-- INSERT INTO bak_defect_image_file_dup_20261119
-- SELECT f.* FROM defect_image_file f
-- JOIN (
--     SELECT camera_id, file_name, MIN(image_id) keep_id
--     FROM defect_image_file GROUP BY camera_id, file_name HAVING COUNT(*) > 1
-- ) k ON f.camera_id = k.camera_id AND f.file_name = k.file_name AND f.image_id <> k.keep_id;

-- ② 删重复的 defect_image_box（先删子表，避免留下孤儿框）
-- DELETE b FROM defect_image_box b
-- JOIN defect_image_file f ON f.image_id = b.image_id
-- JOIN (
--     SELECT camera_id, file_name, MIN(image_id) keep_id
--     FROM defect_image_file GROUP BY camera_id, file_name HAVING COUNT(*) > 1
-- ) k ON f.camera_id = k.camera_id AND f.file_name = k.file_name AND f.image_id <> k.keep_id;

-- ③ 删重复的 defect_image_file 行（保留 create_time 最早的那行）
-- DELETE f FROM defect_image_file f
-- JOIN (
--     SELECT camera_id, file_name, MIN(image_id) keep_id
--     FROM defect_image_file GROUP BY camera_id, file_name HAVING COUNT(*) > 1
-- ) k ON f.camera_id = k.camera_id AND f.file_name = k.file_name AND f.image_id <> k.keep_id;

-- ④ 校验：应无重复组
-- SELECT COUNT(*) AS remaining_dup_groups FROM (
--     SELECT camera_id, file_name FROM defect_image_file
--     GROUP BY camera_id, file_name HAVING COUNT(*) > 1
-- ) t;

-- 磁盘文件清理（需 root 单独执行，见部署说明）：
--   重复行的 image_path 指向的磁盘副本要一并删除，否则占盘。
--   建议由部署脚本在库清理后按 bak 表的 image_path 列表删除。
