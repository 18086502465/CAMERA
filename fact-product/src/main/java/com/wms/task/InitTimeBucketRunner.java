package com.wms.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wms.entity.DimTimeBucket30min;
import com.wms.mapper.DimTimeBucket30minMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用启动时初始化 dim_time_bucket_30min 时间分桶表
 */
@Component
public class InitTimeBucketRunner implements CommandLineRunner {

    @Autowired
    private DimTimeBucket30minMapper bucketMapper;

    private static final int BUCKET_PER_DAY = 48;

    @Override
    public void run(String... args) {
        // 检查表中是否已有数据（用 count 判断）
        Long totalCount = bucketMapper.selectCount(null);
        if (totalCount != null && totalCount > 0) {
            return;
        }

        LocalDate today = LocalDate.now();
        LocalDate nextYear = today.plusYears(1);
        LocalDate cursor = today.minusDays(7);

        List<DimTimeBucket30min> buckets = new ArrayList<>();

        while (!cursor.isAfter(nextYear)) {
            for (int bucketIdx = 0; bucketIdx < BUCKET_PER_DAY; bucketIdx++) {
                DimTimeBucket30min bucket = new DimTimeBucket30min();
                bucket.setFullDate(cursor);
                bucket.setHour((byte) (bucketIdx / 2));
                bucket.setMinuteGroup((bucketIdx % 2) == 0 ? "00" : "30");

                LocalTime startTime = LocalTime.of(bucketIdx / 2, (bucketIdx % 2) * 30);
                LocalDateTime startDateTime = LocalDateTime.of(cursor, startTime);
                bucket.setStartTime(startDateTime);
                bucket.setEndTime(startDateTime.plusMinutes(30));
                buckets.add(bucket);
            }
            cursor = cursor.plusDays(1);
        }

        // 逐条插入
        for (DimTimeBucket30min bucket : buckets) {
            bucketMapper.insert(bucket);
        }
    }
}
