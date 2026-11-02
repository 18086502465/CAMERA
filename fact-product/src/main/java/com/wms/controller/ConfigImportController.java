package com.wms.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wms.dto.ConfigImportResultDTO;
import com.wms.entity.CameraStatus;
import com.wms.entity.DimFactory;
import com.wms.entity.DimProductionLine;
import com.wms.entity.DimWorkstation;
import com.wms.mapper.CameraStatusMapper;
import com.wms.mapper.DimFactoryMapper;
import com.wms.mapper.DimProductionLineMapper;
import com.wms.mapper.DimWorkstationMapper;
import com.wms.vo.ResultVO;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工厂、产线、工位、相机整体导入。
 */
@RestController
@RequestMapping("/api/config-import")
@PreAuthorize("hasRole('ADMIN')")
public class ConfigImportController {

    private static final String MODE_INCREMENTAL = "INCREMENTAL";
    private static final String MODE_FULL = "FULL";

    @Autowired private DimFactoryMapper factoryMapper;
    @Autowired private DimProductionLineMapper lineMapper;
    @Autowired private DimWorkstationMapper workstationMapper;
    @Autowired private CameraStatusMapper cameraStatusMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @GetMapping("/template")
    public void downloadTemplate(HttpServletResponse response) throws Exception {
        Workbook workbook = new XSSFWorkbook();
        try {
            Sheet sheet = workbook.createSheet("配置导入");
            String[] headers = new String[] {
                    "工厂编码", "工厂名称", "工厂地址",
                    "产线编码", "产线名称",
                    "工位编码", "工位名称",
                    "相机编号", "相机名称"
            };

            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 18 * 256);
            }

            writeExample(sheet.createRow(1), new String[] {
                    "HD", "华东工厂", "上海市浦东新区",
                    "LINE-01", "L1-亲宝宝专线",
                    "WS-01", "工位1-上料区",
                    "CAM-001", "相机-001"
            });
            writeExample(sheet.createRow(2), new String[] {
                    "HD", "华东工厂", "上海市浦东新区",
                    "LINE-01", "L1-亲宝宝专线",
                    "WS-01", "工位1-上料区",
                    "CAM-002", "相机-002"
            });

