package com.wms.system.api.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 系统配置整体导入结果。
 */
public class ConfigImportResultDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String mode;
    private int totalRows;
    private int successRows;
    private int failedRows;
    private int skippedRows;

    private int factoryCreated;
    private int factoryUpdated;
    private int lineCreated;
    private int lineUpdated;
    private int workstationCreated;
    private int workstationUpdated;
    private int cameraCreated;
    private int cameraUpdated;
    private int cameraDisabled;

    private List<String> warnings = new ArrayList<>();
    private List<String> errors = new ArrayList<>();

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }

    public int getSuccessRows() { return successRows; }
    public void setSuccessRows(int successRows) { this.successRows = successRows; }

    public int getFailedRows() { return failedRows; }
    public void setFailedRows(int failedRows) { this.failedRows = failedRows; }

    public int getSkippedRows() { return skippedRows; }
    public void setSkippedRows(int skippedRows) { this.skippedRows = skippedRows; }

    public int getFactoryCreated() { return factoryCreated; }
    public void setFactoryCreated(int factoryCreated) { this.factoryCreated = factoryCreated; }
    public void incFactoryCreated() { this.factoryCreated++; }

    public int getFactoryUpdated() { return factoryUpdated; }
    public void setFactoryUpdated(int factoryUpdated) { this.factoryUpdated = factoryUpdated; }
    public void incFactoryUpdated() { this.factoryUpdated++; }

    public int getLineCreated() { return lineCreated; }
    public void setLineCreated(int lineCreated) { this.lineCreated = lineCreated; }
    public void incLineCreated() { this.lineCreated++; }

    public int getLineUpdated() { return lineUpdated; }
    public void setLineUpdated(int lineUpdated) { this.lineUpdated = lineUpdated; }
    public void incLineUpdated() { this.lineUpdated++; }

    public int getWorkstationCreated() { return workstationCreated; }
    public void setWorkstationCreated(int workstationCreated) { this.workstationCreated = workstationCreated; }
    public void incWorkstationCreated() { this.workstationCreated++; }

    public int getWorkstationUpdated() { return workstationUpdated; }
    public void setWorkstationUpdated(int workstationUpdated) { this.workstationUpdated = workstationUpdated; }
    public void incWorkstationUpdated() { this.workstationUpdated++; }

    public int getCameraCreated() { return cameraCreated; }
    public void setCameraCreated(int cameraCreated) { this.cameraCreated = cameraCreated; }
    public void incCameraCreated() { this.cameraCreated++; }

    public int getCameraUpdated() { return cameraUpdated; }
    public void setCameraUpdated(int cameraUpdated) { this.cameraUpdated = cameraUpdated; }
    public void incCameraUpdated() { this.cameraUpdated++; }

    public int getCameraDisabled() { return cameraDisabled; }
    public void setCameraDisabled(int cameraDisabled) { this.cameraDisabled = cameraDisabled; }

    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }

    public List<String> getErrors() { return errors; }
    public void setErrors(List<String> errors) { this.errors = errors; }

    public void addWarning(String warning) { this.warnings.add(warning); }
    public void addError(String error) { this.errors.add(error); }
}
