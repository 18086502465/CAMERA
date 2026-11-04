package com.wms.controller;

import com.wms.dto.log.OperationLogQueryDTO;
import com.wms.repository.log.OperationLogRepository;
import com.wms.vo.ResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 系统操作日志查询 API。
 * Controller 只负责 HTTP 协议，查询 SQL 统一收敛到 OperationLogRepository。
 */
@RestController
@RequestMapping("/api/logs/operation")
public class SysOperationLogController {

    @Autowired
    private OperationLogRepository operationLogRepository;

    @GetMapping
    public ResultVO<Map<String, Object>> pageQuery(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) Integer result,
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) String requestMethod,
            @RequestParam(required = false) String requestUri,
            @RequestParam(required = false) String ipAddress,
            @RequestParam(required = false) Long minExecutionTime,
            @RequestParam(required = false) Long maxExecutionTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResultVO.success(operationLogRepository.pageQuery(
                buildQuery(keyword, operation, module, result, userName, requestMethod, requestUri, ipAddress,
                        minExecutionTime, maxExecutionTime, startDate, endDate),
                page == null ? 1 : page,
                size == null ? 10 : size));
    }

    @GetMapping("/summary")
    public ResultVO<Map<String, Object>> summary(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) Integer result,
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) String requestMethod,
            @RequestParam(required = false) String requestUri,
            @RequestParam(required = false) String ipAddress,
            @RequestParam(required = false) Long minExecutionTime,
            @RequestParam(required = false) Long maxExecutionTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResultVO.success(operationLogRepository.summary(
                buildQuery(keyword, operation, module, result, userName, requestMethod, requestUri, ipAddress,
                        minExecutionTime, maxExecutionTime, startDate, endDate)));
    }

    @GetMapping("/export")
    public void exportCsv(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) Integer result,
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) String requestMethod,
            @RequestParam(required = false) String requestUri,
            @RequestParam(required = false) String ipAddress,
            @RequestParam(required = false) Long minExecutionTime,
            @RequestParam(required = false) Long maxExecutionTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            HttpServletResponse response) throws IOException {
        List<Map<String, Object>> rows = operationLogRepository.exportRows(
                buildQuery(keyword, operation, module, result, userName, requestMethod, requestUri, ipAddress,
                        minExecutionTime, maxExecutionTime, startDate, endDate), 5000);
        String fileName = URLEncoder.encode("操作日志导出.csv", "UTF-8").replace("+", "%20");
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + fileName);
        StringBuilder sb = new StringBuilder();
        sb.append('\ufeff');
        sb.append("日志ID,操作人,操作类型,模块,操作描述,请求方法,请求地址,IP地址,操作时间,耗时ms,结果,错误信息\n");
        for (Map<String, Object> row : rows) {
            appendCsv(sb, row.get("logId"));
            appendCsv(sb, row.get("userName"));
            appendCsv(sb, row.get("operationType"));
            appendCsv(sb, row.get("moduleName"));
            appendCsv(sb, row.get("operationDesc"));
            appendCsv(sb, row.get("requestMethod"));
            appendCsv(sb, row.get("requestUri"));
            appendCsv(sb, row.get("ipAddress"));
            appendCsv(sb, row.get("operationTime"));
            appendCsv(sb, row.get("executionTime"));
            Object opResult = row.get("operationResult");
            appendCsv(sb, opResult != null && String.valueOf(opResult).equals("1") ? "成功" : "失败");
            sb.append(csv(row.get("errorMessage"))).append('\n');
        }
        response.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/{id}")
    public ResultVO<Map<String, Object>> detail(@PathVariable Long id) {
        return ResultVO.success(operationLogRepository.detail(id));
    }

    /**
     * <p><b>2026-11-04 修正</b>：补 {@code @PreAuthorize("hasRole('ADMIN')")}。
     * 路径级 {@code SecurityConfig} 已有 {@code /api/logs/operation/** → ADMIN} 保护，
     * 这里补方法级注解做双保险（与其它删除接口保持一致）。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/clear")
    public ResultVO<String> clear() {
        operationLogRepository.clear();
        return ResultVO.success("清空成功");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResultVO<String> delete(@PathVariable Long id) {
        operationLogRepository.delete(id);
        return ResultVO.success("删除成功");
    }

    private OperationLogQueryDTO buildQuery(String keyword, String operation, String module, Integer result,
                                            String userName, String requestMethod, String requestUri, String ipAddress,
                                            Long minExecutionTime, Long maxExecutionTime,
                                            LocalDate startDate, LocalDate endDate) {
        OperationLogQueryDTO query = new OperationLogQueryDTO();
        query.setKeyword(keyword);
        query.setOperation(operation);
        query.setModule(module);
        query.setResult(result);
        query.setUserName(userName);
        query.setRequestMethod(requestMethod);
        query.setRequestUri(requestUri);
        query.setIpAddress(ipAddress);
        query.setMinExecutionTime(minExecutionTime);
        query.setMaxExecutionTime(maxExecutionTime);
        query.setStartDate(startDate);
        query.setEndDate(endDate);
        return query;
    }

    private void appendCsv(StringBuilder sb, Object value) {
        sb.append(csv(value)).append(',');
    }

    private String csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        text = text.replace("\r", " ").replace("\n", " ").replace("\"", "\"\"");
        return "\"" + text + "\"";
    }
}
