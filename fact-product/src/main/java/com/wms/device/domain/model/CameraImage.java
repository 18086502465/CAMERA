package com.wms.device.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;

@TableName("camera_images")
public class CameraImage implements Serializable {

    private static final long serialVersionUID = 1L;

    // 主键映射：表 camera_images 的实际主键列是 `id`（init_schema.sql 与
    // final_schema_p0_20260608.sql 均为 `id BIGINT AUTO_INCREMENT PRIMARY KEY`），
    // 而表中并无 image_id 列。
    //
    // 【重要】@TableId **必须显式写 value**：
    // MyBatis-Plus 在 @TableId 未指定 value 时，会按**字段名驼峰转下划线**推导列名，
    // 而不是取「id」—— 字段名 imageId 会推导出 `image_id`，与表实际主键 `id` 不符。
    // 后果（2026-10-27 实测复现）：
    //   MyBatis-Plus 生成的 SELECT 带 image_id → 查 camera_images 报
    //   "Unknown column 'image_id' in 'field list'"；
    //   DataCleanupTask 的 30 天图片清理（正是按这张表走的）整体抛异常，
    //   由于 @Transactional 回滚，生产数据归档与操作日志清理也一并回滚。
    @TableId(value = "id", type = IdType.AUTO)
    private Long imageId;

    @TableField("camera_name")
    private String cameraName;

    @TableField("image_path")
    private String imagePath;

    @TableField("image_url")
    private String imageUrl;

    @TableField("capture_time")
    private LocalDateTime captureTime;

    @TableField("expire_time")
    private LocalDateTime expireTime;

    @TableField("file_size")
    private Long fileSize;

    private Integer width;

    private Integer height;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    // ===== Getters & Setters =====
    public Long getImageId() { return imageId; }
    public void setImageId(Long imageId) { this.imageId = imageId; }

    // ===== 兼容旧代码 =====
    public Long getId() { return imageId; }
    public void setId(Long id) { this.imageId = id; }

    public String getCameraName() { return cameraName; }
    public void setCameraName(String cameraName) { this.cameraName = cameraName; }

    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public LocalDateTime getCaptureTime() { return captureTime; }
    public void setCaptureTime(LocalDateTime captureTime) { this.captureTime = captureTime; }

    public LocalDateTime getExpireTime() { return expireTime; }
    public void setExpireTime(LocalDateTime expireTime) { this.expireTime = expireTime; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }

    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
