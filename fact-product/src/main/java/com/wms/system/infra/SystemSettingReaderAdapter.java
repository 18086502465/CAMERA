package com.wms.system.infra;

import com.wms.device.app.SystemSettingReader;
import com.wms.system.infra.repository.SystemSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link SystemSettingReader} 的 system 域适配实现（委托同域 Repository）。
 * device 域心跳处理读取告警阈值时通过本端口访问，不感知 Repository。
 */
@Component
public class SystemSettingReaderAdapter implements SystemSettingReader {

    @Autowired
    private SystemSettingsRepository systemSettingsRepository;

    @Override
    public String findSettingValue(String key) {
        return systemSettingsRepository.findSettingValue(key);
    }
}
