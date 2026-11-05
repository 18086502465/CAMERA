# -*- coding: utf-8 -*-
"""DDD P2：按域搬包（git mv + package 声明 + 全局 import 替换 + XML 全限定名 + MapperScan）

只做物理迁移，不改任何逻辑。执行后 mvn compile 修残错（同包互引变跨包）。
"""
import os
import re
import subprocess

ROOT = r'D:\031_45344camera\fact-product'
SRC = os.path.join(ROOT, 'src', 'main', 'java', 'com', 'wms')

# 类名 -> 新包（不含类名）
MOVE = {
    # ---------- iam ----------
    'AuthController': 'com.wms.iam.api',
    'SysUserController': 'com.wms.iam.api',
    'SysOperationLogController': 'com.wms.iam.api',
    'JwtUtil': 'com.wms.iam.domain',
    'JwtRequestFilter': 'com.wms.iam.infra',
    'SecurityConfig': 'com.wms.iam.infra',
    'SysUserMapper': 'com.wms.iam.infra.mapper',
    'SysOperationLogMapper': 'com.wms.iam.infra.mapper',
    'SysUser': 'com.wms.iam.domain.model',
    'SysOperationLog': 'com.wms.iam.domain.model',
    'ISysUserService': 'com.wms.iam.app',
    'SysUserServiceImpl': 'com.wms.iam.app',
    'ISysOperationLogService': 'com.wms.iam.app',
    'SysOperationLogServiceImpl': 'com.wms.iam.app',
    'OperationLogRepository': 'com.wms.iam.infra.repository',
    'OperationLogQueryDTO': 'com.wms.iam.api.dto',
    # ---------- masterdata ----------
    'DimFactoryController': 'com.wms.masterdata.api',
    'DimCompanyController': 'com.wms.masterdata.api',
    'DimProductionLineController': 'com.wms.masterdata.api',
    'DimWorkstationController': 'com.wms.masterdata.api',
    'SysHolidayConfigController': 'com.wms.masterdata.api',
    'CascadeController': 'com.wms.masterdata.api',
    'IDimFactoryService': 'com.wms.masterdata.app',
    'DimFactoryServiceImpl': 'com.wms.masterdata.app',
    'IDimCompanyService': 'com.wms.masterdata.app',
    'DimCompanyServiceImpl': 'com.wms.masterdata.app',
    'IDimProductionLineService': 'com.wms.masterdata.app',
    'DimProductionLineServiceImpl': 'com.wms.masterdata.app',
    'IDimWorkstationService': 'com.wms.masterdata.app',
    'DimWorkstationServiceImpl': 'com.wms.masterdata.app',
    'IDimProductBrandService': 'com.wms.masterdata.app',
    'DimProductBrandServiceImpl': 'com.wms.masterdata.app',
    'ISysHolidayConfigService': 'com.wms.masterdata.app',
    'SysHolidayConfigServiceImpl': 'com.wms.masterdata.app',
    'DimFactoryMapper': 'com.wms.masterdata.infra.mapper',
    'DimCompanyMapper': 'com.wms.masterdata.infra.mapper',
    'DimProductionLineMapper': 'com.wms.masterdata.infra.mapper',
    'DimWorkstationMapper': 'com.wms.masterdata.infra.mapper',
    'DimProductBrandMapper': 'com.wms.masterdata.infra.mapper',
    'SysHolidayConfigMapper': 'com.wms.masterdata.infra.mapper',
    'DimFactory': 'com.wms.masterdata.domain.model',
    'DimCompany': 'com.wms.masterdata.domain.model',
    'DimProductionLine': 'com.wms.masterdata.domain.model',
    'DimWorkstation': 'com.wms.masterdata.domain.model',
    'DimProductBrand': 'com.wms.masterdata.domain.model',
    'SysHolidayConfig': 'com.wms.masterdata.domain.model',
    # ---------- device ----------
    'CameraStatusHeartbeatController': 'com.wms.device.api',
    'CameraImageController': 'com.wms.device.api',
    'ServerMetricsController': 'com.wms.device.api',
    'ICameraStatusService': 'com.wms.device.app',
    'CameraStatusServiceImpl': 'com.wms.device.app',
    'IServerMetricsService': 'com.wms.device.app',
    'ServerMetricsServiceImpl': 'com.wms.device.app',
    'CameraStatusMapper': 'com.wms.device.infra.mapper',
    'CameraImageMapper': 'com.wms.device.infra.mapper',
    'CameraStatus': 'com.wms.device.domain.model',
    'CameraImage': 'com.wms.device.domain.model',
    'ServerMetricsVO': 'com.wms.device.api.vo',
    'CameraHeartbeatResponseDTO': 'com.wms.device.api.dto',
    'CameraStatusHeartbeatDTO': 'com.wms.device.api.dto',
    # ---------- defect ----------
    'DefectImageController': 'com.wms.defect.api',
    'IDefectImageService': 'com.wms.defect.app',
    'DefectImageServiceImpl': 'com.wms.defect.app',
    'DefectImageUploadMetadataDTO': 'com.wms.defect.api.dto',
    'DefectImageUploadResultDTO': 'com.wms.defect.api.dto',
    # ---------- inspection ----------
    'BatchUploadController': 'com.wms.inspection.api',
    'ProductDetectionHistoryController': 'com.wms.inspection.api',
    'FactProductionInfoController': 'com.wms.inspection.api',
    'InspectionUploadService': 'com.wms.inspection.app',
    'InspectionUploadException': 'com.wms.inspection.app',
    'IProductDetectionHistoryService': 'com.wms.inspection.app',
    'ProductDetectionHistoryServiceImpl': 'com.wms.inspection.app',
    'IFactProductionInfoService': 'com.wms.inspection.app',
    'FactProductionInfoServiceImpl': 'com.wms.inspection.app',
    'ProductDetectionHistoryMapper': 'com.wms.inspection.infra.mapper',
    'FactProductionInfoMapper': 'com.wms.inspection.infra.mapper',
    'ProductDetectionHistory': 'com.wms.inspection.domain.model',
    'FactProductionInfo': 'com.wms.inspection.domain.model',
    'BatchUploadDTO': 'com.wms.inspection.api.dto',
    'BatchUploadResultDTO': 'com.wms.inspection.api.dto',
    'BatchUploadResultDetailDTO': 'com.wms.inspection.api.dto',
    'InspectionBatchUploadRequest': 'com.wms.inspection.api.dto',
    'InspectionBatchUploadResponse': 'com.wms.inspection.api.dto',
    # ---------- analytics ----------
    'LineAnalyticsController': 'com.wms.analytics.api',
    'StatsController': 'com.wms.analytics.api',
    'CameraDashboardController': 'com.wms.analytics.api',
    'FactProduction30minController': 'com.wms.analytics.api',
    'LineAnalyticsRepository': 'com.wms.analytics.infra.repository',
    'StatsQueryRepository': 'com.wms.analytics.infra.repository',
    'IFactProduction30minService': 'com.wms.analytics.app',
    'FactProduction30minServiceImpl': 'com.wms.analytics.app',
    'IFactProduction30minArchiveService': 'com.wms.analytics.app',
    'FactProduction30minArchiveServiceImpl': 'com.wms.analytics.app',
    'IFactAnomalyAlertService': 'com.wms.analytics.app',
    'FactAnomalyAlertServiceImpl': 'com.wms.analytics.app',
    'IDimTimeBucket30minService': 'com.wms.analytics.app',
    'DimTimeBucket30minServiceImpl': 'com.wms.analytics.app',
    'FactProduction30minMapper': 'com.wms.analytics.infra.mapper',
    'FactProduction30minArchiveMapper': 'com.wms.analytics.infra.mapper',
    'FactAnomalyAlertMapper': 'com.wms.analytics.infra.mapper',
    'DimTimeBucket30minMapper': 'com.wms.analytics.infra.mapper',
    'FactProduction30min': 'com.wms.analytics.domain.model',
    'FactProduction30minArchive': 'com.wms.analytics.domain.model',
    'FactAnomalyAlert': 'com.wms.analytics.domain.model',
    'DimTimeBucket30min': 'com.wms.analytics.domain.model',
    # ---------- system ----------
    'FinalConfigController': 'com.wms.system.api',
    'SystemSettingsController': 'com.wms.system.api',
    'ConfigImportController': 'com.wms.system.api',
    'DataCleanupTask': 'com.wms.system.infra.task',
    'InitData': 'com.wms.system.infra.task',
    'InitTimeBucketRunner': 'com.wms.system.infra.task',
    'ConfigImportResultDTO': 'com.wms.system.api.dto',
    'ExcelImportResultVO': 'com.wms.system.api.vo',
}

