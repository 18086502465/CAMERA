# dist-release/linux/

构建产物输出目录，**不入库** —— 由 `installer/linux/build-release.sh` 生成：

- 全量交付包：`bash installer/linux/build-release.sh` → 组装 `stage/` 与 `camera-detection-system-1.0.0.tar.gz`
- 日常增量包：`bash installer/linux/build-release.sh --update-only` → 产出 `update/` 下 ~2MB 的 `update-<时间戳>.zip`

SQL 四段脚本、运维脚本、systemd 单元、配置模板的**真源**分别在
`fact-product/src/main/resources/sql/deploy/`、`installer/linux/scripts|systemd/`、
`installer/templates/`，构建时由脚本自动复制进本目录。
