package com.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wms.entity.FactProduction30minArchive;

import java.util.Date;
import java.util.List;

/**
 * 生产数据归档表 Service 接口
 */
public interface IFactProduction30minArchiveService extends IService<FactProduction30minArchive> {

    /**
     * 查询指定日期范围的数据
     */
    List<FactProduction30minArchive> selectByDateRange(Date startDate, Date endDate);

    /**
     * 查询指定产线的数据
     */
    List<FactProduction30minArchive> selectByLineId(Integer lineId);

    /**
     * 批量插入归档数据
     */
    boolean batchInsert(List<FactProduction30minArchive> archiveList);
}
