package com.attendenceSystem.module.faceid.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.attendenceSystem.module.faceid.exception.FaceAiException;
import com.attendenceSystem.module.faceid.exception.FaceAiUnavailableException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Client gọi sang service face-ai (FastAPI, xem face-ai/app/routers/internal_face.py). */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaceAiClient {

    // RestTemplate được cấu hình timeout riêng, xem FaceAiRestTemplateConfig — tránh treo vô
    // thời hạn nếu face-ai không phản hồi.
    private final RestTemplate restTemplate;

    @Value("${face-ai.base-url:http://127.0.0.1:8002}")
    private String baseUrl;

    @Value("${face-ai.api-key}")
    private String apiKey;

    private final AtomicLong lastUnavailableLogTime = new AtomicLong(0);
    private static final long LOG_INTERVAL_MS = 10000; // Giới hạn tần suất log lỗi lặp lại 10s một lần

    public static class EmbedResult {
        public int index;
        public boolean success;
        public String embedding;
        public String message;
    }

    public static class EmbedBatchResponse {
        public List<EmbedResult> results;
    }

    public static class PoseResult {
        public boolean ok;
        public String message;
        public int face_count;
        public String pose;
        public Double yaw;
        public Double pitch;
        public List<Integer> bbox;
        public Double face_ratio;
    }

    public EmbedBatchResponse embedBatch(List<String> imagesBase64) {
        return executePost("/api/internal/face/embed-batch", "images_base64", imagesBase64, EmbedBatchResponse.class);
    }

    public EmbedResult embedSingle(String imageBase64) {
        return executePost("/api/internal/face/embed", "image_base64", imageBase64, EmbedResult.class);
    }

    public PoseResult checkPose(String imageBase64) {
        return executePost("/api/internal/face/pose-check", "image_base64", imageBase64, PoseResult.class);
    }

    private <T> T executePost(String path, String bodyKey, Object bodyValue, Class<T> responseType) {
        String url = baseUrl + path;
        try {
            HttpEntity<Map<String, Object>> entity = requestEntity(bodyKey, bodyValue);
            ResponseEntity<T> response = restTemplate.postForEntity(url, entity, responseType);
            return response.getBody();
        } catch (ResourceAccessException ex) {
            String msg = "Không thể kết nối đến Face AI (" + url + "): " + ex.getMessage();
            logUnavailable(msg);
            throw new FaceAiUnavailableException(msg, ex);
        } catch (HttpStatusCodeException ex) {
            String msg = "Dịch vụ Face AI trả về HTTP " + ex.getStatusCode().value() + " (" + url + "): " + ex.getResponseBodyAsString();
            if (ex.getStatusCode().is5xxServerError()) {
                logUnavailable(msg);
                throw new FaceAiUnavailableException(msg, ex);
            }
            log.warn(msg);
            throw new FaceAiException(msg, ex);
        } catch (RestClientException ex) {
            String msg = "Lỗi giao tiếp với Face AI (" + url + "): " + ex.getMessage();
            log.warn(msg);
            throw new FaceAiException(msg, ex);
        }
    }

    private void logUnavailable(String message) {
        long now = System.currentTimeMillis();
        long last = lastUnavailableLogTime.get();
        if (now - last > LOG_INTERVAL_MS && lastUnavailableLogTime.compareAndSet(last, now)) {
            log.warn("Face AI không khả dụng (giới hạn log lặp lại trong 10s): {}", message);
        } else {
            log.debug("Face AI không khả dụng: {}", message);
        }
    }

    private HttpEntity<Map<String, Object>> requestEntity(String bodyKey, Object bodyValue) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", apiKey);

        Map<String, Object> body = new HashMap<>();
        body.put(bodyKey, bodyValue);

        return new HttpEntity<>(body, headers);
    }
}
