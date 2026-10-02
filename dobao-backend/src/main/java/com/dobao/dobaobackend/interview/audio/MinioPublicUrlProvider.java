package com.dobao.dobaobackend.interview.audio;

import com.dobao.dobaobackend.config.InterviewProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * prod 实现：直接拼 MinIO 公网地址交给百炼。
 *
 * <p>链路最短、最稳，不受百炼临时存储的 48 小时时效与 100 QPS 限制。
 *
 * <p>前置条件（运维项）：MinIO 必须有对外可达的域名，即
 * {@code minio.public-base-url}（可用环境变量 {@code MINIO_PUBLIC_BASE_URL} 覆盖）。
 * 若该配置为空，说明还没上线准备，此时应保持
 * {@code interview.asr.temp-upload-enabled=true}，而不是让本类报错。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "interview.asr.temp-upload-enabled", havingValue = "false")
public class MinioPublicUrlProvider implements AudioUrlProvider {

    private final InterviewProperties properties;

    @Value("${minio.public-base-url:}")
    private String publicBaseUrl;

    @Value("${minio.bucketName}")
    private String bucketName;

    @Override
    public String provide(String objectName) {
        if (!StringUtils.hasText(publicBaseUrl)) {
            throw new IllegalStateException(
                    "minio.public-base-url 未配置，无法生成百炼可拉取的音频地址。"
                            + "若在开发环境，请改用 interview.asr.temp-upload-enabled=true");
        }
        String base = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        String url = base + "/" + bucketName + "/" + objectName;
        log.info("使用 MinIO 公网地址: objectName={}, url={}", objectName, url);
        return url;
    }
}
