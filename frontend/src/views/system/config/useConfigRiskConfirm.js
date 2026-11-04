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

/**
 * 各类配置的「阻断依赖」键 —— **必须与后端各 Controller 的拦截条件一一对应**。
 *
 * <p><b>2026-11-04 修正</b>：原实现是一份跨类型通用的 {@code BLOCKING_KEYS}，
 * 导致前端比后端更严，把「后端并不拦截」的项也当成阻断项：
 * <ul>
 *   <li>工位：后端只拦 {@code cameraCount>0}，前端却把
 *       {@code productionCount}/{@code defectCount} 也当阻断 → 工位永远删不掉；</li>
 *   <li>工厂：后端只拦 {@code lineCount}/{@code userCount}，前端却把
 *       {@code stationCount}/{@code cameraCount} 也当阻断 → 工厂永远删不掉。</li>
 * </ul>
 * 结果：用户点「删除」只会看到一个「禁止删除」弹窗，**看起来像按钮失效**。
 *
 * <p>现在改为<b>按类型分别定义</b>，且与后端 {@code dependencies} 返回的键、
 * 以及后端 DELETE 的实际拦截条件三者保持一致。
 */
const BLOCKING_KEYS_BY_TYPE = {
  // 后端 DimFactoryController.delete: lineCount>0 || userCount>0
  factory: ['lineCount', 'userCount'],
  // 后端 DimProductionLineController.delete: stationCount>0 || cameraCount>0
  line: ['stationCount', 'cameraCount'],
  // 后端 DimWorkstationController.delete: cameraCount>0
  workstation: ['cameraCount'],
  // 后端 CameraDashboardController.delete: imageCount+compatImageCount>0 || heartbeatCount>0
  camera: ['imageCount', 'heartbeatCount'],
  // 用户删除无依赖
  user: []
}

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
   *
   * <p>2026-11-04 修正：改为**按类型取阻断键**（见 {@link BLOCKING_KEYS_BY_TYPE}），
   * 阻断范围与后端 DELETE 的实际拦截条件严格一致 ——
   * 前端不得比后端更严（否则表现为「按钮点了没反应」），也不得更松（否则会白跑一趟请求）。
   */
  const hasBlockingDependency = (deps = {}, type = '') => {
    const keys = BLOCKING_KEYS_BY_TYPE[type]
    if (!keys) return false
    return keys.some((key) => Number(deps[key]) > 0)
  }

  const confirmDeleteRisk = async (type, id, name) => {
    const deps = await fetchDependencies(type, id)
    const details = dependencyText(deps)
    const hasRisk = hasBlockingDependency(deps, type)
    if (hasRisk) {
      // 阻断提示必须说清「被什么挡住」和「下一步做什么」，
      // 否则用户看到的就是一个无来由的「禁止删除」，以为按钮坏了。
      await messageBox.alert(
        `当前${typeShortLabel(type)}「${name}」存在下级配置或历史数据：${details}。\n\n` +
        `为避免破坏层级关系和历史统计，系统禁止直接删除。\n` +
        `请先处理以下依赖后重试：${blockingAdvice(type, deps)}`,
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

  /** 按类型给出「下一步该做什么」的可操作指引 */
  const blockingAdvice = (type, deps = {}) => {
    if (type === 'factory') {
      const parts = []
      if (Number(deps.lineCount) > 0) parts.push('先删除该工厂下的产线（产线需先清空下属工位与相机）')
      if (Number(deps.userCount) > 0) parts.push('先解绑该工厂下的用户')
      return parts.join('；') || '请先清理下属配置'
    }
    if (type === 'line') {
      const parts = []
      if (Number(deps.stationCount) > 0) parts.push('先删除该产线下的工位')
      if (Number(deps.cameraCount) > 0) parts.push('先删除或迁移该产线下的相机')
      return parts.join('；') || '请先清理下属配置'
    }
    if (type === 'workstation') {
      return Number(deps.cameraCount) > 0
        ? '先删除或改绑该工位下的相机'
        : '请先清理该工位的关联配置'
    }
    if (type === 'camera') {
      const parts = []
      if (Number(deps.imageCount) > 0) parts.push('该相机已有历史图片记录，需先完成数据归档')
      if (Number(deps.heartbeatCount) > 0) parts.push('该相机有设备心跳记录，需先停用设备')
      return parts.join('；') || '请先清理该相机的历史数据'
    }
    return '请先清理关联配置'
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
    cascadeText,
    blockingAdvice
  }
}