            String fileName = URLEncoder.encode("配置整体导入模板.xlsx", "UTF-8").replace("+", "%20");
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + fileName);
            workbook.write(response.getOutputStream());
        } finally {
            workbook.close();
        }
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResultVO<ConfigImportResultDTO> importConfig(@RequestParam("file") MultipartFile file,
                                                        @RequestParam(defaultValue = MODE_INCREMENTAL) String mode) {
        ConfigImportResultDTO result = new ConfigImportResultDTO();
        String normalizedMode = normalizeMode(mode);
        result.setMode(normalizedMode);

        if (file == null || file.isEmpty()) {
            result.addError("上传文件为空，请选择配置导入模板文件。");
            result.setFailedRows(1);
            return ResultVO.fail("导入失败", result);
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!filename.endsWith(".xlsx") && !filename.endsWith(".xls")) {
            result.addError("文件格式不正确，仅支持 .xlsx 或 .xls。");
            result.setFailedRows(1);
            return ResultVO.fail("导入失败", result);
        }

        ImportContext context = new ImportContext();
        try (InputStream inputStream = file.getInputStream(); Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            if (sheet == null) {
                result.addError("Excel 中未找到有效 Sheet。");
                result.setFailedRows(1);
                return ResultVO.fail("导入失败", result);
            }
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter();

            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (isEmptyRow(row, formatter, evaluator)) {
                    continue;
                }
                result.setTotalRows(result.getTotalRows() + 1);
                try {
                    ImportRow data = readImportRow(row, formatter, evaluator);
                    validateRow(data, rowIndex + 1);
                    if (context.processedCameraCodes.contains(data.cameraCode)) {
                        result.setSkippedRows(result.getSkippedRows() + 1);
                        result.addWarning("第 " + (rowIndex + 1) + " 行相机编号 " + data.cameraCode + " 重复，已跳过重复行。");
                        continue;
                    }

                    DimFactory factory = upsertFactory(data, context, result);
                    DimProductionLine line = upsertLine(data, factory, context, result);
                    DimWorkstation workstation = upsertWorkstation(data, line, context, result);
                    upsertCamera(data, line, workstation, context, result);
                    context.processedCameraCodes.add(data.cameraCode);
                    result.setSuccessRows(result.getSuccessRows() + 1);
                } catch (Exception rowError) {
                    result.setFailedRows(result.getFailedRows() + 1);
                    result.addError("第 " + (rowIndex + 1) + " 行：" + rowError.getMessage());
                }
            }

            if (result.getTotalRows() == 0) {
                result.addError("Excel 没有可导入的数据行，请保留表头并从第 2 行开始填写。");
                result.setFailedRows(1);
                return ResultVO.fail("导入失败", result);
            }
            if (MODE_FULL.equals(normalizedMode) && result.getSuccessRows() > 0) {
                disableCamerasNotInFile(context.processedCameraCodes, result);
                result.addWarning("全量导入本期采用安全策略：文件内配置新增/更新；文件外相机标记为停用和离线；工厂、产线、工位不做物理删除。");
            }
            if (result.getFailedRows() > 0) {
                return ResultVO.fail("导入完成，但存在失败行", result);
            }
            return ResultVO.success(result, "导入成功");
        } catch (Exception e) {
            result.addError("解析 Excel 失败：" + e.getMessage());
            result.setFailedRows(result.getFailedRows() + 1);
            return ResultVO.fail("导入失败", result);
        }
    }

    @GetMapping("/export")
    public void exportConfig(HttpServletResponse response) throws Exception {
        Workbook workbook = new XSSFWorkbook();
        try {
            Sheet sheet = workbook.createSheet("整体配置");
            String[] headers = new String[] {
                    "工厂编码", "工厂名称", "工厂地址",
                    "产线编码", "产线名称",
                    "工位编码", "工位名称",
                    "相机编号", "相机名称"
            };
            writeRow(sheet.createRow(0), headers);

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT f.factory_code factoryCode, f.factory_name factoryName, f.address factoryAddress, " +
                            "l.line_code lineCode, l.line_name lineName, " +
                            "w.station_code stationCode, w.station_name stationName, " +
                            "c.camera_code cameraCode, c.camera_name cameraName " +
                            "FROM camera_status c " +
                            "LEFT JOIN dim_workstation w ON c.workstation_id = w.workstation_id " +
                            "LEFT JOIN dim_production_line l ON c.line_id = l.line_id " +
                            "LEFT JOIN dim_factory f ON l.factory_id = f.factory_id " +
                            "WHERE COALESCE(c.is_active, 1) = 1 " +
                            "ORDER BY f.factory_code, l.line_code, w.station_code, c.camera_code");
            int rowIndex = 1;
            for (Map<String, Object> row : rows) {
                writeRow(sheet.createRow(rowIndex++), new Object[] {
                        row.get("factoryCode"), row.get("factoryName"), row.get("factoryAddress"),
                        row.get("lineCode"), row.get("lineName"),
                        row.get("stationCode"), row.get("stationName"),
                        row.get("cameraCode"), row.get("cameraName")
                });
            }
            for (int i = 0; i < headers.length; i++) {
                sheet.setColumnWidth(i, 20 * 256);
            }
            prepareXlsxResponse(response, "系统配置导出.xlsx");
            workbook.write(response.getOutputStream());
        } finally {
            workbook.close();
        }
    }

    @GetMapping("/result/export")
    public void exportImportResult(HttpServletResponse response,
                                   @RequestParam(required = false) String taskId) throws Exception {
        Workbook workbook = new XSSFWorkbook();
        try {
            Sheet sheet = workbook.createSheet("导入结果明细");
            writeRow(sheet.createRow(0), new String[] {
                    "导入任务", "导入时间", "行号", "级别", "对象", "结果", "说明"
            });
            writeRow(sheet.createRow(1), new Object[] {
                    StringUtils.hasText(taskId) ? taskId : "最近一次导入",
                    LocalDateTime.now().toString(),
                    "-",
                    "INFO",
                    "配置导入",
                    "暂无失败明细",
                    "当前版本导入结果未持久化；接口先提供标准结果明细文件，后续可按导入任务ID下载每行成功/失败原因。"
            });
            for (int i = 0; i < 7; i++) {
                sheet.setColumnWidth(i, 22 * 256);
            }
            prepareXlsxResponse(response, "配置导入结果明细.xlsx");
            workbook.write(response.getOutputStream());
        } finally {
            workbook.close();
        }
    }

    private void writeExample(Row row, String[] values) {
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }

    private void writeRow(Row row, Object[] values) {
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i] == null ? "" : String.valueOf(values[i]));
        }
    }

    private void prepareXlsxResponse(HttpServletResponse response, String filename) throws Exception {
        String fileName = URLEncoder.encode(filename, "UTF-8").replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + fileName);
    }

    private String normalizeMode(String mode) {
        if (mode == null) return MODE_INCREMENTAL;
        String value = mode.trim().toUpperCase();
        if ("FULL".equals(value) || "全量导入".equals(value)) return MODE_FULL;
        return MODE_INCREMENTAL;
    }

    private ImportRow readImportRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        ImportRow data = new ImportRow();
        data.factoryCode = cell(row, 0, formatter, evaluator);
        data.factoryName = cell(row, 1, formatter, evaluator);
        data.factoryAddress = cell(row, 2, formatter, evaluator);
        data.lineCode = cell(row, 3, formatter, evaluator);
        data.lineName = cell(row, 4, formatter, evaluator);
        data.stationCode = cell(row, 5, formatter, evaluator);
        data.stationName = cell(row, 6, formatter, evaluator);
        data.cameraCode = cell(row, 7, formatter, evaluator);
        data.cameraName = cell(row, 8, formatter, evaluator);
        return data;
    }

    private void validateRow(ImportRow row, int rowNo) {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(row.factoryCode)) missing.add("工厂编码");
        if (!StringUtils.hasText(row.factoryName)) missing.add("工厂名称");
        if (!StringUtils.hasText(row.lineCode)) missing.add("产线编码");
        if (!StringUtils.hasText(row.lineName)) missing.add("产线名称");
        if (!StringUtils.hasText(row.stationCode)) missing.add("工位编码");
        if (!StringUtils.hasText(row.stationName)) missing.add("工位名称");
        if (!StringUtils.hasText(row.cameraCode)) missing.add("相机编号");
        if (!StringUtils.hasText(row.cameraName)) missing.add("相机名称");
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("必填字段缺失：" + String.join("、", missing));
        }
    }

    private DimFactory upsertFactory(ImportRow row, ImportContext context, ConfigImportResultDTO result) {
        DimFactory cached = context.factoryByCode.get(row.factoryCode);
        if (cached != null) return cached;

        DimFactory factory = factoryMapper.selectOne(new LambdaQueryWrapper<DimFactory>()
                .eq(DimFactory::getFactoryCode, row.factoryCode)
                .last("LIMIT 1"));
        Date now = new Date();
        if (factory == null) {
            factory = new DimFactory();
            factory.setCompanyId(1);
            factory.setFactoryCode(row.factoryCode);
            factory.setFactoryName(row.factoryName);
            factory.setAddress(row.factoryAddress);
            factory.setIsDeleted(0);
            factory.setCreateTime(now);
            factory.setUpdateTime(now);
            factoryMapper.insert(factory);
            result.incFactoryCreated();
        } else {
            factory.setFactoryName(row.factoryName);
            factory.setAddress(row.factoryAddress);
            factory.setUpdateTime(now);
            factoryMapper.updateById(factory);
            result.incFactoryUpdated();
        }
        context.factoryByCode.put(row.factoryCode, factory);
        return factory;
    }

    private DimProductionLine upsertLine(ImportRow row, DimFactory factory, ImportContext context, ConfigImportResultDTO result) {
        DimProductionLine cached = context.lineByCode.get(row.lineCode);
        if (cached != null) return cached;

        DimProductionLine line = lineMapper.selectOne(new LambdaQueryWrapper<DimProductionLine>()
                .eq(DimProductionLine::getLineCode, row.lineCode)
                .last("LIMIT 1"));
        Date now = new Date();
        if (line == null) {
            line = new DimProductionLine();
            line.setFactoryId(factory.getFactoryId());
            line.setLineCode(row.lineCode);
            line.setLineName(row.lineName);
            line.setIsDeleted(0);
            line.setCreateTime(now);
            line.setUpdateTime(now);
            lineMapper.insert(line);
            result.incLineCreated();
        } else {
            line.setFactoryId(factory.getFactoryId());
            line.setLineName(row.lineName);
            line.setUpdateTime(now);
            lineMapper.updateById(line);
            result.incLineUpdated();
        }
        syncDimCamerasForLine(line);
        context.lineByCode.put(row.lineCode, line);
        return line;
    }

    private DimWorkstation upsertWorkstation(ImportRow row, DimProductionLine line, ImportContext context, ConfigImportResultDTO result) {
        DimWorkstation cached = context.workstationByCode.get(row.stationCode);
        if (cached != null) return cached;

        DimWorkstation workstation = workstationMapper.selectOne(new LambdaQueryWrapper<DimWorkstation>()
                .eq(DimWorkstation::getStationCode, row.stationCode)
                .last("LIMIT 1"));
        if (workstation == null) {
            workstation = new DimWorkstation();
            workstation.setLineId(line.getLineId());
            workstation.setStationCode(row.stationCode);
            workstation.setStationName(row.stationName);
            workstationMapper.insert(workstation);
            result.incWorkstationCreated();
        } else {
            workstation.setLineId(line.getLineId());
            workstation.setStationName(row.stationName);
            workstationMapper.updateById(workstation);
            result.incWorkstationUpdated();
        }
        syncCamerasForWorkstation(workstation, line);
        context.workstationByCode.put(row.stationCode, workstation);
        return workstation;
    }

    private CameraStatus upsertCamera(ImportRow row, DimProductionLine line, DimWorkstation workstation,
                                      ImportContext context, ConfigImportResultDTO result) {
        CameraStatus cached = context.cameraByCode.get(row.cameraCode);
        if (cached != null) return cached;

        CameraStatus camera = cameraStatusMapper.selectOne(new LambdaQueryWrapper<CameraStatus>()
                .eq(CameraStatus::getCameraCode, row.cameraCode)
                .last("LIMIT 1"));
        if (camera == null) {
            camera = cameraStatusMapper.selectOne(new LambdaQueryWrapper<CameraStatus>()
                    .eq(CameraStatus::getCameraName, row.cameraName)
                    .last("LIMIT 1"));
            if (camera != null) {
                result.addWarning("相机名称 " + row.cameraName + " 已存在，本次按名称匹配并更新相机编号为 " + row.cameraCode + "。");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        if (camera == null) {
            camera = new CameraStatus();
            camera.setCameraCode(row.cameraCode);
            camera.setCameraName(row.cameraName);
            camera.setLineId(line.getLineId());
            camera.setWorkstationId(workstation.getStationId());
            camera.setStatus("OFFLINE");
            camera.setIsActive(1);
            camera.setCreateTime(now);
            camera.setUpdateTime(now);
            cameraStatusMapper.insert(camera);
            result.incCameraCreated();
        } else {
            camera.setCameraCode(row.cameraCode);
            camera.setCameraName(row.cameraName);
            camera.setLineId(line.getLineId());
            camera.setWorkstationId(workstation.getStationId());
            camera.setIsActive(1);
            camera.setUpdateTime(now);
            cameraStatusMapper.updateById(camera);
            result.incCameraUpdated();
        }
        syncDimCamera(camera, line);
        context.cameraByCode.put(row.cameraCode, camera);
        return camera;
    }

    private void syncDimCamerasForLine(DimProductionLine line) {
        if (line == null || line.getLineId() == null || line.getFactoryId() == null) return;
        jdbcTemplate.update(
                "UPDATE dim_camera SET factory_id=? WHERE line_id=? AND IFNULL(is_deleted,0)=0",
                line.getFactoryId(), line.getLineId());
        jdbcTemplate.update(
                "UPDATE cfg_camera_defect_mapping m JOIN dim_camera c ON m.camera_id=c.camera_id " +
                        "SET m.factory_id=c.factory_id, m.line_id=c.line_id, m.workstation_id=c.workstation_id, m.camera_code=c.camera_code " +
                        "WHERE c.line_id=? AND IFNULL(c.is_deleted,0)=0",
                line.getLineId());
    }

    private void syncCamerasForWorkstation(DimWorkstation workstation, DimProductionLine line) {
        if (workstation == null || workstation.getStationId() == null || line == null || line.getLineId() == null) return;
        jdbcTemplate.update(
                "UPDATE camera_status SET line_id=?, update_time=NOW() WHERE workstation_id=?",
                line.getLineId(), workstation.getStationId());
        jdbcTemplate.update(
                "UPDATE dim_camera SET factory_id=?, line_id=?, workstation_id=? WHERE workstation_id=? AND IFNULL(is_deleted,0)=0",
                line.getFactoryId(), line.getLineId(), workstation.getStationId(), workstation.getStationId());
        jdbcTemplate.update(
                "UPDATE cfg_camera_defect_mapping m JOIN dim_camera c ON m.camera_id=c.camera_id " +
                        "SET m.factory_id=c.factory_id, m.line_id=c.line_id, m.workstation_id=c.workstation_id, m.camera_code=c.camera_code " +
                        "WHERE c.workstation_id=? AND IFNULL(c.is_deleted,0)=0",
                workstation.getStationId());
    }

    private void syncDimCamera(CameraStatus camera, DimProductionLine line) {
        if (camera == null || camera.getId() == null || line == null || line.getLineId() == null) return;
        jdbcTemplate.update(
                "INSERT INTO dim_camera(camera_id, factory_id, line_id, workstation_id, camera_code, camera_name, device_id, is_enabled, is_deleted) " +
                        "VALUES (?,?,?,?,?,?,?,1,0) " +
                        "ON DUPLICATE KEY UPDATE factory_id=VALUES(factory_id), line_id=VALUES(line_id), workstation_id=VALUES(workstation_id), " +
                        "camera_code=VALUES(camera_code), camera_name=VALUES(camera_name), device_id=VALUES(device_id), is_enabled=1, is_deleted=0",
                camera.getId(), line.getFactoryId(), line.getLineId(), camera.getWorkstationId(),
                camera.getCameraCode(), camera.getCameraName(), camera.getCameraCode());
        jdbcTemplate.update(
                "UPDATE cfg_camera_defect_mapping SET factory_id=?, line_id=?, workstation_id=?, camera_code=? WHERE camera_id=?",
                line.getFactoryId(), line.getLineId(), camera.getWorkstationId(), camera.getCameraCode(), camera.getId());
    }

    private void disableCamerasNotInFile(Set<String> importedCodes, ConfigImportResultDTO result) {
        if (importedCodes == null || importedCodes.isEmpty()) return;
        List<CameraStatus> all = cameraStatusMapper.selectList(new LambdaQueryWrapper<CameraStatus>()
                .isNotNull(CameraStatus::getCameraCode));
        int disabled = 0;
        LocalDateTime now = LocalDateTime.now();
        for (CameraStatus camera : all) {
            String code = camera.getCameraCode();
            if (StringUtils.hasText(code) && !importedCodes.contains(code)) {
                camera.setIsActive(0);
                camera.setStatus("OFFLINE");
                camera.setUpdateTime(now);
                cameraStatusMapper.updateById(camera);
                disabled++;
            }
        }
        result.setCameraDisabled(disabled);
    }

    private boolean isEmptyRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null) return true;
        for (int i = 0; i <= 9; i++) {
            if (StringUtils.hasText(cell(row, i, formatter, evaluator))) return false;
        }
        return true;
    }

    private String cell(Row row, int index, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null) return "";
        Cell cell = row.getCell(index);
        if (cell == null) return "";
        return formatter.formatCellValue(cell, evaluator).trim();
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) return "RUNNING";
        String value = status.trim().toUpperCase();
        if ("运行".equals(status) || "RUNNING".equals(value) || "ONLINE".equals(value) || "1".equals(value)) return "RUNNING";
        if ("停止".equals(status) || "STOPPED".equals(value) || "STOP".equals(value) || "0".equals(value)) return "STOPPED";
        if ("故障".equals(status) || "FAULT".equals(value) || "ERROR".equals(value) || "2".equals(value)) return "FAULT";
        if ("待机".equals(status) || "STANDBY".equals(value) || "PAUSED".equals(value) || "3".equals(value)) return "STANDBY";
        if ("离线".equals(status) || "OFFLINE".equals(value) || "9".equals(value)) return "OFFLINE";
        return null;
    }

    private static class ImportRow {
        String factoryCode;
        String factoryName;
        String factoryAddress;
        String lineCode;
        String lineName;
        String stationCode;
        String stationName;
        String cameraCode;
        String cameraName;
        String cameraStatus;
    }

    private static class ImportContext {
        Map<String, DimFactory> factoryByCode = new HashMap<>();
        Map<String, DimProductionLine> lineByCode = new HashMap<>();
        Map<String, DimWorkstation> workstationByCode = new HashMap<>();
        Map<String, CameraStatus> cameraByCode = new HashMap<>();
        Set<String> processedCameraCodes = new HashSet<>();
    }
}
