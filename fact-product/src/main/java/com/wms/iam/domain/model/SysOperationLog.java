package com.wms.iam.domain.model;

import com.baomidou.mybatisplus.annotation.*;
import java.util.Date;

/**
 * 操作日志表
 */
@TableName("sys_operation_log")
public class SysOperationLog {
    @TableId(type = IdType.AUTO)
    private Long logId;

    private Integer userId;
    private String userName;
    private String operationType;  // LOGIN/CREATE/UPDATE/DELETE/EXPORT/QUERY
    private String moduleName;    // 模块名称
    private String operationDesc;   // 操作描述
    private String requestMethod;   // GET/POST/PUT/DELETE
    private String requestUri;     // 请求URI
    private String requestParams;   // 请求参数
    private String ipAddress;      // IP地址
    private Date operationTime;    // 操作时间
    private Integer executionTime;  // 执行耗时(ms)
    private Integer operationResult; // 0-失败, 1-成功
    private String errorMessage;    // 错误信息

    // Getters and Setters
    public Long getLogId() { return logId; }
    public void setLogId(Long logId) { this.logId = logId; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }

    public String getModuleName() { return moduleName; }
    public void setModuleName(String moduleName) { this.moduleName = moduleName; }

    public String getOperationDesc() { return operationDesc; }
    public void setOperationDesc(String operationDesc) { this.operationDesc = operationDesc; }

    public String getRequestMethod() { return requestMethod; }
    public void setRequestMethod(String requestMethod) { this.requestMethod = requestMethod; }

    public String getRequestUri() { return requestUri; }
    public void setRequestUri(String requestUri) { this.requestUri = requestUri; }

    public String getRequestParams() { return requestParams; }
    public void setRequestParams(String requestParams) { this.requestParams = requestParams; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public Date getOperationTime() { return operationTime; }
    public void setOperationTime(Date operationTime) { this.operationTime = operationTime; }

    public Integer getExecutionTime() { return executionTime; }
    public void setExecutionTime(Integer executionTime) { this.executionTime = executionTime; }

    public Integer getOperationResult() { return operationResult; }
    public void setOperationResult(Integer operationResult) { this.operationResult = operationResult; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
