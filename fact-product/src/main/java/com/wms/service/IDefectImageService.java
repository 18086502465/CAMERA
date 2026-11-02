package com.wms.service;

import com.wms.dto.DefectImageUploadResultDTO;
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
                                         LocalDateTime startTime, LocalDateTime endTime);

    List<Map<String, Object>> listCameraSummaries(Integer latestLimit, String cameraId, String cameraName,
                                                  Integer factoryId, Integer lineId, Integer stationId,
                                                  String keyword, String severity, String defectType,
                                                  LocalDateTime startTime, LocalDateTime endTime);

    Map<String, Object> listHistoryPage(Integer page, Integer size, String cameraId, String cameraName,
                                        Integer factoryId, Integer lineId, Integer stationId,
                                        String keyword, String severity, String defectType,
                                        LocalDateTime startTime, LocalDateTime endTime);

    ResponseEntity<Resource> loadImage(String relativePath, HttpServletRequest request);
}
