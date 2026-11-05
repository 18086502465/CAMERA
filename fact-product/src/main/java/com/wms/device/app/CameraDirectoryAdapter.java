package com.wms.device.app;

import com.wms.device.infra.repository.CameraDirectoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link CameraDirectory} 的 device 域实现：委托同域 Repository。
 * 消费方（analytics）只依赖接口，不感知 Repository 存在。
 */
@Component
public class CameraDirectoryAdapter implements CameraDirectory {

    @Autowired
    private CameraDirectoryRepository repository;

    @Override
    public int countDimCameraByCode(String cameraCode) {
        return repository.countDimCameraByCode(cameraCode);
    }

    @Override
    public int countDimCameraByCodeExcept(String cameraCode, Long cameraId) {
        return repository.countDimCameraByCodeExcept(cameraCode, cameraId);
    }

    @Override
    public int countDefectImageFiles(String cameraCode, String cameraName) {
        return repository.countDefectImageFiles(cameraCode, cameraName);
    }

    @Override
    public int countCompatImages(String cameraName) {
        return repository.countCompatImages(cameraName);
    }

    @Override
    public int countDefectImageBoxes(String cameraCode, String cameraName) {
        return repository.countDefectImageBoxes(cameraCode, cameraName);
    }

    @Override
    public int countDefectMappings(Long cameraId) {
        return repository.countDefectMappings(cameraId);
    }

    @Override
    public int countCameraTokens(Long cameraId) {
        return repository.countCameraTokens(cameraId);
    }

    @Override
    public int countHeartbeats(String cameraCode) {
        return repository.countHeartbeats(cameraCode);
    }

    @Override
    public int softDeleteCameraStatus(Long id) {
        return repository.softDeleteCameraStatus(id);
    }

    @Override
    public int softDeleteDimCamera(Long cameraId) {
        return repository.softDeleteDimCamera(cameraId);
    }

    @Override
    public int deleteDefectMappings(Long cameraId) {
        return repository.deleteDefectMappings(cameraId);
    }

    @Override
    public int deleteCameraTokens(Long cameraId) {
        return repository.deleteCameraTokens(cameraId);
    }

    @Override
    public int upsertDimCamera(Long cameraId, Integer factoryId, Integer lineId, Integer workstationId,
                               String cameraCode, String cameraName, String cameraType) {
        return repository.upsertDimCamera(cameraId, factoryId, lineId, workstationId,
                cameraCode, cameraName, cameraType);
    }

    @Override
    public int syncMappingLocation(Integer factoryId, Integer lineId, Integer workstationId,
                                   String cameraCode, Long cameraId) {
        return repository.syncMappingLocation(factoryId, lineId, workstationId, cameraCode, cameraId);
    }
}
