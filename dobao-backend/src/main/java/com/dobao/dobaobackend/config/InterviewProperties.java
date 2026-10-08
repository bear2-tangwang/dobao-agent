package com.dobao.dobaobackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试总结功能配置（{@code interview.*}）。
 *
 * <p>项目没有 {@code @ConfigurationPropertiesScan}，故用 {@link Component} 显式注册；pom 里没有
 * validation starter，加 {@code @Validated} 会导致启动失败，必填校验放到业务首次使用时做。
 * {@code interview.asr.poll-interval-ms} 与 {@code interview.stream.heartbeat-ms} 由
 * {@code @Scheduled} 占位符直接读取，此处刻意不定义字段，避免出现第二份默认值。
 */
@Data
@Component
@ConfigurationProperties(prefix = "interview")
public class InterviewProperties {

    /**
     * 百炼录音文件识别相关配置
     */
    private Asr asr = new Asr();

    /**
     * 报告归纳（参考回答 + 面试总结）相关配置
     */
    private Report report = new Report();

    @Data
    public static class Asr {

        /**
         * 百炼 API 地址，必须带 {@code /api/v1}：缺了它 {@code /uploads}、
         * {@code /services/audio/asr/transcription}、{@code /tasks/{id}} 会全部 404。
         */
        private String baseUrl;

        /**
         * 百炼 API Key。默认回退到 {@code spring.ai.openai.api-key}（同源账号）。
         */
        private String apiKey;

        /**
         * 转写模型名
         */
        private String model;

        /**
         * 轮询超时上限（毫秒），超过即置 FAILED，避免任务永久挂起
         */
        private long pollTimeoutMs = 1800000L;

        /**
         * 是否走百炼临时文件上传：true=dev（本地无需公网域名），false=prod（走 MinIO 公网地址）。
         * 这是 dev/prod 的唯一切换开关。
         */
        private boolean tempUploadEnabled = true;

        /**
         * 单个音频大小上限（字节），与 spring.servlet.multipart.max-file-size 对齐
         */
        private long maxAudioBytes = 83886080L;

        /**
         * 单个音频时长上限（毫秒）。模型官方支持 12 小时，1.5 小时是产品侧约束。
         */
        private long maxAudioDurationMs = 5400000L;
    }

    @Data
    public static class Report {

        /**
         * 报告归纳使用的模型名（留空表示沿用全局
         * {@code spring.ai.openai.chat.options.model}）。
         *
         * <p>信息抽取任务不需要对话/PPT 那种表达能力，换一个非推理模型可以省掉大量"思考" token。
         */
        private String model;

        /**
         * 报告归纳调用的随机种子，配合低温保证"同一输入同一输出"。类似与llm 的 temperature。
         */
        private Integer seed = 42;

        /**
         * 追加到报告归纳请求体里的参数（透传给模型服务）。
         *
         * <p>这是厂商扩展字段，参数名写错通常表现为请求被拒，把该项删掉即可恢复。
         */
        private Map<String, Object> extraBody = new LinkedHashMap<>();

        /**
         * {@link #extraBody} 中真正应用到报告归纳调用上的键。留空表示不应用任何额外参数。
         *
         * <p>不能全量下发：这些键会合进 Spring AI 的全局 ChatOptions，而对话 / PPT 那条链路
         * 用的是同一个 ChatModel，全量下发会顺带把对话助手的思考过程一起关掉。
         */
        private List<String> extraBodyKeys = new ArrayList<>();
    }
}