# XML 与 Java 里的旧全限定名 -> 新
FQN_MAP = {}
for cls, pkg in MOVE.items():
    # 推断旧包
    if cls.endswith('Controller'):
        old = 'com.wms.controller'
    elif cls.startswith('Dim') and cls.endswith('Mapper') or cls in (
            'SysUserMapper', 'SysOperationLogMapper', 'CameraStatusMapper',
            'CameraImageMapper', 'ProductDetectionHistoryMapper',
            'FactProductionInfoMapper', 'FactProduction30minMapper',
            'FactProduction30minArchiveMapper', 'FactAnomalyAlertMapper',
            'DimTimeBucket30minMapper'):
        old = 'com.wms.mapper'
    elif cls.startswith('I') and cls[1:2].isupper() or cls.endswith('ServiceImpl'):
        old = 'com.wms.service'
    elif cls == 'InspectionUploadService' or cls == 'InspectionUploadException':
        old = 'com.wms.service.inspection'
    elif cls == 'OperationLogRepository':
        old = 'com.wms.repository.log'
    elif cls == 'LineAnalyticsRepository':
        old = 'com.wms.repository.analytics'
    elif cls == 'StatsQueryRepository':
        old = 'com.wms.repository.stats'
    elif cls in ('JwtUtil', 'JwtRequestFilter', 'ExcelUtils', 'ShiftCalculator', 'CodeGenerator'):
        old = 'com.wms.utils'
    elif cls in ('SecurityConfig',):
        old = 'com.wms.config'
    elif cls in ('DataCleanupTask', 'InitData', 'InitTimeBucketRunner'):
        old = 'com.wms.task'
    elif cls.endswith('DTO') or cls.endswith('Request') or cls.endswith('Response'):
        old = 'com.wms.dto'
    elif cls.endswith('VO'):
        old = 'com.wms.vo'
    else:
        old = 'com.wms.entity'
    FQN_MAP[old + '.' + cls] = pkg + '.' + cls

