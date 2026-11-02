const DEPENDENCY_LABELS = {
  lineCount: '产线',
  stationCount: '工位',
  cameraCount: '相机',
  userCount: '用户',
  imageCount: '图片记录',
  defectBoxCount: '缺陷框',
  productionCount: '生产记录',
  defectCount: '缺陷记录'
}

/**
 * 「可自动级联清理」的依赖项。
 *
 * <p>2026-11-02 新增：相机删除现在会连带清理缺陷映射与设备令牌
 * （后端 DELETE /api/cameras/{id} 已改为级联清理）。
 * 这类依赖**不应该阻止删除** —— 否则所有配置了映射的相机都删不掉。
 *
 * <p>真正阻断删除的是「历史数据」：图片、缺陷框、生产记录、心跳 ——
 * 这些是业务事实，删相机不该连带删掉。
 */
const CASCADEABLE_KEYS = ['mappingCount', 'tokenCount']

/** 历史数据类依赖：存在即阻断删除 */
const BLOCKING_KEYS = [
  'lineCount', 'stationCount', 'cameraCount', 'userCount',
  'imageCount', 'defectBoxCount', 'productionCount', 'defectCount',
  'heartbeatCount'
]

export function useConfigRiskConfirm({ request, messageBox, typeShortLabel, finalConfigMenuKeys, apiPath }) {
  const dependencyPath = (type, id) => ['factory', 'line', 'workstation', 'camera'].includes(type)
    ? '/' + apiPath(type) + '/' + id + '/dependencies'
    : null

  const dependencyText = (deps = {}) => {
    const parts = Object.entries(DEPENDENCY_LABELS)
      .map(([key, label]) => deps[key] ? `${label} ${deps[key]} 条` : '')
      .filter(Boolean)
    // 心跳记录不在 DEPENDENCY_LABELS 里（无统一标签），单独补
    if (deps.heartbeatCount) parts.push(`心跳记录 ${deps.heartbeatCount} 条`)
    return parts.join('，') || '无依赖'
  }

  /** 可级联清理的依赖文案（用于告知用户"这些会一并清掉"） */
  const cascadeText = (deps = {}) => {
    const parts = []
    if (deps.mappingCount) parts.push(`缺陷映射 ${deps.mappingCount} 条`)
    if (deps.tokenCount) parts.push(`设备令牌 ${deps.tokenCount} 个`)
    return parts.join('，')
  }

  const fetchDependencies = async (type, id) => {
    const path = dependencyPath(type, id)
    if (!path) return {}
    const res = await request.get(path)
    return res.data?.data || {}
  }

  /**
   * 判断是否存在阻断删除的依赖。
   *
   * <p>2026-11-02 修正：原实现是「任意字段 > 0 就阻断」，
   * 这会把 `mappingCount`、`tokenCount` 这类**可级联清理**的项也当成阻断项，
   * 导致所有配了缺陷映射的相机都删不掉。
   * 现在只检查「历史数据类」依赖（BLOCKING_KEYS）。
   */
  const hasBlockingDependency = (deps = {}) =>
    BLOCKING_KEYS.some((key) => Number(deps[key]) > 0)

  const confirmDeleteRisk = async (type, id, name) => {
    const deps = await fetchDependencies(type, id)
    const details = dependencyText(deps)
    const hasRisk = hasBlockingDependency(deps)
    if (hasRisk) {
      await messageBox.alert(
        `当前${typeShortLabel(type)}「${name}」存在下级配置或历史数据：${details}。\n\n为避免破坏层级关系和历史统计，系统禁止直接删除。请先迁移/解绑下级配置，或后续使用归档能力处理。`,
        '禁止删除',
        { confirmButtonText: '我知道了', type: 'warning' }
      )
      throw 'cancel'
    }
    const cascade = cascadeText(deps)
    const cascadeHint = cascade ? `\n\n以下关联配置将一并清理：${cascade}。` : ''
    await messageBox.confirm(
      `确认删除${typeShortLabel(type)}「${name}」？${cascadeHint}\n\n删除后不可恢复。`,
      '删除二次确认',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning', dangerouslyUseHTMLString: false }
    )
  }

  const confirmUpdateRisk = async (type, id) => {
    if (finalConfigMenuKeys.includes(type)) return
    const deps = await fetchDependencies(type, id)
    const details = dependencyText(deps)
    if (!deps.riskLevel || deps.riskLevel === 'LOW') return
    await messageBox.confirm(
      `该配置存在依赖：${details}。\n\n修改名称通常安全；修改编码或所属关系，可能影响顶部级联、相机上报匹配和历史统计口径。是否继续保存？`,
      '配置修改风险提示',
      { confirmButtonText: '继续保存', cancelButtonText: '取消', type: 'warning' }
    )
  }

  const confirmImportRisk = async (mode) => {
    if (mode !== 'FULL') return
    await messageBox.confirm(
      '全量导入会将 Excel 文件外的相机标记为停用/离线。工厂、产线、工位不会物理删除，但会按编码更新文件内配置。是否继续？',
      '全量导入风险确认',
      { confirmButtonText: '确认导入', cancelButtonText: '取消', type: 'warning' }
    )
  }

  return {
    confirmDeleteRisk,
    confirmUpdateRisk,
    confirmImportRisk,
    fetchDependencies,
    dependencyText,
    cascadeText
  }
}
