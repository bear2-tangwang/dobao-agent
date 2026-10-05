package com.dobao.dobaobackend.agent.ppt;


import com.dobao.dobaobackend.entity.record.pptx.AiPptInst;
import com.dobao.dobaobackend.entity.record.pptx.PptInstStatus;
import com.dobao.dobaobackend.entity.record.pptx.PptIntent;
import com.dobao.dobaobackend.entity.record.pptx.PptIntentResult;
import com.dobao.dobaobackend.prompts.PptBuilderPrompts;
import com.dobao.dobaobackend.service.AiPptInstService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.StringUtils;

/**
 * PPT意图识别器
 */
@Slf4j
public class PptIntentRecognizer {

    private final ChatClient chatClient;
    private final AiPptInstService pptInstService;

    public PptIntentRecognizer(ChatClient chatClient, AiPptInstService pptInstService) {
        this.chatClient = chatClient;
        this.pptInstService = pptInstService;
    }

    /**
     * 意图识别
     *
     * @param conversationId 会话ID
     * @param query          用户查询
     * @return 意图识别结果
     */
    public PptIntentResult recognize(String conversationId, String query) {
        // 取最近一个实例，不限状态
        AiPptInst latestInst = pptInstService.getLatestInst(conversationId);

        if (latestInst == null) {
            log.info("会话中无PPT实例，默认新建");
            return new PptIntentResult(PptIntent.CREATE_PPT, "会话中无PPT实例，默认新建");
        }

        PptInstStatus status = latestInst.getStatusEnum();
        String errorMsg = latestInst.getErrorMsg();

        if (needsResume(status, errorMsg, query)) {
            log.info("检测到断点重连需求: status={}, hasError={}", status, StringUtils.hasText(errorMsg));
            return new PptIntentResult(PptIntent.RESUME_PPT,
                    "检测到上次执行未完成，从状态 " + status + " 继续执行");
        }

        // 只有 SUCCESS 状态才需要 LLM 区分新建与修改
        if (status == PptInstStatus.SUCCESS) {
            return recognizeWithLLM(query);
        }

        log.info("状态为 {}，默认新建", status);
        return new PptIntentResult(PptIntent.CREATE_PPT, "状态为 " + status + "，默认新建");
    }

    private boolean needsResume(PptInstStatus status, String errorMsg, String query) {
        // 有错误信息说明上次执行失败，需要重连
        if (StringUtils.hasText(errorMsg)) {
            return true;
        }

        String lowerQuery = query.toLowerCase();
        String[] resumeKeywords = {"继续", "重试", "resume", "retry", "继续执行", "继续生成"};
        for (String keyword : resumeKeywords) {
            if (lowerQuery.contains(keyword)) {
                return true;
            }
        }

        // 中间状态默认继续，除非用户明确要求新建
        if (status != PptInstStatus.SUCCESS && status != PptInstStatus.INIT) {
            String[] newKeywords = {"新建", "重新", "重新生成", "new", "create new"};
            for (String keyword : newKeywords) {
                if (lowerQuery.contains(keyword)) {
                    return false; // 用户明确要新建
                }
            }
            return true; // 默认继续
        }

        return false;
    }

    private PptIntentResult recognizeWithLLM(String query) {
        String prompt = PptBuilderPrompts.INTENT_RECOGNITION_PROMPT;
        BeanOutputConverter<PptIntentResult> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {});

        try {
            String response = chatClient.prompt()
                    .messages(new SystemMessage(prompt), new UserMessage("<question>" + query + "</question>"))
                    .call()
                    .content();

            log.info("LLM意图识别响应: {}", response);
            PptIntentResult result = converter.convert(response);
            return result;
        } catch (Exception e) {
            log.error("解析意图识别结果失败", e);
            return new PptIntentResult(PptIntent.CREATE_PPT, "意图识别失败，默认新建");
        }
    }
}
