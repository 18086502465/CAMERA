package com.wms.system.domain.model;

/**
 * 缺陷类型配置行（DDD P3 批次②）。
 *
 * <p>对应 {@code dim_defect_type} 表的 12 个可写列。
 */
public class DefectTypeConfigRow {

    private Long productId;
    private String defectCode;
    private String defectName;
    private String defectCategory;
    private Long defaultPositionId;
    private String defaultPositionCode;
    private String defaultPositionName;
    private String defaultSeverity;
    private Integer isNg;
    private String description;
    private Integer sortOrder;
    private Integer isEnabled;

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public String getDefectCode() { return defectCode; }
    public void setDefectCode(String defectCode) { this.defectCode = defectCode; }

    public String getDefectName() { return defectName; }
    public void setDefectName(String defectName) { this.defectName = defectName; }

    public String getDefectCategory() { return defectCategory; }
    public void setDefectCategory(String defectCategory) { this.defectCategory = defectCategory; }

    public Long getDefaultPositionId() { return defaultPositionId; }
    public void setDefaultPositionId(Long defaultPositionId) { this.defaultPositionId = defaultPositionId; }

    public String getDefaultPositionCode() { return defaultPositionCode; }
    public void setDefaultPositionCode(String defaultPositionCode) { this.defaultPositionCode = defaultPositionCode; }

    public String getDefaultPositionName() { return defaultPositionName; }
    public void setDefaultPositionName(String defaultPositionName) { this.defaultPositionName = defaultPositionName; }

    public String getDefaultSeverity() { return defaultSeverity; }
    public void setDefaultSeverity(String defaultSeverity) { this.defaultSeverity = defaultSeverity; }

    public Integer getIsNg() { return isNg; }
    public void setIsNg(Integer isNg) { this.isNg = isNg; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public Integer getIsEnabled() { return isEnabled; }
    public void setIsEnabled(Integer isEnabled) { this.isEnabled = isEnabled; }
}
