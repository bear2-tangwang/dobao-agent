package com.dobao.dobaobackend.interview.audio;

import com.dobao.dobaobackend.config.InterviewProperties;
import com.dobao.dobaobackend.interview.asr.dto.AsrUploadPolicyResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 百炼官方"临时文件上传"客户端：取上传凭证 → POST 音频到 OSS → 返回 {@code oss://} 地址。
 *
 * <p>实现细节直接沿用了步骤 0 探针脚本踩出来的三条经验（都是有代价的坑）：
 * <ol>
 *   <li><b>file 部件必须用纯 ASCII 的 {@code filename="file"}</b>。OSS 上传表单不认
 *       RFC 5987 的 {@code filename*=UTF-8''...}，会退化成"整个 body 当成一个文本字段"，
 *       最终报 {@code FieldItemTooLong}。真实文件名放在 {@code key} 表单字段里传。</li>
 *   <li>文本字段先写、file 部件最后写（与表单顺序一致）。</li>
 *   <li>凭证接口有 100 QPS 限制且官方声明"请勿用于生产环境"，因此 prod 必须切到
 *       {@link MinioPublicUrlProvider}。</li>
 * </ol>
 */
@Slf4j
@Component
public class DashScopeFileUploader {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int API_READ_TIMEOUT_MS = 60_000;
    /** 上传几 MB 音频的正常耗时，给足但别无限等 */
    private static final int UPLOAD_READ_TIMEOUT_MS = 10 * 60 * 1000;

    private final InterviewProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient apiClient;
    private final RestClient uploadClient;

    public DashScopeFileUploader(InterviewProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.apiClient = buildClient(API_READ_TIMEOUT_MS);
        this.uploadClient = buildClient(UPLOAD_READ_TIMEOUT_MS);
    }

    private static RestClient buildClient(int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(readTimeoutMs);
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 上传音频到百炼临时存储。
     *
     * @param audio    音频字节
     * @param fileName 对象名（作为 OSS key 的文件名部分）
     * @return {@code oss://...} 地址，有效期 48 小时
     */
    public String upload(byte[] audio, String fileName) {
        InterviewProperties.Asr asr = properties.getAsr();
        if (!StringUtils.hasText(asr.getApiKey())) {
            throw new IllegalStateException("interview.asr.api-key 为空，无法调用百炼接口");
        }

        AsrUploadPolicyResponse policy = getUploadPolicy(asr);
        if (!policy.usable()) {
            throw new IllegalStateException("百炼上传凭证字段不完整: " + policy);
        }

        String key = policy.data().uploadDir() + "/" + fileName;
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("OSSAccessKeyId", policy.data().ossAccessKeyId());
        parts.add("Signature", policy.data().signature());
        parts.add("policy", policy.data().policy());
        addIfPresent(parts, "x-oss-object-acl", policy.data().xOssObjectAcl());
        addIfPresent(parts, "x-oss-forbid-overwrite", policy.data().xOssForbidOverwrite());
        parts.add("key", key);
        parts.add("success_action_status", "200");
        // file 部件放最后，且文件名必须是纯 ASCII 的 "file"（见类注释第 1 条）
        parts.add("file", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return "file";
            }
        });

        uploadClient.post()
                .uri(policy.data().uploadHost())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .toBodilessEntity();

        String ossUrl = "oss://" + key;
        log.info("百炼临时上传成功: key={}, size={}B", key, audio.length);
        return ossUrl;
    }

    private AsrUploadPolicyResponse getUploadPolicy(InterviewProperties.Asr asr) {
        String url = asr.getBaseUrl() + "/uploads?action=getPolicy&model=" + asr.getModel();
        String body = apiClient.get()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + asr.getApiKey())
                .retrieve()
                .body(String.class);
        try {
            return objectMapper.readValue(body, AsrUploadPolicyResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("解析百炼上传凭证失败: " + body, e);
        }
    }

    private static void addIfPresent(MultiValueMap<String, Object> parts, String name, String value) {
        if (StringUtils.hasText(value)) {
            parts.add(name, value);
        }
    }
}
