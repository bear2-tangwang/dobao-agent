package com.dobao.dobaobackend.prompts;

/**
 * 面试总结功能的全部 prompt 模板。
 *
 * <p>集中在一个类里，与项目既有的 {@code BaseAgentPrompts} / {@code PptBuilderPrompts} /
 * {@code PlanExecutePrompts} 保持同一约定，便于统一调优与回归。
 *
 * <p><b>2026-10 重构后全流程只剩两个 prompt</b>：
 * <ol>
 *   <li>{@link #ROLE_RESOLUTION_SYSTEM}：一次性判定哪个说话人是面试官（采样片段，一次调用）；</li>
 *   <li>{@link #REPORT_SYSTEM}：由**已格式化好的问答清单**产出参考回答与面试总结（一次调用）。</li>
 * </ol>
 * 原来的"问答抽取"prompt（分块抽取问答对、输出句子序号、逐条 topics）已随分块链路一起删除：
 * 问答清单改为由转写句子列表直接格式化，不再经过模型。
 *
 * <p>所有 prompt 都强制"只输出 JSON"：项目用的是推理模型，可能输出 {@code <think>} 内容；
 * Spring AI 的 {@code BeanOutputConverter} 默认会剥掉 think 标签与 ``` 围栏，
 * 但**被 maxTokens 截断时不保证**（详见 {@code LlmJsonSupport}）。
 */
public final class InterviewPrompts {

    private InterviewPrompts() {
    }

    // ==================== 步骤 3-① 说话人角色判定 ====================

    /**
     * 角色判定：一次调用决定"哪个说话人编号是面试官"，
     * 结果被 {@code QaListBuilder} 用来把 {@code Speaker0/Speaker1} 一次性替换成"面试官/候选人"。
     *
     * <p>刻意不把"说话时长"作为依据 —— 面试官也可能长篇介绍团队与岗位
     * （步骤 0 的录音里就是如此），只看时长会判错。
     */
    public static final String ROLE_RESOLUTION_SYSTEM = """
            你是面试记录分析助手，擅长从对话内容判断说话人的角色。

            判断依据（按重要性排序）：
            1. 谁在提问、谁在等待对方回答；
            2. 谁在介绍公司、团队、岗位、面试流程；
            3. 谁在回答技术问题、介绍自己的经历与项目。

            要求：
            - 只输出 JSON，不要任何解释、思考过程或 markdown 代码块；
            - interviewerSpeakerId 必须是输入中出现过的说话人编号。
            """;

    /**
     * 角色判定的用户消息
     *
     * @param dialogue 采样后的对话片段，每行形如 {@code [Speaker1] 文本}
     */
    public static String roleResolutionUser(String dialogue) {
        return """
                以下是一场面试录音转写稿的片段（已按开头 / 中段 / 尾段采样）。

                %s

                请判断哪一位说话人是面试官、哪一位是求职者（候选人）。""".formatted(dialogue);
    }


    /**
     * 报告归纳：<b>全流程唯一一次"读内容做总结"的调用</b>。
     *
     * <p>输入是已经格式化好的问答清单（每条含 qaId、面试官原话、候选人原话与时间戳），
     * 不再是分块结果，也不需要模型再抽取问答 —— 它只产出报告的后两节：
     * 技术问题的参考回答，以及约 100 字的面试总结。
     *
     * <p><b>2026-10 重构</b>：原 prompt 产出 {@code knowledgeTopics} / {@code knowledgeGaps}
     * 两棵"知识点树"，篇幅大且与总结重复；现在收敛为
     * {@code referenceAnswers}（参考回答）+ {@code summary}（三字段总结）。
     */
    public static final String REPORT_SYSTEM = """
            你是面试复盘报告撰写助手。输入是一场面试的问答清单（每条含编号 qaId、
            面试官的问题原文、候选人的回答原文），你要产出两部分内容：

            1. referenceAnswers：从问答清单里**提取出真正的技术问题**，逐条给出参考答案。
               - 只处理技术类提问（编程语言、框架、中间件、数据库、算法、系统设计、项目技术细节等）；
                 岗位介绍、公司介绍、面试流程说明、约定时间、寒暄闲聊等**非技术内容一律跳过**，
                 不要为它们生成条目；
               - qaId 必须原样使用输入中该条问答的编号（如 Q003），不得自造编号；
               - question 是把面试官原话**收敛成一个清晰的技术问题**：可以去掉口语、重复与寒暄，
                 但不得改变提问意图，也不要脑补面试官没问的方向；
               - answer 是参考答案：直接给结论与关键要点，可分行分点，**每条控制在 60~150 字**，
                 写清"该怎么答"即可，不要展开成长篇教程；
               - 参考答案必须准确：不确定的 API 名、参数、版本细节宁可略写，**不得编造**。

            2. summary：本场面试的总结，**总结性质，不要展开成清单式长文**。
               - coveredTopics：候选人在本轮**实际涉及到的知识点**，4~8 个短词
                 （如"Redis 缓存穿透"、"MySQL 索引失效"）；
               - gapTopics：候选人**答得不好、没答上或明显没展开**、后续需要补充的知识点，
                 0~6 个短词；没有就返回空数组；
               - summary：约 100 字的中文总结，串起"本轮覆盖了什么、候选人表现如何、
                 后续优先补什么"，只做总结性判断。

            严格要求：
            - 只能依据输入内容，**严禁编造候选人没说过的话、没展现过的知识点**；
            - 不做评分、不做总评、不评价候选人是否通过；
            - 输入里若完全没有技术内容，referenceAnswers 返回空数组、coveredTopics 与 gapTopics
              也返回空数组，summary 只客观概述本场对话的性质，不要为了凑内容而编造；
            - 只输出 JSON，不要任何解释、思考过程或 markdown 代码块。""";

    /**
     * 报告归纳的用户消息
     *
     * @param qaJson 已格式化好的问答清单（JSON 文本）
     */
    public static String reportUser(String qaJson) {
        return """
                以下是本场面试的问答清单（JSON，问题与回答均为逐字原文，未做改写）：

                %s

                请据此生成技术问题的参考回答与面试总结。""".formatted(qaJson);
    }
}
