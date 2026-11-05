package com.wms.analytics.app;

import com.wms.analytics.domain.model.FactAnomalyAlert;
import com.wms.analytics.infra.mapper.FactAnomalyAlertMapper;
import com.wms.analytics.app.IFactAnomalyAlertService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 异常分析告警事实表 服务实现类
 * </p>
 *
 * @author 45344
 * @since 2026-01-15
 */
@Service
public class FactAnomalyAlertServiceImpl extends ServiceImpl<FactAnomalyAlertMapper, FactAnomalyAlert> implements IFactAnomalyAlertService {

}
