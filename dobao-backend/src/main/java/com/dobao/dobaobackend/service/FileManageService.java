package com.dobao.dobaobackend.service;


import com.dobao.dobaobackend.auth.UserContext;
import com.dobao.dobaobackend.entity.record.FileInfo;
import com.dobao.dobaobackend.service.impl.FileInfoServiceImpl;
import com.dobao.dobaobackend.splitter.OverlapParagraphTextSplitter;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 文件管理服务
 * 提供文件上传、查询、删除等纯业务功能
 */
@Service
@Slf4j
public class FileManageService {

    @Autowired
    private MinioService minioService;

    @Autowired
    private FileParserService fileParserService;

    @Autowired
    private FileInfoServiceImpl fileInfoService;

    @Autowired
    private EmbeddingService embeddingService;

    private OpenAiChatModel multimodalChatModel;

    /**
     * 大文件阈值（字符数）
     */
    private static final int LARGE_FILE_THRESHOLD = 5000;

    /**
     * 未登录/无上下文时的兜底用户，与各表 user_id 列的 DEFAULT 'default' 保持一致
     */
    private static final String DEFAULT_USER_ID = "default";

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    /**
     * 初始化多模态模型（用于图片识别）
     */
    @PostConstruct
    public void init() {
        try {
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .temperature(0.2d)
                    .model("qwen3-vl-plus")
                    .build();
            multimodalChatModel = OpenAiChatModel.builder()
                    .openAiApi(OpenAiApi.builder()
                            .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/")
                            .apiKey(new SimpleApiKey(apiKey))
                            .build())
                    .defaultOptions(options)
                    .build();
            log.info("多模态模型初始化成功");
        } catch (Exception e) {
            log.warn("多模态模型初始化失败: {}", e.getMessage());
        }
    }

    /**
     * 上传文件
     *
     * @param file 上传的文件
     * @return 文件信息
     */
    @Transactional(rollbackFor = Exception.class)
    public FileInfo uploadFile(MultipartFile file) {
        return uploadFile(file, UserContext.getUserIdOrDefault(DEFAULT_USER_ID));
    }

