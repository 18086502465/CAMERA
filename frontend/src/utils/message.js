// element-plus ESM 入口只提供 default 导出，没有命名导出
// 这里从 default 导出中提取 ElMessage 和 ElMessageBox，供各组件按需使用
import ElementPlus from 'element-plus'

export const ElMessage = ElementPlus.ElMessage
export const ElMessageBox = ElementPlus.ElMessageBox
