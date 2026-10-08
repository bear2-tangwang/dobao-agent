package com.dobao.dobaobackend.agent.ppt.strategy;


import com.alibaba.fastjson2.JSON;
import com.dobao.dobaobackend.entity.record.pptx.*;
import com.dobao.dobaobackend.prompts.PptBuilderPrompts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Schema生成策略
 */
@Slf4j
public class SchemaStrategy implements PptStateStrategy {

    private static final PptInstStatus TARGET_STATUS = PptInstStatus.RENDER;

    @Override
    public void execute(AiPptInst inst, Sinks.Many<String> sink, String query,
                        StringBuilder thinkingBuffer, PptStateStrategyContext context) {
        sink.tryEmitNext(context.createThinkingResponse("正在设计PPT详细内容...\n"));

        String templateCode = inst.getTemplateCode();
        AiPptTemplate template = context.getPptTemplateService().getByCode(templateCode);
        String templateSchema = template.getTemplateSchema();
        String outline = inst.getOutline();

        String prompt = PptBuilderPrompts.getSchemaGenerationPrompt(templateSchema, outline);

        BeanOutputConverter<PptSchema> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
        });
        // Schema 必须一次性拿到完整 JSON，所以走非流式调用
        Disposable disposable = Mono.fromCallable(() -> {
                    String json = context.getChatModel().call(new Prompt(prompt)).getResult().getOutput().getText();
                    PptSchema pptSchema = converter.convert(json);
                    String pptSchemaJson = JSON.toJSONString(pptSchema);

                    context.getPptInstService().updatePptSchema(inst.getId(), pptSchemaJson, TARGET_STATUS);

                    processImageGeneration(pptSchema, sink, inst.getConversationId(), context);

                    // 图片生成会就地写入 url，需要再存一次
                    context.getPptInstService().updatePptSchema(inst.getId(), JSON.toJSONString(pptSchema), TARGET_STATUS);
                    context.continueStateMachine(inst, sink, query, thinkingBuffer);
                    return null;
                })
                .doOnError(err -> {
                    log.error("Schema生成异常", err);
                    // 失败不回退状态，只记录错误信息
                    context.getPptInstService().updateError(inst.getId(),
                            "Schema生成失败: " + err.getMessage(), PptInstStatus.SCHEMA);
                    PptStateStrategyFactory.getInstance().executeFailedState(inst, sink, query, thinkingBuffer, context);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        // 交给任务管理器，便于取消时中断
        context.setDisposable(inst.getConversationId(), disposable);
    }

    /**
     * 用给定的修改提示词走一遍 Schema 生成，供修改流程使用
     */
    public void executeWithModifyPrompt(AiPptInst inst, Sinks.Many<String> sink, String query,
                                        StringBuilder thinkingBuffer, PptStateStrategyContext context,
                                        String modifyPrompt) {
        sink.tryEmitNext(context.createThinkingResponse("正在重新生成PPT详细内容...\n"));

        BeanOutputConverter<PptSchema> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
        });

        Disposable disposable = Mono.fromCallable(() -> {
                    String json = context.getChatModel().call(new Prompt(modifyPrompt)).getResult().getOutput().getText();
                    PptSchema pptSchema = converter.convert(json);
                    String pptSchemaJson = JSON.toJSONString(pptSchema);

                    context.getPptInstService().updatePptSchema(inst.getId(), pptSchemaJson, TARGET_STATUS);

                    processImageGeneration(pptSchema, sink, inst.getConversationId(), context);

                    // 图片生成会就地写入 url，需要再存一次
                    context.getPptInstService().updatePptSchema(inst.getId(), JSON.toJSONString(pptSchema), TARGET_STATUS);
                    context.continueStateMachine(inst, sink, query, thinkingBuffer);
                    return null;
                })
                .doOnError(err -> {
                    log.error("Schema生成异常", err);
                    // 失败不回退状态，只记录错误信息
                    context.getPptInstService().updateError(inst.getId(),
                            "Schema生成失败: " + err.getMessage(), PptInstStatus.SCHEMA);
                    PptStateStrategyFactory.getInstance().executeFailedState(inst, sink, query, thinkingBuffer, context);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        // 交给任务管理器，便于取消时中断
        context.setDisposable(inst.getConversationId(), disposable);
    }

    @Override
    public PptInstStatus getTargetStatus() {
        return TARGET_STATUS;
    }

    private void processImageGeneration(PptSchema pptSchema, Sinks.Many<String> sink, String conversationId,
                                        PptStateStrategyContext context) {
        if (pptSchema.getSlides() == null) {
            return;
        }

        // 收集所有缺图字段
        List<ImageGenerationTask> tasks = new ArrayList<>();
        for (Slide slide : pptSchema.getSlides()) {
            if (slide.getData() == null) {
                continue;
            }

            for (Map.Entry<String, FieldData> entry : slide.getData().entrySet()) {
                String key = entry.getKey();
                FieldData fieldData = entry.getValue();
                if (fieldData == null) {
                    continue;
                }

                String type = fieldData.getType();
                if (!"image".equalsIgnoreCase(type) && !"background".equalsIgnoreCase(type)) {
                    continue;
                }

                if (fieldData.getUrl() != null && !fieldData.getUrl().isEmpty()) {
                    continue;
                }

                // url 为空时，content 就是文生图提示词
                String prompt = fieldData.getContent();
                if (prompt == null || prompt.isEmpty()) {
                    continue;
                }

                tasks.add(new ImageGenerationTask(key, fieldData, prompt));
            }
        }

        if (tasks.isEmpty()) {
            return;
        }

        int total = tasks.size();
        sink.tryEmitNext(context.createThinkingResponse("✅PPT内容设计完成，开始生成图片素材\n"));

        sink.tryEmitNext(context.createThinkingResponse("共需生成 " + total + " 张图片，开始生成...\n"));

        // 逐个生成图片
        for (int i = 0; i < tasks.size(); i++) {
            ImageGenerationTask task = tasks.get(i);
            int current = i + 1;

            sink.tryEmitNext(context.createThinkingResponse("正在生成图片 (" + current + "/" + total + ")... \n"));

            try {
                // 调用图片生成服务
                String originalImageUrl = context.getImageGenerationService().generateImage(task.prompt);

                // 下载图片并上传到MinIO
                byte[] imageBytes = downloadImageFromUrl(originalImageUrl);

                if (imageBytes != null && imageBytes.length > 0) {
                    // 上传到MinIO
                    String objectName = "ppt/" + conversationId + "/images/" + System.currentTimeMillis() + "_" + (i + 1) + ".png";
                    String minioUrl = context.getMinioService().uploadFile(objectName, imageBytes, "image/png");

                    // 更新schema中的url为MinIO地址
                    task.fieldData.setUrl(minioUrl);

                    sink.tryEmitNext(context.createThinkingResponse("✅ 图片生成完成 (" + current + "/" + total + ")\n"));
                    log.info("图片已上传到MinIO: {} -> {}", task.key, minioUrl);
                } else {
                    throw new RuntimeException("图片下载失败");
                }

            } catch (Exception e) {
                log.error("图片生成或上传失败: {}", task.prompt, e);
                sink.tryEmitNext(context.createThinkingResponse("⚠ 图片生成失败 (" + current + "/" + total + "): \n" + task.key));
                // 使用空字符串
                task.fieldData.setUrl("");
            }
        }
        sink.tryEmitNext(context.createThinkingResponse("✅ 所有图片生成完成\n"));
        sink.tryEmitNext(context.createThinkingResponse("✅素材准备就绪，开始渲染PPT\n"));
    }

    /**
     * 从URL下载图片
     */
    private byte[] downloadImageFromUrl(String imageUrl) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() == 200) {
            return response.body();
        } else {
            throw new RuntimeException("下载图片失败，状态码: " + response.statusCode());
        }
    }

    /**
     * 图片生成任务
     */
    private static class ImageGenerationTask {
        String key;
        FieldData fieldData;
        String prompt;

        ImageGenerationTask(String key, FieldData fieldData, String prompt) {
            this.key = key;
            this.fieldData = fieldData;
            this.prompt = prompt;
        }
    }
}
