import { computed } from 'vue'

const defaultSameId = (a, b) => String(a ?? '') === String(b ?? '')

const rowIdSort = (a, b) => {
  const diff = (a.dbSortId ?? Number.MAX_SAFE_INTEGER) - (b.dbSortId ?? Number.MAX_SAFE_INTEGER)
  if (diff !== 0) return diff
  return (a.sourceIndex ?? 0) - (b.sourceIndex ?? 0)
}

const abnormalScore = (row) => {
  let score = 0
  if (row.statusCode === 2) score += 1000
  if (row.statusCode === 9) score += 800
  if (row.uploadState === 'offline') score += 500
  if (row.uploadState === 'delay') score += 300
  if (row.heartbeatState === 'offline') score += 200
  if (row.heartbeatState === 'delay') score += 100
  if (Number(row.highCount || 0) > 0) score += 80
  if (Number(row.abnormalCount || 0) > 0) score += 20
  if (Number(row.cpuUsage || 0) > 90 || Number(row.memoryUsage || 0) > 90 || Number(row.temperature || 0) > 70) score += 60
  return score
}

export function useCameraStatusTable({
  rows,
  tablePage,
  tablePageSize,
  searchKeyword,
  statusFilter,
  uploadFilter,
  abnormalFirst,
  filterParams,
  sameId = defaultSameId
}) {
  const filteredRows = computed(() => {
    const f = filterParams.value || {}
    const lineIds = f.lineIds || []
    const stationIds = f.stationIds || []
    const keyword = searchKeyword.value.trim().toLowerCase()
    const filtered = rows.value.filter((row) => {
      if (f.factoryId && !sameId(row.factoryId, f.factoryId)) return false
      if (lineIds.length && !lineIds.some(id => sameId(id, row.lineId))) return false
      if (stationIds.length && !stationIds.some(id => sameId(id, row.workstationId))) return false
      if (statusFilter.value !== 'all' && String(row.statusCode) !== statusFilter.value) return false
      if (uploadFilter.value !== 'all' && row.uploadState !== uploadFilter.value) return false
      if (keyword) {
        const haystack = [
          row.cameraName,
          row.cameraCode,
          row.stationName,
          row.productName,
          row.errorMessage,
          row.reportTime,
          row.lastUploadTime
        ].join(' ').toLowerCase()
        if (!haystack.includes(keyword)) return false
      }
      return true
    })
    if (!abnormalFirst.value) return [...filtered].sort(rowIdSort)
    return [...filtered].sort((a, b) => abnormalScore(b) - abnormalScore(a) || rowIdSort(a, b))
  })

  const pagedRows = computed(() => {
    const start = (tablePage.value - 1) * tablePageSize.value
    return filteredRows.value.slice(start, start + tablePageSize.value)
  })

  const stats = computed(() => {
    const result = {
      total: filteredRows.value.length,
      running: 0,
      standby: 0,
      fault: 0,
      offline: 0,
      uploadNormal: 0,
      uploadDelay: 0,
      uploadOffline: 0,
      uploadNone: 0,
      imageCount: 0,
      defectCount: 0,
      highCount: 0
    }
    filteredRows.value.forEach((row) => {
      if (row.statusCode === 1) result.running += 1
      else if (row.statusCode === 2) result.fault += 1
      else if (row.statusCode === 3) result.standby += 1
      else if (row.statusCode === 9) result.offline += 1
      if (row.uploadState === 'normal') result.uploadNormal += 1
      else if (row.uploadState === 'delay') result.uploadDelay += 1
      else if (row.uploadState === 'offline') result.uploadOffline += 1
      else result.uploadNone += 1
      result.imageCount += row.imageCount || 0
      result.defectCount += row.defectCount || 0
      result.highCount += row.highCount || 0
    })
    return result
  })

  return {
    filteredRows,
    pagedRows,
    stats
  }
}
