package com.wms.analytics.infra.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 统计报表查询 Repository。
 *
 * 当前先迁移 StatsController 中直接使用 JdbcTemplate 的简单统计 SQL，
 * 逐步降低 Controller 的 SQL 承载量。
 */
@Repository
public class StatsQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    public StatsQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> queryProductShiftYieldRows(Long brandId, LocalDate startDate, LocalDate endDate) {
        StringBuilder sql = new StringBuilder(
                "SELECT bucket_date, shift_id, SUM(total_count) AS total_count, SUM(ok_count) AS ok_count " +
                        "FROM fact_production_30min WHERE bucket_date BETWEEN ? AND ? ");
        List<Object> params = new ArrayList<>();
        params.add(startDate);
        params.add(endDate);
        if (brandId != null) {
            sql.append("AND brand_id = ? ");
            params.add(brandId);
        }
        sql.append("GROUP BY bucket_date, shift_id ORDER BY bucket_date");
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> queryYieldTrendRows(Integer lineId, Integer workstationId, Integer brandId,
                                                         LocalDate startDate, LocalDate endDate) {
        StringBuilder sql = new StringBuilder(
                "SELECT bucket_date, bucket_index, SUM(total_count) AS total_count, SUM(ok_count) AS ok_count, SUM(ng_count) AS ng_count " +
                        "FROM fact_production_30min WHERE bucket_date BETWEEN ? AND ? ");
        List<Object> params = new ArrayList<>();
        params.add(startDate);
        params.add(endDate);
        if (lineId != null) {
            sql.append("AND line_id = ? ");
            params.add(lineId);
        }
        if (workstationId != null) {
            sql.append("AND workstation_id = ? ");
            params.add(workstationId);
        }
        if (brandId != null) {
            sql.append("AND brand_id = ? ");
            params.add(brandId);
        }
        sql.append("GROUP BY bucket_date, bucket_index ORDER BY bucket_date, bucket_index");
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> queryStationRankingRows(Integer lineId, Integer brandId,
                                                             LocalDate startDate, LocalDate endDate) {
        StringBuilder sql = new StringBuilder(
                "SELECT workstation_id, SUM(total_count) AS total_count, SUM(ok_count) AS ok_count, SUM(ng_count) AS ng_count " +
                        "FROM fact_production_30min WHERE bucket_date BETWEEN ? AND ? AND workstation_id IS NOT NULL ");
        List<Object> params = new ArrayList<>();
        params.add(startDate);
        params.add(endDate);
        if (lineId != null) {
            sql.append("AND line_id = ? ");
            params.add(lineId);
        }
        if (brandId != null) {
            sql.append("AND brand_id = ? ");
            params.add(brandId);
        }
        sql.append("GROUP BY workstation_id");
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> queryBrandCompareRows(Integer lineId, LocalDate startDate, LocalDate endDate) {
        StringBuilder sql = new StringBuilder(
                "SELECT bucket_date, brand_id, SUM(total_count) AS total_count, SUM(ok_count) AS ok_count, SUM(ng_count) AS ng_count " +
                        "FROM fact_production_30min WHERE bucket_date BETWEEN ? AND ? AND brand_id IS NOT NULL ");
        List<Object> params = new ArrayList<>();
        params.add(startDate);
        params.add(endDate);
        if (lineId != null) {
            sql.append("AND line_id = ? ");
            params.add(lineId);
        }
        sql.append("GROUP BY bucket_date, brand_id ORDER BY bucket_date");
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }
}
