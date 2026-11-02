package com.wms.dto.log;

import java.time.LocalDate;

/**
 * 操作日志高级查询条件。
 */
public class OperationLogQueryDTO {
    private String keyword;
    private String operation;
    private String module;
    private Integer result;
    private LocalDate startDate;
    private LocalDate endDate;
    private String userName;
    private String requestMethod;
    private String requestUri;
    private String ipAddress;
    private Long minExecutionTime;
    private Long maxExecutionTime;

    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getModule() { return module; }
    public void setModule(String module) { this.module = module; }
    public Integer getResult() { return result; }
    public void setResult(Integer result) { this.result = result; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getRequestMethod() { return requestMethod; }
    public void setRequestMethod(String requestMethod) { this.requestMethod = requestMethod; }
    public String getRequestUri() { return requestUri; }
    public void setRequestUri(String requestUri) { this.requestUri = requestUri; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public Long getMinExecutionTime() { return minExecutionTime; }
    public void setMinExecutionTime(Long minExecutionTime) { this.minExecutionTime = minExecutionTime; }
    public Long getMaxExecutionTime() { return maxExecutionTime; }
    public void setMaxExecutionTime(Long maxExecutionTime) { this.maxExecutionTime = maxExecutionTime; }
}
