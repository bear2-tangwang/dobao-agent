package com.dobao.dobaobackend.interview.asr;

import com.dobao.dobaobackend.config.InterviewProperties;
import com.dobao.dobaobackend.interview.asr.dto.AsrTaskResponse;
import com.dobao.dobaobackend.interview.asr.dto.AsrTaskState;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 百炼录音文件识别（异步）客户端。
 *
 * <p>三个端点（实测可用，注意 base-url 必须带 {@code /api/v1}）：
 * <pre>
 * POST {base}/services/audio/asr/transcription   Header: X-DashScope-Async: enable
 * GET  {base}/tasks/{task_id}                    轮询至终态
 * GET  {transcription_url}                       结果 JSON，24 小时失效，必须立刻下载
 * </pre>
 *
 * <p><b>为什么只提供单次查询而没有阻塞式 {@code pollUntilDone}</b>：转写最长要等 30 分钟，
 * 若用阻塞轮询占住线程池，8 个线程会被占满、第 9 个任务静默排队、实例重启即丢。
 * 因此轮询改为由 {@code @Scheduled} 扫描 {@code status=TRANSCRIBING} 的记录驱动
 * （见 {@code InterviewTaskService#pollTranscribingTasks}），本类只负责"查一次"。
 */
@Slf4j
@Component
public class DashScopeAsrClient {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int API_READ_TIMEOUT_MS = 60_000;
    private static final int TRANSFER_READ_TIMEOUT_MS = 10 * 60 * 1000;

    private static final String OSS_PREFIX = "oss://";
    private static final String ASR_PATH = "/services/audio/asr/transcription";
    private static final String TASK_PATH = "/tasks/";

    /** 录音文件识别只支持单声道输入 */
    private static final List<Integer> CHANNEL_IDS = List.of(0);
    /** 产品侧约定"一场面试两个人"（与 QaListBuilder 的配对口径、speaker_count 配置一致） */
    private static final int SPEAKER_COUNT = 2;
    private static final List<String> LANGUAGE_HINTS = List.of("zh", "en");
    /** 热词权重：百炼取值 1~5，5 是最高权重 */
    private static final int VOCABULARY_WEIGHT = 5;

    private final InterviewProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient apiClient;
    private final RestClient transferClient;

    public DashScopeAsrClient(InterviewProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.apiClient = buildClient(API_READ_TIMEOUT_MS);
        this.transferClient = buildClient(TRANSFER_READ_TIMEOUT_MS);
    }

    private static RestClient buildClient(int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(readTimeoutMs);
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 提交转写任务。
     *
     * @param audioUrl   音频地址（dev 为 {@code oss://...}，prod 为公网 https）
     * @param vocabulary 热词，可为空
     * @return 百炼任务 ID
     */
    public String submit(String audioUrl, List<String> vocabulary) {
        InterviewProperties.Asr asr = requireUsableConfig();

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("channel_id", CHANNEL_IDS);
        parameters.put("diarization_enabled", true);
        parameters.put("speaker_count", SPEAKER_COUNT);
        parameters.put("language_hints", LANGUAGE_HINTS);
        Map<String, Integer> vocab = toVocabulary(vocabulary);
        if (!vocab.isEmpty()) {
            parameters.put("vocabulary", vocab);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", asr.getModel());
        payload.put("input", Map.of("file_urls", List.of(encodeOssUrl(audioUrl))));
        payload.put("parameters", parameters);

        String body = post(asr.getBaseUrl() + ASR_PATH, payload, asr.getApiKey(),
                Map.of("X-DashScope-Async", "enable",
                        "X-DashScope-OssResourceResolve", "enable"));
        AsrTaskResponse response = parse(body, AsrTaskResponse.class);
        String taskId = response.output() == null ? null : response.output().taskId();
        if (!StringUtils.hasText(taskId)) {
            throw new IllegalStateException("百炼未返回 task_id: " + body);
        }
        log.info("百炼转写任务已提交: taskId={}, taskStatus={}",
                taskId, response.output().taskStatus());
        return taskId;
    }

    /**
     * 查询一次任务状态（不阻塞）。
     */
    public AsrTaskState query(String taskId) {
        InterviewProperties.Asr asr = requireUsableConfig();
        String body = apiClient.get()
                .uri(asr.getBaseUrl() + TASK_PATH + taskId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + asr.getApiKey())
                .retrieve()
                .body(String.class);
        AsrTaskResponse response = parse(body, AsrTaskResponse.class);
        if (response.output() == null) {
            return new AsrTaskState(null, null, "百炼响应缺少 output: " + body);
        }
        return new AsrTaskState(response.output().taskStatus(),
                response.firstTranscriptionUrl(), response.firstErrorMessage());
    }

    /**
     * 下载结果 JSON 原文。
     *
     * <p><b>两个必须注意的点</b>：
     * <ol>
     *   <li>{@code transcription_url} 是<b>预签名 URL</b>（查询串里带 {@code Signature}）。
     *       必须用 {@link URI} 传入，不能传 String —— 传 String 时 Spring 会把它当作 URI
     *       模板重新编码，签名里的 {@code +}/{@code =} 被改写后 OSS 直接返回
     *       {@code 403 SignatureDoesNotMatch}（已实测踩到）。</li>
     *   <li>必须按字节取回再显式按 UTF-8 解码：若直接用 {@code body(String.class)}，
     *       响应头没有 {@code charset} 时可能按 ISO-8859-1 解码，中文会整段变乱码
     *       —— 步骤 0 的 {@code interView-asr-raw.json} 就是这么坏掉的。</li>
     * </ol>
     */
    public String downloadResult(String resultUrl) {
        byte[] bytes = transferClient.get()
                .uri(URI.create(resultUrl))
                .retrieve()
                .body(byte[].class);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("转写结果下载为空: " + resultUrl);
        }
        String json = new String(bytes, StandardCharsets.UTF_8);
        log.info("转写结果已下载: bytes={}, chars={}", bytes.length, json.length());
        return json;
    }

    /**
     * 取配置并做一次前置校验：api-key 为空或 base-url 少了 {@code /api/v1} 时，
     * 直接抛出可读异常，而不是让请求变成一个难查的 404。
     */
    private InterviewProperties.Asr requireUsableConfig() {
        InterviewProperties.Asr asr = properties.getAsr();
        if (!StringUtils.hasText(asr.getApiKey())) {
            throw new IllegalStateException("interview.asr.api-key 为空，无法调用百炼接口");
        }
        if (!StringUtils.hasText(asr.getBaseUrl()) || !asr.getBaseUrl().contains("/api/v1")) {
            throw new IllegalStateException(
                    "interview.asr.base-url 必须以 /api/v1 结尾，当前值: " + asr.getBaseUrl());
        }
        return asr;
    }

    private String post(String url, Object payload, String apiKey, Map<String, String> extraHeaders) {
        try {
            return apiClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .headers(h -> extraHeaders.forEach(h::add))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("百炼接口返回 " + e.getStatusCode()
                    + ": " + e.getResponseBodyAsString(), e);
        }
    }

    private <T> T parse(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalStateException("解析百炼响应失败: " + body, e);
        }
    }

    private static Map<String, Integer> toVocabulary(List<String> vocabulary) {
        Map<String, Integer> vocab = new LinkedHashMap<>();
        if (vocabulary != null) {
            for (String word : vocabulary) {
                if (StringUtils.hasText(word)) {
                    vocab.put(word.trim(), VOCABULARY_WEIGHT);
                }
            }
        }
        return vocab;
    }

    /**
     * 百炼要求音频 URL 做百分号编码，否则路径含空格/中文时提交直接 400。
     * 逐段编码以便保留 {@code /} 分隔符。
     */
    private static String encodeOssUrl(String audioUrl) {
        if (audioUrl == null || !audioUrl.startsWith(OSS_PREFIX)) {
            return audioUrl;
        }
        String path = audioUrl.substring(OSS_PREFIX.length());
        StringBuilder sb = new StringBuilder(OSS_PREFIX);
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }
}
