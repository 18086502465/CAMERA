import request from '../utils/request'

// 相机分页查询
export const getCameraList = (params) => request({ url: '/cameras', method: 'get', params })

// 相机状态统计
export const getCameraStats = () => request({ url: '/cameras/stats', method: 'get' })

// 识别统计
export const getRecognizeStats = () => request({ url: '/cameras/recognize-stats', method: 'get' })

// 旧相机图片列表兼容接口
export const getCameraImages = (cameraName, limit = 5) =>
  request({ url: `/v1/camera/images/${encodeURIComponent(cameraName)}`, method: 'get', params: { limit } })

// 新版缺陷图片记录：图像记录页和相机状态页复用
export const getRecentDefectImages = (params = {}) =>
  request({ url: '/v1/defect-images/recent', method: 'get', params })

// 按相机聚合图像记录：返回每台相机的总图片数、严重度统计和最近图片
export const getDefectImageCameraSummaries = (params = {}) =>
  request({ url: '/v1/defect-images/camera-summaries', method: 'get', params })

// 单台相机图像记录分页：用于展开相机后的历史记录分页
export const getDefectImageHistory = (params = {}) =>
  request({ url: '/v1/defect-images/history', method: 'get', params })
