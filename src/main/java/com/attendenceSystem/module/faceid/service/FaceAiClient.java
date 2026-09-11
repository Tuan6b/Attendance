package com.attendenceSystem.module.faceid.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import lombok.RequiredArgsConstructor;

/** Client gọi sang service face-ai (FastAPI, xem face-ai/app/routers/internal_face.py). */
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
        HttpEntity<Map<String, Object>> entity = requestEntity("images_base64", imagesBase64);
        ResponseEntity<EmbedBatchResponse> response = restTemplate.postForEntity(
                baseUrl + "/api/internal/face/embed-batch",
                entity,
                EmbedBatchResponse.class
        );
        return response.getBody();
    }

    public EmbedResult embedSingle(String imageBase64) {
        HttpEntity<Map<String, Object>> entity = requestEntity("image_base64", imageBase64);
        ResponseEntity<EmbedResult> response = restTemplate.postForEntity(
                baseUrl + "/api/internal/face/embed",
                entity,
                EmbedResult.class
        );
        return response.getBody();
    }

    public PoseResult checkPose(String imageBase64) {
        HttpEntity<Map<String, Object>> entity = requestEntity("image_base64", imageBase64);
        ResponseEntity<PoseResult> response = restTemplate.postForEntity(
                baseUrl + "/api/internal/face/pose-check",
                entity,
                PoseResult.class
        );
        return response.getBody();
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
