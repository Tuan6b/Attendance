package com.attendenceSystem.module.faceid.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * {@link RestTemplate} riêng cho {@link com.attendenceSystem.module.faceid.service.FaceAiClient}
 * với timeout rõ ràng. Trước đây {@code FaceAiClient} tự {@code new RestTemplate()} không có
 * timeout — nếu face-ai service treo/không phản hồi, request tới nó (và luồng kiosk đang chờ)
 * sẽ treo vô thời hạn thay vì trả lỗi.
 */
@Configuration
public class FaceAiRestTemplateConfig {

    @Bean
    public RestTemplate faceAiRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return new RestTemplate(factory);
    }
}
