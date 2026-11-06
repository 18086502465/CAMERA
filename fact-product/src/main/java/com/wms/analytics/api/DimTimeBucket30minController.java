package com.wms.analytics.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.analytics.domain.model.DimTimeBucket30min;
import com.wms.analytics.app.IDimTimeBucket30minService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Date;
import java.util.List;

/**
 * <p>
 * 30分钟时间分桶维度表 前端控制器
 * </p>
 *
 * @author 45344
 * @since 2026-01-10
 */
@RestController
@RequestMapping("/DimTimeBucket30min")
public class DimTimeBucket30minController {

    @Autowired
    private IDimTimeBucket30minService DimTimeBucket30minService;

    // SEC-02（2026-11-06）：本控制器映射为 "/DimTimeBucket30min"（无 /api 前缀），
    // 历史 SecurityConfig 规则写的 /api/DimTimeBucket30min/** 匹配不到真实路径，
    // 写操作曾落入"仅登录"兜底。方法级鉴权与路径级规则（SecurityConfig）双保险，
    // 写操作一律 ADMIN；读端点维持登录即可。
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<Boolean> save(@RequestBody DimTimeBucket30min DimTimeBucket30min) {
        return ResponseEntity.ok(DimTimeBucket30minService.save(DimTimeBucket30min));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping
    public ResponseEntity<Boolean> updateById(@RequestBody DimTimeBucket30min DimTimeBucket30min) {
        return ResponseEntity.ok(DimTimeBucket30minService.updateById(DimTimeBucket30min));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Boolean> removeById(@PathVariable Integer id) {
        return ResponseEntity.ok(DimTimeBucket30minService.removeById(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DimTimeBucket30min> getById(@PathVariable Integer id) {
        return ResponseEntity.ok(DimTimeBucket30minService.getById(id));
    }

    @GetMapping
    public ResponseEntity<List<DimTimeBucket30min>> list() {
        return ResponseEntity.ok(DimTimeBucket30minService.list());
    }

    @GetMapping("/page")
    public ResponseEntity<IPage<DimTimeBucket30min>> page(@RequestParam Integer pageNum, @RequestParam Integer pageSize) {
        Page<DimTimeBucket30min> page = new Page<>(pageNum, pageSize);
        return ResponseEntity.ok(DimTimeBucket30minService.page(page));
    }

    // 【专属你的业务】按日期查询30分钟时间桶，超实用！
    @GetMapping("/date/{fullDate}")
    public ResponseEntity<List<DimTimeBucket30min>> getByDate(@PathVariable Date fullDate) {
        LambdaQueryWrapper<DimTimeBucket30min> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DimTimeBucket30min::getFullDate, fullDate);
        return ResponseEntity.ok(DimTimeBucket30minService.list(wrapper));
    }
}