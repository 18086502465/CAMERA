package com.wms.device.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wms.device.domain.model.CameraImage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

/**
 * 鐩告満鍥剧墖琛?Mapper
 */
public interface CameraImageMapper extends BaseMapper<CameraImage> {

    /**
     * 鏌ヨ鎸囧畾鐩告満鏈€杩戜笂浼犵殑鍥剧墖鍒楄〃
     */
    @Select("SELECT * FROM camera_images " +
            "WHERE camera_name = #{cameraName} " +
            "ORDER BY capture_time DESC, id DESC LIMIT #{limit}")
    List<CameraImage> findRecentByCamera(
            @Param("cameraName") String cameraName,
            @Param("limit") int limit);

    /**
     * 鏌ヨ杩囨湡鍥剧墖鍒楄〃
     */
    @Select("SELECT * FROM camera_images " +
            "WHERE expire_time < NOW() " +
            "ORDER BY capture_time DESC")
    List<CameraImage> selectExpiredImages();

    /**
     * 鍒犻櫎杩囨湡鍥剧墖璁板綍
     */
    @Delete("DELETE FROM camera_images " +
            "WHERE expire_time < NOW()")
    int deleteExpiredImages();
}

