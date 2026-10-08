package com.dobao.dobaobackend.service.impl;


import com.dobao.dobaobackend.entity.record.pptx.AiPptInst;
import com.dobao.dobaobackend.entity.record.pptx.AiPptTemplate;
import com.dobao.dobaobackend.service.AiPptTemplateService;
import com.dobao.dobaobackend.service.MinioService;
import com.dobao.dobaobackend.service.PptPythonRenderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * PPT Python 渲染服务实现
 */
@Slf4j
@Service
public class PptPythonRenderServiceImpl implements PptPythonRenderService {

    /** 渲染脚本在 classpath 上的位置 */
    private static final String RENDER_SCRIPT_RESOURCE = "python/render_ppt.py";

    /** python 进程的超时上限 */
    private static final long RENDER_TIMEOUT_MS = 5 * 60 * 1000L;

    private final AiPptTemplateService templateService;
    private final MinioService minioService;

    /** 脚本落盘后的绝对路径，只解压一次 */
    private volatile String cachedScriptPath;

    public PptPythonRenderServiceImpl(AiPptTemplateService templateService, MinioService minioService) {
        this.templateService = templateService;
        this.minioService = minioService;
    }

    @Override
    public String renderPpt(AiPptInst inst, String pptSchema) throws Exception {

        log.info("开始渲染PPT: instId={}", inst.getId());

        AiPptTemplate template = templateService.getByCode(inst.getTemplateCode());
        if (template == null) {
            throw new RuntimeException("模板不存在: " + inst.getTemplateCode());
        }

        String pythonScriptPath = getPythonScriptPath();
        String templateFilePath = template.getFilePath();
        String outputDir = getOutputDir();

        String outputFileName = "ppt_" + inst.getId() + "_" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".pptx";

        String outputFilePath = outputDir + File.separator + outputFileName;

        File templateFile = new File(templateFilePath);
        if (!templateFile.exists()) {
            throw new RuntimeException("模板文件不存在: " + templateFilePath);
        }

        List<String> command = List.of(
                "python",
                pythonScriptPath,
                "--template", templateFilePath,
                "--output", outputFilePath
        );

        log.info("执行Python命令: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();

        env.put("PYTHONIOENCODING", "utf-8");

        // Windows 环境变量长度上限约 32KB，大 JSON 走临时文件传递
        if (pptSchema.length() > 20000) {

            Path tempFile = Files.createTempFile("ppt_schema_", ".json");
            Files.writeString(tempFile, pptSchema);

            env.put("PPT_SCHEMA_FILE", tempFile.toAbsolutePath().toString());
            log.info("JSON过大，使用临时文件传递: {}", tempFile);

        } else {
            env.put("PPT_SCHEMA", pptSchema);
        }

        Process process = pb.start();

        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                log.info("Python输出: {}", line);
            }
        }

        // 读 stdout 到 EOF 时进程通常已退出，这里再按超时上限兜一次底
        if (!process.waitFor(RENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new RuntimeException("Python执行超时");
        }

        int exitCode = process.exitValue();

        if (exitCode != 0) {
            log.error("Python执行失败: {}", output);
            throw new RuntimeException("Python脚本执行失败:\n" + output);
        }

        File outputFile = new File(outputFilePath);
        if (!outputFile.exists()) {
            throw new RuntimeException("PPT未生成: " + outputFilePath);
        }

        log.info("PPT生成成功，开始上传到MinIO");
        byte[] fileBytes = Files.readAllBytes(outputFile.toPath());

        String objectName = "ppt/" + inst.getConversationId() + "/" + outputFileName;

        String fileUrl = minioService.uploadFile(objectName, fileBytes, "application/vnd.openxmlformats-officedocument.presentationml.presentation");

        log.info("PPT已上传到MinIO: {}", fileUrl);

        // ---------- 删除本地文件 ----------
        try {
            Files.deleteIfExists(outputFile.toPath());
            log.info("本地PPT文件已删除: {}", outputFilePath);
        } catch (Exception e) {
            log.warn("删除本地文件失败: {}", outputFilePath, e);
        }

        return fileUrl;
    }
    /**
     * 把渲染脚本从 classpath 解压到临时文件，返回其绝对路径。
     * 用临时文件而不是 {@code getFile()}：打成可执行 jar 后脚本没有独立的文件系统路径。
     */
    private String getPythonScriptPath() throws IOException {
        if (cachedScriptPath == null) {
            synchronized (this) {
                if (cachedScriptPath == null) {
                    ClassPathResource resource = new ClassPathResource(RENDER_SCRIPT_RESOURCE);
                    if (!resource.exists()) {
                        throw new IOException("渲染脚本不存在: " + RENDER_SCRIPT_RESOURCE);
                    }
                    try (InputStream in = resource.getInputStream()) {
                        Path script = Files.createTempFile("render_ppt_", ".py");
                        Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
                        script.toFile().deleteOnExit();
                        cachedScriptPath = script.toAbsolutePath().toString();
                    }
                }
            }
        }
        return cachedScriptPath;
    }

    /**
     * 获取输出目录（用于临时存储）
     */
    private String getOutputDir() {
        String projectRoot = System.getProperty("user.dir");
        String outputDir = projectRoot + File.separator + "output" + File.separator + "ppt";
        try {
            Path path = Paths.get(outputDir);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
        } catch (Exception e) {
            log.error("创建输出目录失败: {}", outputDir, e);
        }
        return outputDir;
    }
}
