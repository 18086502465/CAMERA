
package com.wms.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * Excel导入结果VO
 */
@Data
public class ExcelImportResultVO implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 成功条数
     */
    private int successCount;

    /**
     * 失败条数
     */
    private int failCount;

    /**
     * 消息说明
     */
    private String message;
}

