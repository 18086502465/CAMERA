package com.wms.iam.infra.repository;

import com.wms.iam.api.dto.OperationLogQueryDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作日志查询仓储。
 *
 * 将操作日志中心的 SQL 统一归拢在 Repository 层，Controller 只负责协议入参/出参。
 */
@Repository
public class OperationLogRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public Map<String, Object> pageQuery(OperationLogQueryDTO query, int page, int size) {
        SqlParts where = buildWhere(query);
        int safePage = page < 1 ? 1 : page;
        int safeSize = size < 1 ? 10 : Math.min(size, 100);
        List<Object> dataArgs = new ArrayList<Object>(where.args);
        dataArgs.add(safeSize);
        dataArgs.add((safePage - 1) * safeSize);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                selectSql() + where.sql + " ORDER BY operation_time DESC LIMIT ? OFFSET ?",
                dataArgs.toArray());
        Long total = count(where);

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("records", rows);
        data.put("total", total == null ? 0 : total);
        data.put("size", safeSize);
        data.put("current", safePage);
        data.put("pages", total == null ? 0 : (long) Math.ceil(total.doubleValue() / safeSize));
        return data;
    }

    public Map<String, Object> summary(OperationLogQueryDTO query) {
        SqlParts where = buildWhere(query);
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("total", count(where));

        OperationLogQueryDTO successQuery = copy(query);
        successQuery.setResult(1);
        data.put("success", count(buildWhere(successQuery)));

        OperationLogQueryDTO failureQuery = copy(query);
        failureQuery.setResult(0);
        data.put("failure", count(buildWhere(failureQuery)));

        OperationLogQueryDTO todayQuery = copy(query);
        todayQuery.setStartDate(LocalDate.now());
        todayQuery.setEndDate(LocalDate.now());
        data.put("today", count(buildWhere(todayQuery)));

        List<Map<String, Object>> latest = jdbcTemplate.queryForList(
                selectSql() + where.sql + " ORDER BY operation_time DESC LIMIT 1",
                where.args.toArray());
        data.put("latest", latest.isEmpty() ? null : latest.get(0));
        return data;
    }

    public List<Map<String, Object>> exportRows(OperationLogQueryDTO query, int maxRows) {
        SqlParts where = buildWhere(query);
        int safeMax = Math.max(1, Math.min(maxRows, 20000));
        List<Object> args = new ArrayList<Object>(where.args);
        args.add(safeMax);
        return jdbcTemplate.queryForList(selectSql() + where.sql + " ORDER BY operation_time DESC LIMIT ?", args.toArray());
    }

    public Map<String, Object> detail(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql() + " AND log_id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int delete(Long id) {
        return jdbcTemplate.update("DELETE FROM sys_operation_log WHERE log_id=?", id);
    }

    public int clear() {
        return jdbcTemplate.update("DELETE FROM sys_operation_log");
    }

    private String selectSql() {
        return "SELECT log_id logId, user_id userId, user_name userName, operation_type operationType, module_name moduleName, " +
                "operation_desc operationDesc, request_method requestMethod, request_uri requestUri, request_params requestParams, " +
                "ip_address ipAddress, DATE_FORMAT(operation_time, '%Y-%m-%d %H:%i:%s') operationTime, execution_time executionTime, " +
                "operation_result operationResult, error_message errorMessage FROM sys_operation_log WHERE 1=1";
    }

    private Long count(SqlParts where) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM sys_operation_log WHERE 1=1" + where.sql, where.args.toArray(), Long.class);
        return value == null ? 0L : value;
    }

    private SqlParts buildWhere(OperationLogQueryDTO query) {
        OperationLogQueryDTO q = query == null ? new OperationLogQueryDTO() : query;
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<Object>();

        if (StringUtils.hasText(q.getKeyword())) {
            String like = "%" + q.getKeyword().trim() + "%";
            sql.append(" AND (user_name LIKE ? OR operation_desc LIKE ? OR request_uri LIKE ? OR request_params LIKE ? OR ip_address LIKE ? OR error_message LIKE ?)");
            args.add(like); args.add(like); args.add(like); args.add(like); args.add(like); args.add(like);
        }
        if (StringUtils.hasText(q.getOperation())) {
            sql.append(" AND operation_type LIKE ?");
            args.add("%" + q.getOperation().trim() + "%");
        }
        if (StringUtils.hasText(q.getModule())) {
            sql.append(" AND module_name LIKE ?");
            args.add("%" + q.getModule().trim() + "%");
        }
        if (q.getResult() != null) {
            sql.append(" AND operation_result = ?");
            args.add(q.getResult());
        }
        if (StringUtils.hasText(q.getUserName())) {
            sql.append(" AND user_name LIKE ?");
            args.add("%" + q.getUserName().trim() + "%");
        }
        if (StringUtils.hasText(q.getRequestMethod())) {
            sql.append(" AND request_method = ?");
            args.add(q.getRequestMethod().trim().toUpperCase());
        }
        if (StringUtils.hasText(q.getRequestUri())) {
            sql.append(" AND request_uri LIKE ?");
            args.add("%" + q.getRequestUri().trim() + "%");
        }
        if (StringUtils.hasText(q.getIpAddress())) {
            sql.append(" AND ip_address LIKE ?");
            args.add("%" + q.getIpAddress().trim() + "%");
        }
        if (q.getMinExecutionTime() != null) {
            sql.append(" AND execution_time >= ?");
            args.add(q.getMinExecutionTime());
        }
        if (q.getMaxExecutionTime() != null) {
            sql.append(" AND execution_time <= ?");
            args.add(q.getMaxExecutionTime());
        }
        if (q.getStartDate() != null) {
            Date start = Date.from(q.getStartDate().atStartOfDay(ZoneId.systemDefault()).toInstant());
            sql.append(" AND operation_time >= ?");
            args.add(start);
        }
        if (q.getEndDate() != null) {
            Date end = Date.from(q.getEndDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
            sql.append(" AND operation_time < ?");
            args.add(end);
        }
        return new SqlParts(sql.toString(), args);
    }

    private OperationLogQueryDTO copy(OperationLogQueryDTO source) {
        OperationLogQueryDTO s = source == null ? new OperationLogQueryDTO() : source;
        OperationLogQueryDTO target = new OperationLogQueryDTO();
        target.setKeyword(s.getKeyword());
        target.setOperation(s.getOperation());
        target.setModule(s.getModule());
        target.setResult(s.getResult());
        target.setStartDate(s.getStartDate());
        target.setEndDate(s.getEndDate());
        target.setUserName(s.getUserName());
        target.setRequestMethod(s.getRequestMethod());
        target.setRequestUri(s.getRequestUri());
        target.setIpAddress(s.getIpAddress());
        target.setMinExecutionTime(s.getMinExecutionTime());
        target.setMaxExecutionTime(s.getMaxExecutionTime());
        return target;
    }

    private static class SqlParts {
        private final String sql;
        private final List<Object> args;
        private SqlParts(String sql, List<Object> args) {
            this.sql = sql;
            this.args = args;
        }
    }
}
