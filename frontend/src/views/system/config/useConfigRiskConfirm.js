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

export function useConfigRiskConfirm({ request, messageBox, typeShortLabel, finalConfigMenuKeys, apiPath }) {
  const dependencyPath = (type, id) => ['factory', 'line', 'workstation', 'camera'].includes(type)
    ? '/' + apiPath(type) + '/' + id + '/dependencies'
    : null

  const dependencyText = (deps = {}) => Object.entries(DEPENDENCY_LABELS)
    .map(([key, label]) => deps[key] ? `${label} ${deps[key]} 条` : '')
    .filter(Boolean)
    .join('，') || '无依赖'

  const fetchDependencies = async (type, id) => {
    const path = dependencyPath(type, id)
    if (!path) return {}
    const res = await request.get(path)
    return res.data?.data || {}
  }

  const hasBlockingDependency = (deps = {}) => Object.entries(deps)
    .some(([key, value]) => key !== 'riskLevel' && Number(value) > 0)

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
    await messageBox.confirm(
      `确认删除${typeShortLabel(type)}「${name}」？\n\n删除后不可恢复。`,
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
    dependencyText
  }
}
