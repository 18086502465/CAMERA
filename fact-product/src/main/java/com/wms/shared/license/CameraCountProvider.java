package com.wms.shared.license;

/**
 * 相机总数查询 SPI。
 *
 * <p>机器码包要带「现场相机数」，让研发签发时判断档位是否够用；但
 * {@code LicenseService} 刻意不依赖任何业务域（见其类注释），相机表在 device 域。
 * 所以按既有的 shared-SPI 模式：本接口定义在 shared，由 device 域提供实现 Bean，
 * {@code LicensePackageService} 用 {@code ObjectProvider} 可选注入 ——
 * 实现缺失（单测 / 精简环境）时相机数上报为 null，不阻断流程。
 */
public interface CameraCountProvider {

    /**
     * @return 当前注册相机总数（未删除）；查询失败返回 null，由调用方决定降级
     */
    Integer currentCameraCount();
}
