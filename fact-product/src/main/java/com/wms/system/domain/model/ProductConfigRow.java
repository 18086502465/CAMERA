package com.wms.system.domain.model;

/**
 * 产品配置行（DDD P3 批次②）。
 *
 * <p>对应 {@code dim_product} 表的 7 个可写列。
 */
public class ProductConfigRow {

    private String productCode;
    private String productName;
    private String productSpec;
    private String brandName;
    private String description;
    private Integer sortOrder;
    private Integer isEnabled;

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getProductSpec() { return productSpec; }
    public void setProductSpec(String productSpec) { this.productSpec = productSpec; }

    public String getBrandName() { return brandName; }
    public void setBrandName(String brandName) { this.brandName = brandName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public Integer getIsEnabled() { return isEnabled; }
    public void setIsEnabled(Integer isEnabled) { this.isEnabled = isEnabled; }
}
