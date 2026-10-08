package com.dobao.dobaobackend.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class ImageGenerationService {

    // endpoint 与模型名外置到 application.yml，便于切换主机/模型
    @Value("${qwen.image.api-url}")
    private String qwenApiUrl;

    @Value("${qwen.image.model}")
    private String qwenImageModel;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    /**
     * 生成图像
     *
     * @param prompt 提示词
     * @return 图像URL
     */
    public String generateImage(String prompt) {
        return generateWithQwen(prompt);
    }

    /**
     * 使用通义千问生成图像（multimodal-generation 同步接口）
     */
    private String generateWithQwen(String prompt) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", qwenImageModel);

            // 该接口的输入走 messages 格式，而不是 input.prompt
            Map<String, Object> textContent = new HashMap<>();
            textContent.put("text", prompt);

            Map<String, Object> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", new Object[]{textContent});

            Map<String, Object> input = new HashMap<>();
            input.put("messages", new Object[]{userMessage});
            requestBody.put("input", input);

            Map<String, Object> parameters = new HashMap<>();
            parameters.put("negative_prompt", "低分辨率，低画质，肢体畸形，手指畸形，画面过饱和，蜡像感，人脸无细节，过度光滑，画面具有AI感。构图混乱。文字模糊，扭曲。");
            parameters.put("prompt_extend", true);
            parameters.put("watermark", false);
            parameters.put("size", "1664*928");
            requestBody.put("parameters", parameters);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(qwenApiUrl))
                    .timeout(Duration.ofMinutes(5));

            requestBuilder.header("Content-Type", "application/json");
            requestBuilder.header("Authorization", "Bearer " + apiKey);

            String bodyStr = JSON.toJSONString(requestBody);
            requestBuilder.POST(HttpRequest.BodyPublishers.ofString(bodyStr));

            HttpRequest request = requestBuilder.build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JSONObject jsonResponse = JSON.parseObject(response.body());
                log.info("Qwen图像生成响应: {}", jsonResponse);

                JSONObject output = jsonResponse.getJSONObject("output");
                if (output != null && output.containsKey("choices")) {
                    JSONArray choices = output.getJSONArray("choices");
                    if (choices != null && choices.size() > 0) {
                        JSONObject choice = choices.getJSONObject(0);
                        JSONObject message = choice.getJSONObject("message");
                        if (message != null && message.containsKey("content")) {
                            JSONArray contents = message.getJSONArray("content");
                            if (contents != null && contents.size() > 0) {
                                JSONObject content = contents.getJSONObject(0);
                                if (content.containsKey("image")) {
                                    String imageUrl = content.getString("image");
                                    log.info("Qwen图像生成成功，URL: {}", imageUrl);
                                    return imageUrl;
                                }
                            }
                        }
                    }
                }
            } else {
                log.error("Qwen HTTP请求失败，状态码: {}, 响应: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("Qwen图像生成失败", e);
        }
        return null;
    }

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
}