# 特例修正（通配规则误判的）
FQN_MAP['com.wms.service.IProductDetectionHistoryService'] = 'com.wms.inspection.app.IProductDetectionHistoryService'
FQN_MAP['com.wms.service.IFactProductionInfoService'] = 'com.wms.inspection.app.IFactProductionInfoService'
FQN_MAP['com.wms.service.IFactProduction30minService'] = 'com.wms.analytics.app.IFactProduction30minService'
FQN_MAP['com.wms.service.IFactProduction30minArchiveService'] = 'com.wms.analytics.app.IFactProduction30minArchiveService'
FQN_MAP['com.wms.service.IFactAnomalyAlertService'] = 'com.wms.analytics.app.IFactAnomalyAlertService'
FQN_MAP['com.wms.service.IDimTimeBucket30minService'] = 'com.wms.analytics.app.IDimTimeBucket30minService'


def git(*args):
    r = subprocess.run(['git'] + list(args), cwd=ROOT, capture_output=True, text=True)
    if r.returncode != 0:
        print('  git err:', r.stderr.strip()[:200])
    return r.stdout.strip()


def to_path(pkg):
    return SRC + '/' + pkg.replace('com.wms.', '').replace('.', '/') + '.java'


moved, missing = 0, []
for cls, pkg in MOVE.items():
    # 找旧路径
    candidates = []
    for old_pkg in set(v.rsplit('.', 1)[0] for v in FQN_MAP.values() if v.endswith('.' + cls)) | \
                   {k.rsplit('.', 1)[0] for k, v in FQN_MAP.items() if k.endswith('.' + cls)}:
        candidates.append(old_pkg)
    src_path = None
    for old_pkg in ['com.wms.controller', 'com.wms.entity', 'com.wms.mapper', 'com.wms.service',
                    'com.wms.service.impl', 'com.wms.service.inspection', 'com.wms.utils',
                    'com.wms.config', 'com.wms.task', 'com.wms.dto', 'com.wms.vo',
                    'com.wms.repository.log', 'com.wms.repository.analytics',
                    'com.wms.repository.stats', 'com.wms.dto.log', 'com.wms.dto.inspection']:
        p = SRC + '/' + old_pkg.replace('com.wms.', '').replace('.', '/') + '/' + cls + '.java'
        if os.path.exists(p):
            src_path = p
            break
    if not src_path:
        missing.append(cls)
        continue
    dst_path = to_path(pkg + '.' + cls)  # ★修复：to_path 期望全限定名
    os.makedirs(os.path.dirname(dst_path), exist_ok=True)
    git('mv', os.path.relpath(src_path, ROOT).replace('\\', '/'),
        os.path.relpath(dst_path, ROOT).replace('\\', '/'))
    # 改 package 声明
    s = open(dst_path, encoding='utf-8').read()
    s2 = re.sub(r'^package\s+com\.wms\.[\w.]+\s*;', 'package %s;' % pkg, s, count=1, flags=re.M)
    open(dst_path, 'w', encoding='utf-8').write(s2)
    moved += 1

