package com.wms.device.app;

import com.wms.device.infra.repository.CameraDirectoryRepository;
import com.wms.shared.license.CameraCountProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link CameraCountProvider} 的 device 域实现。
 *
 * <p>只做一行转发：授权侧（system 域）不能直接依赖 device 的仓储
 * （ArchUnit 冻结跨域 infra 依赖），所以经由 shared SPI 反转方向。
 * 查询失败在这里吞掉转 null —— 机器码包缺相机数字段只是提示弱化，
 * 不该让「下载机器码包」这个主流程失败。
 */
@Component
public class CameraCountProviderImpl implements CameraCountProvider {

    @Autowired
    private CameraDirectoryRepository cameraDirectoryRepository;

    @Override
    public Integer currentCameraCount() {
        try {
            return cameraDirectoryRepository.countDimCameras();
        } catch (Exception e) {
            return null;
        }
    }
}
