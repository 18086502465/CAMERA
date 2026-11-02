package com.wms.utils;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.NotOLE2FileException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Excel工具类（完美支持：标准xls/xlsx + UTF-16 LE编码制表符分隔文件 + 普通文本分隔文件）
 * 修复所有BUG+兼容英文格式日期 Tue Dec 30 16:31:39 CST 2025
 */
public class ExcelUtils {

    // 支持的日期格式【最全兼容：数字格式 + 英文星期月份格式】
    private static final List<DateTimeFormatter> DATE_FORMATTERS = Arrays.asList(
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            // 核心新增：兼容 Tue Dec 30 16:31:39 CST 2025 格式
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US),
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss yyyy", Locale.US),
            DateTimeFormatter.ofPattern("MMM dd HH:mm:ss yyyy", Locale.US)
    );

    /**
     * 读取Excel文件（支持.xls, .xlsx和制表符分隔的文本文件）【原方法不变，直接调用】
     * @param file Excel文件
     * @param encoding 文件编码 如：UTF-16LE、UTF-8、GBK
     * @param separator 分隔符（制表符\t或逗号,等）
     * @return 数据列表（Map形式，key为列名）
     */
    public static List<Map<String, Object>> readExcel(MultipartFile file, String encoding, String separator) throws IOException {
        List<Map<String, Object>> resultList = new ArrayList<>();
        String originalFilename = file.getOriginalFilename();

        // 校验文件为空
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传的文件为空，请选择有效文件");
        }
        // 校验文件名称
        if (originalFilename == null || originalFilename.trim().isEmpty()) {
            throw new IllegalArgumentException("文件名称为空");
        }

        try {
            // 1. 优先解析【标准Excel文件 .xls .xlsx】
            if (originalFilename.endsWith(".xls") || originalFilename.endsWith(".xlsx")) {
                parseStandardExcel(file, resultList);
            } else {
                // 2. 解析【文本分隔文件】
                parseTextFile(file, encoding, separator, resultList);
            }
        } catch (NotOLE2FileException e) {
            // 兼容：后缀是xls/xlsx，但实际是UTF16LE文本文件的场景
            System.out.println("检测到文件为【伪Excel文本文件】，自动切换文本解析模式 >> 编码："+encoding+", 分隔符："+separator);
            resultList.clear();
            parseTextFile(file, encoding, separator, resultList);
        } catch (Exception e) {
            throw new IOException("文件解析失败：" + e.getMessage(), e);
        }
        return resultList;
    }

    // 私有方法：解析标准Excel文件 .xls .xlsx
    private static void parseStandardExcel(MultipartFile file, List<Map<String, Object>> resultList) throws IOException {
        String originalFilename = file.getOriginalFilename();
        try (InputStream inputStream = file.getInputStream()) {
            Workbook workbook;
            if (originalFilename.endsWith(".xlsx")) {
                workbook = new XSSFWorkbook(inputStream);
            } else {
                workbook = new HSSFWorkbook(inputStream);
            }

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                return;
            }
            Row headerRow = sheet.getRow(0);
            if (headerRow == null || headerRow.getLastCellNum() == 0) {
                return;
            }
            List<String> headers = getExcelHeaders(headerRow);
            readExcelData(sheet, headers, resultList);
            workbook.close();
        }
    }

    // 私有方法：解析文本分隔文件（完美支持UTF-16LE，解决大文件OOM）
    private static void parseTextFile(MultipartFile file, String encoding, String separator, List<Map<String, Object>> resultList) throws IOException {
        try (InputStreamReader isr = new InputStreamReader(file.getInputStream(), Charset.forName(encoding));
             BufferedReader br = new BufferedReader(isr)) {
            String line;
            String[] headers = null;
            int lineNum = 0;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                lineNum++;
                if (lineNum == 1) {
                    headers = line.split(separator, -1); // 保留空列，防止数据错位
                    continue;
                }
                String[] fields = line.split(separator, -1);
                Map<String, Object> rowMap = new HashMap<>(16);
                if (headers != null) {
                    for (int j = 0; j < headers.length; j++) {
                        String header = headers[j].trim();
                        String value = j < fields.length ? fields[j].trim() : "";
                        rowMap.put(header, value);
                    }
                }
                resultList.add(rowMap);
            }
        }
    }

    // 私有方法：获取Excel表头
    private static List<String> getExcelHeaders(Row headerRow) {
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            Cell cell = headerRow.getCell(i);
            headers.add(getCellValue(cell));
        }
        return headers;
    }

    // 私有方法：读取Excel数据行
    private static void readExcelData(Sheet sheet, List<String> headers, List<Map<String, Object>> resultList) {
        for (int rowNum = 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
            Row dataRow = sheet.getRow(rowNum);
            if (dataRow == null) {
                continue;
            }
            Map<String, Object> rowMap = new HashMap<>(16);
            for (int cellNum = 0; cellNum < headers.size(); cellNum++) {
                Cell cell = dataRow.getCell(cellNum);
                String header = headers.get(cellNum);
                rowMap.put(header, getCellValue(cell));
            }
            resultList.add(rowMap);
        }
    }

    /**
     * 获取单元格的值【增强版，处理所有格式，避免科学计数法】
     */
    private static String getCellValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        String cellValue = "";
        switch (cell.getCellType()) {
            case STRING:
                cellValue = cell.getStringCellValue().trim();
                break;
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    cellValue = cell.getDateCellValue().toString();
                } else {
                    cell.setCellType(CellType.STRING);
                    cellValue = cell.getStringCellValue().trim();
                    // 去除小数点后多余的0
                    if (cellValue.contains(".") && cellValue.endsWith("0")) {
                        cellValue = cellValue.replaceAll("0*$", "").replaceAll("\\.$", "");
                    }
                }
                break;
            case BOOLEAN:
                cellValue = String.valueOf(cell.getBooleanCellValue());
                break;
            case FORMULA:
                try {
                    cellValue = cell.getStringCellValue().trim();
                } catch (Exception e) {
                    cell.setCellType(CellType.NUMERIC);
                    cellValue = String.valueOf(cell.getNumericCellValue()).trim();
                }
                break;
            default:
                cellValue = "";
        }
        return cellValue;
    }

    /**
     * 解析日期时间字符串【完美修复版】
     * 兼容：数字格式 + 英文格式(Tue Dec 30 16:31:39 CST 2025) + 纯日期
     */
    public static LocalDateTime parseDateTime(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }

        dateStr = dateStr.trim();
        // 尝试所有支持的日期格式
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDateTime.parse(dateStr, formatter);
            } catch (DateTimeParseException e) {
                continue;
            }
        }

        // 终极兜底：兼容纯日期拼接默认时间
        try {
            return LocalDateTime.parse(dateStr + " 00:00:00", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e1) {
            try {
                return LocalDateTime.parse(dateStr + " 00:00:00", DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /**
     * 解析整数值【增强版，兼容小数点/空值/异常值】
     */
    public static Integer parseInteger(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            if (value instanceof Number) {
                return ((Number) value).intValue();
            } else {
                String strValue = String.valueOf(value).trim();
                if (strValue.isEmpty() || "null".equalsIgnoreCase(strValue)) {
                    return 0;
                }
                // 兼容带小数点的数字 如 100.00 → 100
                if (strValue.contains(".")) {
                    return Double.valueOf(strValue).intValue();
                }
                return Integer.parseInt(strValue);
            }
        } catch (Exception e) {
            return 0;
        }
    }
}