print('已迁移 %d 个类，未找到 %d 个: %s' % (moved, len(missing), missing))

# ---- 全局替换 import / 全限定名（java + xml + yml）----
n = 0
for base in [os.path.join(ROOT, 'src'),
             os.path.join(ROOT, 'src', 'main', 'resources', 'mapper')]:
    for root, dirs, files in os.walk(base):
        for fn in files:
            if not fn.endswith(('.java', '.xml', '.yml')):
                continue
            p = os.path.join(root, fn)
            s = open(p, encoding='utf-8').read()
            o = s
            for a, b in sorted(FQN_MAP.items(), key=lambda kv: -len(kv[0])):
                s = s.replace(a, b)
            # 通配 import：com.wms.service.* -> 无法简单映射，交给编译修错
            if s != o:
                open(p, 'w', encoding='utf-8').write(s)
                n += 1
print('已更新引用的文件数:', n)

# ---- MapperScan 更新 ----
app = os.path.join(SRC, 'TimeBucketApplication.java')
s = open(app, encoding='utf-8').read()
s = s.replace('@MapperScan("com.wms.mapper")',
              '@MapperScan({\n'
              '        "com.wms.iam.infra.mapper",\n'
              '        "com.wms.masterdata.infra.mapper",\n'
              '        "com.wms.device.infra.mapper",\n'
              '        "com.wms.defect.infra.mapper",\n'
              '        "com.wms.inspection.infra.mapper",\n'
              '        "com.wms.analytics.infra.mapper",\n'
              '        "com.wms.system.infra.mapper"\n'
              '}) // DDD 拆分：按域扫描 mapper')
open(app, 'w', encoding='utf-8').write(s)
print('MapperScan 已更新')

# ---- 删除空目录 ----
for d in ['controller', 'entity', 'mapper', 'service/impl', 'service/inspection',
          'service', 'utils', 'config', 'task', 'dto/log', 'dto/inspection',
          'dto', 'vo', 'repository/log', 'repository/analytics', 'repository/stats',
          'repository']:
    p = os.path.join(SRC, d)
    if os.path.isdir(p) and not os.listdir(p):
        os.rmdir(p)
        print('已删空目录:', d)
