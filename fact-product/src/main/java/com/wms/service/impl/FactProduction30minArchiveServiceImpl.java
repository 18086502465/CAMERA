package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.FactProduction30minArchive;
import com.wms.mapper.FactProduction30minArchiveMapper;
import com.wms.service.IFactProduction30minArchiveService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 生产数据归档表 Service 实现类
 */
@Service
public class FactProduction30minArchiveServiceImpl extends ServiceImpl<FactProduction30minArchiveMapper, FactProduction30minArchive> implements IFactProduction30minArchiveService {

    @Autowired
    private FactProduction30minArchiveMapper archiveMapper;

    @Override
    public List<FactProduction30minArchive> selectByDateRange(Date startDate, Date endDate) {
        return archiveMapper.selectByDateRange(startDate, endDate);
    }

    @Override
    public List<FactProduction30minArchive> selectByLineId(Integer lineId) {
        return archiveMapper.selectByLineId(lineId);
    }

    @Override
    public boolean batchInsert(List<FactProduction30minArchive> archiveList) {
        return saveBatch(archiveList);
    }
}
