package com.wms.defect.app;

import com.wms.defect.api.dto.DefectImageUploadResultDTO;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface IDefectImageService {
    DefectImageUploadResultDTO upload(MultipartFile[] files, String metadataJson, String authorization, String deviceId);

    List<Map<String, Object>> listRecent(Integer limit, String cameraId, String cameraName,
                                         Integer factoryId, Integer lineId, Integer stationId,
                                         String keyword, String severity, String defectType,
                                         String resolution, LocalDateTime startTime, LocalDateTime endTime);

    List<Map<String, Object>> listCameraSummaries(Integer latestLimit, String cameraId, String cameraName,
                                                  Integer factoryId, Integer lineId, Integer stationId,
                                                  String keyword, String severity, String defectType,
                                                  String resolution, LocalDateTime startTime, LocalDateTime endTime);

    Map<String, Object> listHistoryPage(Integer page, Integer size, String cameraId, String cameraName,
                                        Integer factoryId, Integer lineId, Integer stationId,
                                        String keyword, String severity, String defectType,
                                        String resolution, LocalDateTime startTime, LocalDateTime endTime);

    ResponseEntity<Resource> loadImage(String relativePath, HttpServletRequest request);

    /**
     * 图像记录页的筛选下拉选项，取自实际上报数据。
     *
     * @return {@code defectTypes}（类型名+张数）与 {@code resolutions}（分辨率+张数）
     */
    Map<String, Object> listFilterOptions();
}
