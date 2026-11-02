package com.wms.controller;

import com.wms.entity.DimCompany;
import com.wms.service.IDimCompanyService;
import com.wms.vo.ResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/company")
public class DimCompanyController {

    @Autowired
    private IDimCompanyService companyService;

    @GetMapping
    public ResultVO<List<DimCompany>> listAll() {
        return ResultVO.success(companyService.list());
    }

    @GetMapping("/{id}")
    public ResultVO<DimCompany> getById(@PathVariable Integer id) {
        return ResultVO.success(companyService.getById(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResultVO<String> create(@RequestBody DimCompany company) {
        if (companyService.findByCompanyName(company.getCompanyName()) != null) {
            return ResultVO.error("公司名称已存在");
        }
        companyService.saveCompany(company);
        return ResultVO.success("创建成功");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ResultVO<String> update(@PathVariable Integer id, @RequestBody DimCompany company) {
        company.setCompanyId(id);
        companyService.updateCompany(company);
        return ResultVO.success("更新成功");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResultVO<String> delete(@PathVariable Integer id) {
        companyService.deleteCompany(id);
        return ResultVO.success("删除成功");
    }
}
