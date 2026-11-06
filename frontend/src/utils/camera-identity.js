/**
 * 相机身份匹配工具 —— code-first
 * ============================================================
 * 为什么需要这个工具（2026-11-05 现场 bug）：
 *
 *   相机状态页的「近批次图片 / 高危缺陷」两个指标，是把每台相机的图片统计
 *   **逐行累加**得到的。此前匹配相机与图片摘要时，把 `cameraName` 也当作
 *   匹配键，于是：
 *
 *     现场 camera_name **跨产线重名**（实测）：
 *       ZK54 的 CV1_Cam1 (DA8119487)  与  ZK23 的 CV1_Cam1 (DA8119489)
 *       ZK54 的 CV2_Cam1 (DA9200622)  与  ZK23 的 CV2_Cam1 (DA4733319)
 *
 *     ZK54 那两台相机按 code 匹配不到摘要 → 回退到按名称匹配 →
 *     **拿到了 ZK23 同名相机的统计** → 不筛选产线时多算一整条产线：
 *
 *       筛选 ZK54 → 0
 *       筛选 ZK23 → 3133
 *       不筛选   → 6266   ← 3133(真实) + 3133(错配)，子集加不起来
 *
 * 铁律（与后端一致）：**相机身份标识是 camera_code**（唯一索引 uk_camera_code），
 *   `camera_name` 只在**全局唯一**时才允许作为兜底；重名 → 宁可显示 0，
 *   也不能错配成别的相机的数据。
 *
 * 规则：
 *   ① 匹配键只用 code 类字段：cameraCode / deviceId / cameraId；
 *   ② 名称兜底需满足「summaries 侧唯一」且「cameras 侧唯一」；
 *   ③ 名称不唯一 → 不建该键 → 匹配不到就是 0。
 */

/** 取 code 字段（cameraCode 优先，其次 deviceId / cameraId） */
const codeOf = (item) => {
  if (!item) return ''
  const v = item.cameraCode || item.deviceId || item.cameraId
  return v == null ? '' : String(v).trim()
}

/**
 * 取 code 类匹配键（有序、去重）。
 * 注意：不包含 cameraName —— 名称重名会导致错配。
 */
export const cameraCodeKeys = (item) => {
  const keys = []
  const push = (v) => {
    if (v == null) return
    const s = String(v).trim()
    if (s && !keys.includes(s)) keys.push(s)
  }
  push(item?.cameraCode)
  push(item?.deviceId)
  push(item?.cameraId)
  return keys
}

/**
 * 统计一组相机里每个名称出现的次数（用于判断名称是否唯一）。
 * @param {Array} list 相机列表
 * @returns {Map<string, number>}
 */
export const countCameraNames = (list) => {
  const counts = new Map()
  ;(list || []).forEach((c) => {
    const name = (c?.cameraName || '').trim()
    if (name) counts.set(name, (counts.get(name) || 0) + 1)
  })
  return counts
}

/**
 * 构建「相机身份 → 摘要」索引。
 * @param {Array} list 摘要列表（每相机一行）
 * @returns {{ byCode: Map<string, any>, byUniqueName: Map<string, any> }}
 */
export const buildCameraSummaryIndex = (list) => {
  const byCode = new Map()
  const nameGroups = new Map()

  ;(list || []).forEach((summary) => {
    cameraCodeKeys(summary).forEach((k) => {
      if (!byCode.has(k)) byCode.set(k, summary)
    })
    const name = (summary?.cameraName || '').trim()
    if (name) {
      if (!nameGroups.has(name)) nameGroups.set(name, new Set())
      nameGroups.get(name).add(summary)
    }
  })

  // 名称 → 摘要：仅当该名称只对应**一个**摘要时才建立（重名不建）
  const byUniqueName = new Map()
  nameGroups.forEach((set, name) => {
    if (set.size === 1) byUniqueName.set(name, [...set][0])
  })

  return { byCode, byUniqueName }
}

/**
 * 按 code 优先、唯一名称兜底，查找某台相机的图片摘要。
 *
 * @param {{byCode: Map, byUniqueName: Map}} index buildCameraSummaryIndex 的结果
 * @param {object} item 相机对象
 * @param {Map<string, number>} [cameraNameCounts] countCameraNames 的结果；
 *        提供时，名称在相机列表侧也必须唯一才允许兜底
 * @returns {object|null} 命中的摘要；未命中返回 null
 */
export const findCameraSummary = (index, item, cameraNameCounts) => {
  // ① code 优先
  for (const k of cameraCodeKeys(item)) {
    const hit = index.byCode.get(k)
    if (hit) return hit
  }
  // ② 名称兜底：两侧都唯一才允许
  const name = (item?.cameraName || '').trim()
  if (!name) return null
  if (cameraNameCounts && (cameraNameCounts.get(name) || 0) > 1) return null
  return index.byUniqueName.get(name) || null
}
