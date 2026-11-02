import request from '../utils/request'

export const getProducts = (params) => request({ url: '/v1/config/products', method: 'get', params })
export const createProduct = (data) => request({ url: '/v1/config/products', method: 'post', data })
export const updateProduct = (id, data) => request({ url: `/v1/config/products/${id}`, method: 'put', data })
export const deleteProduct = (id) => request({ url: `/v1/config/products/${id}`, method: 'delete' })

export const getFinalDefectTypes = (params) => request({ url: '/v1/config/defect-types', method: 'get', params })
export const createFinalDefectType = (data) => request({ url: '/v1/config/defect-types', method: 'post', data })
export const updateFinalDefectType = (id, data) => request({ url: `/v1/config/defect-types/${id}`, method: 'put', data })
export const deleteFinalDefectType = (id) => request({ url: `/v1/config/defect-types/${id}`, method: 'delete' })

export const getCameraDefectMappings = (params) => request({ url: '/v1/config/camera-defect-mappings', method: 'get', params })
export const createCameraDefectMapping = (data) => request({ url: '/v1/config/camera-defect-mappings', method: 'post', data })
export const updateCameraDefectMapping = (id, data) => request({ url: `/v1/config/camera-defect-mappings/${id}`, method: 'put', data })
export const deleteCameraDefectMapping = (id) => request({ url: `/v1/config/camera-defect-mappings/${id}`, method: 'delete' })

export const getMappingExceptions = (params) => request({ url: '/v1/config/mapping-exceptions', method: 'get', params })
export const closeMappingException = (id) => request({ url: `/v1/config/mapping-exceptions/${id}/close`, method: 'put' })
