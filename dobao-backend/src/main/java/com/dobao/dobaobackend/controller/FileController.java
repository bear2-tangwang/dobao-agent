package com.dobao.dobaobackend.controller;


import com.dobao.dobaobackend.auth.LoginRequired;
import com.dobao.dobaobackend.auth.UserContext;
import com.dobao.dobaobackend.common.BaseResult;
import com.dobao.dobaobackend.config.GithubOAuthProperties;
import com.dobao.dobaobackend.entity.record.FileInfo;
import com.dobao.dobaobackend.service.FileManageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * 文件控制器
 * 提供文件上传、查询等接口
 *
 * <p>所有接口都要求登录：文件表的 {@code file_id} 是全局唯一索引，
 * 只按 ID 查询等于"猜到 ID 就能读别人的文件"，因此每个按 ID 的读写
 * 都额外带上当前用户做归属校验（见 {@code FileManageService} 的 userId 重载）。
 */
@RestController
@RequestMapping("/file")
@Tag(name = "文件管理", description = "文件上传、查询等接口")
@LoginRequired
@Slf4j
@RequiredArgsConstructor
public class FileController {

    private final FileManageService fileManageService;
    private final GithubOAuthProperties authProperties;

    /**
     * 取当前登录用户ID（未登录时回退兜底用户，保证本地调试可用）
     */
    private String currentUserId() {
        return UserContext.getUserIdOrDefault(authProperties.getDefaultUserId());
    }

    /**
     * 上传文件
     */
    @PostMapping("/upload")
    @Operation(summary = "上传文件", description = "上传文件并返回文件ID，支持PDF、DOC、DOCX、TXT、PNG、JPG等格式")
    public BaseResult<FileInfo> uploadFile(@RequestParam("file") MultipartFile file) {

        log.info("收到文件上传请求: fileName={}, size={}", file.getOriginalFilename(), file.getSize());

        try {
            if (file.isEmpty()) {
                return BaseResult.newError("文件不能为空");
            }

            // 上传并处理文件（落库时写入归属用户）
            FileInfo fileInfo = fileManageService.uploadFile(file, currentUserId());
            log.info("文件上传成功: fileId={}, userId={}", fileInfo.getFileId(), fileInfo.getUserId());
            return BaseResult.newSuccess(fileInfo);

        } catch (Exception e) {
            log.error("文件上传失败", e);
            return BaseResult.newError("文件上传失败: " + e.getMessage());
        }
    }

    /**
     * 获取文件信息
     */
    @GetMapping("/info/{fileId}")
    @Operation(summary = "获取文件信息", description = "根据文件ID获取文件的基本信息")
    public BaseResult<FileInfo> getFileInfo(@PathVariable String fileId) {
        log.info("获取文件信息: fileId={}", fileId);

        try {
            FileInfo fileInfo = fileManageService.getFileInfo(fileId, currentUserId());

            return BaseResult.newSuccess(fileInfo);

        } catch (Exception e) {
            log.error("获取文件信息失败: fileId={}", fileId, e);
            return BaseResult.newError("获取文件信息失败: " + e.getMessage());
        }
    }

    /**
     * 获取文件内容
     */
    @GetMapping("/content/{fileId}")
    @Operation(summary = "获取文件内容", description = "根据文件ID获取文件的文本内容")
    public BaseResult<Map<String, Object>> getFileContent(@PathVariable String fileId) {
        log.info("获取文件内容: fileId={}", fileId);

        try {
            String content = fileManageService.getFileContent(fileId, currentUserId());

            Map<String, Object> response = new HashMap<>();
            response.put("content", content);
            response.put("length", content.length());

            return BaseResult.newSuccess(response);

        } catch (Exception e) {
            log.error("获取文件内容失败: fileId={}", fileId, e);
            return BaseResult.newError("获取文件内容失败: " + e.getMessage());
        }
    }

    /**
     * 删除文件
     */
    @DeleteMapping("/{fileId}")
    @Operation(summary = "删除文件", description = "根据文件ID删除文件及其内容")
    public BaseResult<String> deleteFile(@PathVariable String fileId) {
        log.info("删除文件: fileId={}", fileId);

        try {
            fileManageService.deleteFile(fileId, currentUserId());

            return BaseResult.newSuccess("文件删除成功");

        } catch (Exception e) {
            log.error("删除文件失败: fileId={}", fileId, e);
            return BaseResult.newError("删除文件失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有文件列表（仅当前用户的）
     */
    @GetMapping("/list")
    @Operation(summary = "获取所有文件列表", description = "获取当前登录用户的所有文件信息")
    public BaseResult<Map<String, Object>> listFiles() {
        String userId = currentUserId();
        log.info("获取文件列表: userId={}", userId);

        try {
            var files = fileManageService.getAllFiles(userId);
            int count = fileManageService.getFileCount(userId);

            Map<String, Object> response = new HashMap<>();
            response.put("count", count);
            response.put("files", files);

            return BaseResult.newSuccess(response);

        } catch (Exception e) {
            log.error("获取文件列表失败", e);
            return BaseResult.newError("获取文件列表失败: " + e.getMessage());
        }
    }

    /**
     * 检查文件是否存在（仅限自己的文件 —— 否则就成了"探测别人 fileId"的接口）
     */
    @GetMapping("/exists/{fileId}")
    @Operation(summary = "检查文件是否存在", description = "检查指定文件ID的文件是否存在（限当前用户）")
    public BaseResult<Boolean> fileExists(@PathVariable String fileId) {
        log.info("检查文件是否存在: fileId={}", fileId);

        try {
            boolean exists;
            try {
                fileManageService.getFileInfo(fileId, currentUserId());
                exists = true;
            } catch (IllegalArgumentException e) {
                exists = false;
            }

            return BaseResult.newSuccess(exists);

        } catch (Exception e) {
            log.error("检查文件存在失败: fileId={}", fileId, e);
            return BaseResult.newError("检查文件存在失败: " + e.getMessage());
        }
    }
}
