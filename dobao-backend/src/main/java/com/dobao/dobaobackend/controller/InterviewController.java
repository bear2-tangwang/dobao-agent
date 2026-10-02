package com.dobao.dobaobackend.controller;

import com.dobao.dobaobackend.common.BaseResult;
import com.dobao.dobaobackend.interview.dto.InterviewReport;
import com.dobao.dobaobackend.interview.dto.InterviewStatusVO;
import com.dobao.dobaobackend.interview.dto.InterviewUploadVO;
import com.dobao.dobaobackend.interview.dto.ReportFile;
import com.dobao.dobaobackend.interview.progress.InterviewProgressHub;
import com.dobao.dobaobackend.service.InterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;

/**
 * 面试总结 · 接口。
 *
 * <p>与既有 {@code FileController} 保持同样的返回风格（{@link BaseResult} 包装 + 统一错误信息），
 * 便于前端复用现有的请求封装。
 *
 * <p>注意上传接口<b>不做任何耗时动作</b>：落 MinIO + 建记录后立即返回 interviewId，
 * 转写与后续分析全部异步，这是"接口 3 秒内返回"这条验收的基础。
 */
@Slf4j
@RestController
@RequestMapping("/interview")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;
    private final InterviewProgressHub progressHub;

    /**
     * 上传面试录音，返回 interviewId 与初始状态
     */
    @PostMapping("/upload")
    public BaseResult<InterviewUploadVO> upload(@RequestParam("file") MultipartFile file) {
        log.info("收到面试录音上传请求: fileName={}, size={}",
                file == null ? null : file.getOriginalFilename(),
                file == null ? null : file.getSize());
        try {
            return BaseResult.newSuccess(interviewService.upload(file));
        } catch (IllegalArgumentException e) {
            // 参数类错误（格式/大小/空文件）是可预期的，按业务错误返回，不打堆栈
            log.warn("面试录音上传参数校验失败: {}", e.getMessage());
            return BaseResult.newError(e.getMessage());
        } catch (Exception e) {
            log.error("面试录音上传失败", e);
            return BaseResult.newError("面试录音上传失败: " + e.getMessage());
        }
    }

    /**
     * 面试进度实时流（SSE）。
     *
     * <p>上传接口只返回"已接收"，真正的进度（转写 / 分析 / 报告）在这条流上推。
     * 返回的是**具名事件**（{@code event: progress} 等），与前端
     * {@code dobao-front/src/composables/useInterview.ts} 的解析口径一致；
     * 流本身只做通知，事实来源仍是数据库，所以前端在断流时会自动降级为轮询。
     */
    @GetMapping(value = "/{interviewId}/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> stream(@PathVariable String interviewId) {
        log.info("收到面试进度流请求: interviewId={}", interviewId);
        return progressHub.stream(interviewId);
    }

    /**
     * 查询处理状态与阶段
     */
    @GetMapping("/{interviewId}/status")
    public BaseResult<InterviewStatusVO> status(@PathVariable String interviewId) {
        try {
            return BaseResult.newSuccess(interviewService.status(interviewId));
        } catch (Exception e) {
            log.warn("查询面试状态失败: interviewId={}, err={}", interviewId, e.getMessage());
            return BaseResult.newError(e.getMessage());
        }
    }

    /**
     * 取结构化报告（问答清单 / 知识点 / 待补充知识点）
     */
    @GetMapping("/{interviewId}/report")
    public BaseResult<InterviewReport> report(@PathVariable String interviewId) {
        try {
            return BaseResult.newSuccess(interviewService.report(interviewId));
        } catch (Exception e) {
            log.warn("查询报告失败: interviewId={}, err={}", interviewId, e.getMessage());
            return BaseResult.newError(e.getMessage());
        }
    }

    /**
     * 下载报告 Markdown 文件
     */
    @GetMapping("/{interviewId}/report/download")
    public ResponseEntity<?> downloadReport(@PathVariable String interviewId) {
        try {
            ReportFile file = interviewService.reportFile(interviewId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + file.fileName() + "\"")
                    .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                    .body(new InputStreamResource(file.inputStream()));
        } catch (Exception e) {
            log.warn("报告下载失败: interviewId={}, err={}", interviewId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BaseResult.newError(e.getMessage()));
        }
    }

    /**
     * 重试 / 重新生成报告。
     *
     * <p>有文字稿时只重跑分析（不重新转写、不重复计费），否则重新提交转写；
     * 这是"重新生成报告"的唯一入口，不再单独提供 analyze 接口。
     */
    @PostMapping("/{interviewId}/retry")
    public BaseResult<String> retry(@PathVariable String interviewId) {
        try {
            interviewService.retry(interviewId);
            return BaseResult.newSuccess("已开始重试，请轮询状态接口");
        } catch (Exception e) {
            log.warn("重试失败: interviewId={}, err={}", interviewId, e.getMessage());
            return BaseResult.newError(e.getMessage());
        }
    }
}