    /**
     * 上传文件（指定归属用户）
     *
     * <p>归属用户走显式参数而不是只读 {@code UserContext}：面试上传链路里本方法也可能在
     * 非请求线程被调用，ThreadLocal 那时是 null。
     *
     * @param userId 归属用户；为空时退化为兜底用户
     */
    @Transactional(rollbackFor = Exception.class)
    public FileInfo uploadFile(MultipartFile file, String userId) {
        String fileId = UUID.randomUUID().toString();
        String fileType = getFileType(file.getOriginalFilename());
        long fileSize = file.getSize();
        String owner = StringUtils.isNotBlank(userId) ? userId : DEFAULT_USER_ID;

        log.info("开始处理文件上传: fileId={}, fileName={}, fileType={}, fileSize={}, userId={}",
                fileId, file.getOriginalFilename(), fileType, fileSize, owner);

        try {
            FileInfo fileInfo = FileInfo.builder()
                    .userId(owner)
                    .fileId(fileId)
                    .fileName(file.getOriginalFilename())
                    .fileType(fileType)
                    .fileSize(fileSize)
                    .createdAt(LocalDateTime.now())
                    .status(FileInfo.FileStatus.PROCESSING)
                    .build();

            // 先落库为 PROCESSING，MinIO 上传成功后再回写路径与状态
            fileInfoService.saveFileInfo(fileInfo);

            String objectName = generateObjectName(fileId, fileType);
            String minioPath = minioService.uploadFile(file, objectName);
            log.info("MinIO 上传完成: fileId={}", fileId);

            fileInfo.setMinioPath(minioPath);
            fileInfo.setStatus(FileInfo.FileStatus.SUCCESS);
            fileInfoService.updateFileInfo(fileInfo);

            if (isTextFile(fileType)) {
                try {
                    String extractedText = fileParserService.parseFile(file);
                    fileInfo.setExtractedText(extractedText);
                    fileInfoService.updateFileInfo(fileInfo);
                    log.info("文件解析完成: fileId={}, 文本长度: {}", fileId, extractedText.length());

                    if (isLargeFile(extractedText)) {
                        log.info("检测到大文件，开始向量化处理: fileId={}, 文本长度: {}", fileId, extractedText.length());
                        try {
                            processLargeFileEmbedding(fileId, extractedText);
                            fileInfo.setEmbed(1);
                            fileInfoService.updateFileInfo(fileInfo);
                            log.info("大文件向量化完成: fileId={}", fileId);
                        } catch (Exception e) {
                            log.error("大文件向量化失败: fileId={}", fileId, e);
                            // 向量化失败不影响文件上传，embed 保持为 0
                        }
                    }
                } catch (Exception e) {
                    log.error("文件解析失败: fileId={}", fileId, e);
                    fileInfo.setStatus(FileInfo.FileStatus.FAILED);
                    fileInfoService.updateFileInfo(fileInfo);
                    throw new RuntimeException("文件解析失败: " + e.getMessage(), e);
                }
            } else if (isImageFile(fileType)) {
                try {
                    String extractedText = image2Text(file);
                    fileInfo.setExtractedText(extractedText);
                    fileInfoService.updateFileInfo(fileInfo);
                    log.info("图片识别完成: fileId={}, 识别文本长度: {}", fileId, extractedText.length());
                } catch (Exception e) {
                    log.error("图片识别失败: fileId={}", fileId, e);
                    fileInfo.setStatus(FileInfo.FileStatus.FAILED);
                    fileInfoService.updateFileInfo(fileInfo);
                    throw new RuntimeException("图片识别失败: " + e.getMessage(), e);
                }
            } else if (isAudioFile(fileType)) {
                // 音频文件（面试录音）：这里绝不做耗时动作，只落 MinIO + 建记录，
                // 转写交给 InterviewService 异步处理，否则上传接口无法及时返回。
                log.info("音频文件上传完成，等待转写: fileId={}, 类型: {}", fileId, fileType);
            } else {
                log.info("其他类型文件上传完成: fileId={}, 类型: {}", fileId, fileType);
            }

            log.info("文件上传完成: fileId={}", fileId);

            return fileInfo;
        } catch (Exception e) {
            log.error("文件上传失败: fileId={}", fileId, e);

            FileInfo fileInfo = fileInfoService.getFileInfoById(fileId);
            if (fileInfo != null) {
                fileInfo.setStatus(FileInfo.FileStatus.FAILED);
                fileInfoService.updateFileInfo(fileInfo);
            }

            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 识别图片内容
     *
     * @param file 图片文件
     * @return 图片内容的详细描述
     */
    private String image2Text(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            byte[] imageBytes = IOUtils.toByteArray(inputStream);

            if (imageBytes == null || imageBytes.length == 0) {
                throw new RuntimeException("图片文件内容为空");
            }

            ByteArrayResource imageResource = new ByteArrayResource(imageBytes);
            var userMessage = UserMessage.builder()
                    .text("请描述这张图片的内容，包括场景、对象、布局、颜色、文字信息，直接输出纯文本描述，不要多余说明。")
                    .media(List.of(new Media(MimeTypeUtils.IMAGE_PNG, imageResource)))
                    .build();
            var response = multimodalChatModel.call(new Prompt(List.of(userMessage)));
            String resp = response.getResult().getOutput().getText();

            if (resp == null || resp.trim().isEmpty()) {
                return "[无法识别图片内容]";
            }
            return resp.trim();
        } catch (Exception e) {
            log.error("图片识别异常", e);
            throw new RuntimeException("图片识别失败: " + e.getMessage(), e);
        }
    }

    /**
     * 根据 fileId 获取文件信息
     *
     * @param fileId 文件ID
     * @return 文件信息
     */
    public FileInfo getFileInfo(String fileId) {
        return getFileInfo(fileId, null);
    }

    /**
     * 按「文件ID + 归属用户」取文件信息。
     *
     * <p>控制器必须用这个重载：fileId 是全局唯一索引，只按它查等于"拿到别人的 fileId 就能读内容/删文件"。
     *
     * @param userId 归属用户；为空时不做归属过滤（供内部/后台调用）
     */
    public FileInfo getFileInfo(String fileId, String userId) {
        FileInfo fileInfo = fileInfoService.getFileInfoById(fileId, userId);
        if (fileInfo == null) {
            // 归属不符时也报"不存在"，不回 403，以免泄露这个 fileId 真实存在
            throw new IllegalArgumentException("文件不存在: " + fileId);
        }
        return fileInfo;
    }

    /**
     * 获取文件处理状态（用于查询处理进度）
     *
     * @param fileId 文件ID
     * @return 处理状态描述
     */
    public String getFileProcessingStatus(String fileId) {
        FileInfo fileInfo = getFileInfo(fileId);

        switch (fileInfo.getStatus()) {
            case PROCESSING:
                return "文件正在处理中...";
            case SUCCESS:
                return "文件处理完成，可以查看内容";
            case FAILED:
                return "文件处理失败，请重试";
            default:
                return "未知状态";
        }
    }

    /**
     * 根据 fileId 获取文件内容
     *
     * @param fileId 文件ID
     * @return 文件内容
     */
    public String getFileContent(String fileId) {
        return getFileContent(fileId, null);
    }

    /**
     * 按「文件ID + 归属用户」取文件内容
     */
    public String getFileContent(String fileId, String userId) {
        FileInfo fileInfo = getFileInfo(fileId, userId);

        if (fileInfo.getStatus() != FileInfo.FileStatus.SUCCESS) {
            throw new IllegalStateException("文件尚未处理完成，当前状态: " + fileInfo.getStatus());
        }

        String content = fileInfo.getExtractedText();
        if (content == null || content.trim().isEmpty()) {
            return "该文件没有可识别的内容";
        }

        return content;
    }

    /**
     * 检查文件是否存在
     *
     * @param fileId 文件ID
     * @return 是否存在
     */
    public boolean exists(String fileId) {
        return fileInfoService.exists(fileId);
    }

    /**
     * 删除文件
     *
     * @param fileId 文件ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteFile(String fileId) {
        deleteFile(fileId, null);
    }

    /**
     * 删除文件（带归属校验）
     *
     * @param userId 归属用户；为空时不做归属过滤（供内部清理调用）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteFile(String fileId, String userId) {
        FileInfo fileInfo = getFileInfo(fileId, userId);

        try {
            if (fileInfo.getMinioPath() != null) {
                String objectName = extractObjectName(fileInfo.getMinioPath());
                minioService.deleteFile(objectName);
            }

            fileInfoService.deleteFileInfo(fileId, userId);

            log.info("文件删除成功: fileId={}, userId={}", fileId, userId);
        } catch (Exception e) {
            log.error("文件删除失败: fileId={}", fileId, e);
            throw new RuntimeException("文件删除失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取所有文件列表
     *
     * @return 文件列表
     */
    public Map<String, FileInfo> getAllFiles() {
        return getAllFiles(null);
    }

    /**
     * 取某用户的全部文件
     *
     * @param userId 归属用户；为空时返回所有（仅供内部/后台调用）
     */
    public Map<String, FileInfo> getAllFiles(String userId) {
        List<FileInfo> fileInfos = fileInfoService.getAllFiles(userId);
        return fileInfos.stream()
                .collect(Collectors.toMap(FileInfo::getFileId, fileInfo -> fileInfo, (existing, replacement) -> existing, ConcurrentHashMap::new));
    }

    /**
     * 获取文件数量
     *
     * @return 文件数量
     */
    public int getFileCount() {
        return fileInfoService.getFileCount();
    }

    /**
     * 取某用户的文件数量
     */
    public int getFileCount(String userId) {
        return fileInfoService.getFileCount(userId);
    }

    /**
     * 清理所有文件（用于测试）
     */
    @Transactional(rollbackFor = Exception.class)
    public void clearAll() {
        List<FileInfo> allFiles = fileInfoService.getAllFiles();
        for (FileInfo fileInfo : allFiles) {
            try {
                deleteFile(fileInfo.getFileId());
            } catch (Exception e) {
                log.error("清理文件失败: fileId={}", fileInfo.getFileId(), e);
            }
        }
        log.info("所有文件已清理");
    }

    /**
     * 从文件名中提取文件类型
     */
    private String getFileType(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "unknown";
        }
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < fileName.length() - 1) {
            return fileName.substring(lastDotIndex + 1).toLowerCase();
        }
        return "unknown";
    }

    /**
     * 生成 MinIO 对象名称
     */
    public static String generateObjectName(String fileId, String fileType) {
        return "file-" + fileId.replace("-", "") + "." + fileType.toLowerCase();
    }

    /**
     * 判断是否为文本文件
     */
    private boolean isTextFile(String fileType) {
        return ("pdf".equalsIgnoreCase(fileType) ||
                "docx".equalsIgnoreCase(fileType) ||
                "doc".equalsIgnoreCase(fileType) ||
                "txt".equalsIgnoreCase(fileType));
    }

    /**
     * 判断是否为图片文件
     */
    private boolean isImageFile(String fileType) {
        return ("jpg".equalsIgnoreCase(fileType) ||
                "jpeg".equalsIgnoreCase(fileType) ||
                "png".equalsIgnoreCase(fileType) ||
                "gif".equalsIgnoreCase(fileType) ||
                "bmp".equalsIgnoreCase(fileType));
    }

    /**
     * 判断是否为音频文件（面试录音）。
     *
     * <p>格式清单复用 {@link FileInfo#isAudioType(String)}，保证只有一处定义。
     */
    private boolean isAudioFile(String fileType) {
        return FileInfo.isAudioType(fileType);
    }

    /**
     * 从完整路径中提取对象名称
     */
    private String extractObjectName(String fullPath) {
        if (fullPath == null || !fullPath.contains("/")) {
            return fullPath;
        }
        return fullPath.substring(fullPath.lastIndexOf("/") + 1);
    }

    /**
     * 判断是否为大文件
     *
     * @param text 文本内容
     * @return 是否为大文件
     */
    private boolean isLargeFile(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return text.length() >= LARGE_FILE_THRESHOLD;
    }

    /**
     * 处理大文件向量化
     *
     * @param fileId 文件ID
     * @param text   文本内容
     */
    private void processLargeFileEmbedding(String fileId, String text) {
        log.info("开始处理大文件向量化: fileId={}, 文本长度: {}", fileId, text.length());

        Document document = new Document(text);
        List<Document> documents = List.of(document);

        OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(500, 50);
        List<Document> chunks = splitter.apply(documents);
        log.info("文档切分完成: fileId={}, 切分数量: {}", fileId, chunks.size());

        // 元数据 fileid 是 RAG 检索时的过滤键，改名会让已入库的向量检索不到
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            chunk.getMetadata().put("fileid", fileId);
            chunk.getMetadata().put("chunkId", i);
        }

        embeddingService.embedAndStore(chunks);
        log.info("大文件向量化存储完成: fileId={}, 切分数量: {}", fileId, chunks.size());
    }
}
