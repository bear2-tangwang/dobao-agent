package com.dobao.dobaobackend.interview.audio;

import com.dobao.dobaobackend.service.MinioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * dev 实现：走百炼官方"临时文件上传"拿 {@code oss://} 临时地址。
 *
 * <p>流程（与步骤 0 探针脚本一致）：取上传凭证 → POST 音频到 {@code upload_host} → 得到
 * {@code oss://dashscope-instant/...}（有效期 48 小时）。
 *
 * <p>为什么需要"从 MinIO 下载再上传"：dev 环境的 MinIO 在 {@code 127.0.0.1:9000}，
 * 百炼在公网，**拉不到本地地址**；所以只能把音频搬到百炼自己的临时存储。
 * 这也是"无论哪套实现，音频都必须先存 MinIO"的原因 —— 重试时不用让用户重新上传。
 *
 * <p>切换开关：{@code interview.asr.temp-upload-enabled=true}（缺省即 true）。
 *
 * @see MinioPublicUrlProvider prod 实现
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "interview.asr.temp-upload-enabled", havingValue = "true", matchIfMissing = true)
public class DashScopeTempUrlProvider implements AudioUrlProvider {

    private final MinioService minioService;
    private final DashScopeFileUploader fileUploader;

    @Override
    public String provide(String objectName) {
        try (InputStream in = minioService.downloadFile(objectName)) {
            byte[] audio = in.readAllBytes();
            String ossUrl = fileUploader.upload(audio, objectName);
            log.info("音频已上传至百炼临时存储: objectName={}, size={}B, url={}", objectName, audio.length, ossUrl);
            return ossUrl;
        } catch (Exception e) {
            throw new IllegalStateException("音频上传百炼临时存储失败: objectName=" + objectName, e);
        }
    }
}
