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
 * <p>注册方式说明：本项目此前没有任何 {@code @ConfigurationProperties}，也没有
 * {@code @ConfigurationPropertiesScan}，所以这里用 {@link Component} 显式注册；
 * 这是项目"注解式"风格（对比 Mapper 用 {@code @Mapper}）下最省事、也最不容易漏的做法。
 *
 * <p>刻意<b>不加</b> {@code @Validated}：pom 里没有 {@code spring-boot-starter-validation}
 * （{@code spring-boot-starter-web} 自 Boot 2.3 起不再传递它），加了会在启动期直接报错。
 * 校验改为在业务首次使用时做，缺失时给出可读提示。
 *
 * <p><b>两个键没有对应字段</b>：{@code interview.asr.poll-interval-ms} 与
 * {@code interview.stream.heartbeat-ms} 由 {@code @Scheduled} 的占位符直接读取
 * （轮询与心跳的周期必须是注解上的编译期常量），因此这里不重复定义字段，
 * 避免出现"字段默认值和 yml 值各说各话"的第二份真相。
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
     * 报告与知识点归纳相关配置
     */
    private Report report = new Report();

    @Data
    public static class Asr {

        /**
         * 百炼 API 地址，<b>必须带 {@code /api/v1}</b>。
         * 缺了它 {@code /uploads}、{@code /services/audio/asr/transcription}、
         * {@code /tasks/{id}} 三个端点会全部 404。
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

    /**
     * 报告与知识点归纳配置。
     *
     * <p><b>2026-10 重构后本配置块只服务于一次 LLM 调用</b>（知识点 + 待补充知识点）。
     * 原先的 {@code max-questions-per-chunk} 与 {@code extraction-concurrency} 已删除：
     * 问答清单改为由转写句子列表直接格式化（{@code QaListBuilder}），不再分块、不再逐块调模型，
     * 因此"每块多少轮""并发几个块"这两个旋钮已经没有作用对象。
     */
    @Data
    public static class Report {

        /**
         * 知识点归纳使用的模型名（留空表示沿用全局
         * {@code spring.ai.openai.chat.options.model}）。
         *
         * <p>给"归纳换一个更快的模型"留的开关：它是信息抽取任务，
         * 不需要对话/PPT 那种表达能力，换非推理模型可以省掉大量"思考" token。
         */
        private String model;

        /**
         * 知识点归纳调用的随机种子。配合低温保证"同一输入同一输出"（需求 AC-13）。
         */
        private Integer seed = 42;

        /**
         * 追加到知识点归纳请求体里的参数（透传给模型服务）。
         *
         * <p><b>用途</b>：关掉推理模型的"思考"。实测同一 prompt 下
         * {@code enable_thinking: false} 让 completion 从 8064 token 降到 5075（省 37%），
         * 耗时从 &gt;150 秒降到约 120 秒。不同服务商/模型的参数名不一样，
         * 所以做成配置而不是写死在代码里。
         *
         * <p>注意：这是**厂商扩展字段**，写错了通常表现为请求被拒，
         * 此时把该项删掉即可恢复。
         */
        private Map<String, Object> extraBody = new LinkedHashMap<>();

        /**
         * {@link #extraBody} 中真正应用到知识点归纳调用上的键。
         *
         * <p>为什么不直接全量下发：这几个键最终会合进 Spring AI 的全局 ChatOptions，
         * 而对话 / PPT 那条链路也用同一个 ChatModel —— 不加白名单的话
         * "关思考"会顺带把对话助手的思考过程一起关掉，属于越界修改。
         * 留空表示"不应用任何额外参数"。
         */
        private List<String> extraBodyKeys = new ArrayList<>();
    }
}
