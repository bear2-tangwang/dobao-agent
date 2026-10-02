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
 *   <li>{@link #REPORT_SYSTEM}：由**已格式化好的问答清单**归纳知识点与待补充知识点（一次调用）。</li>
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
     * 知识点归纳：<b>全流程唯一一次"读内容做总结"的调用</b>。
     *
     * <p>输入是已经格式化好的问答清单（每条含 qaId、面试官原话、候选人原话与时间戳），
     * 不再是分块结果，也不需要模型再抽取问答 —— 它只做"从这些原话里归纳出知识点"。
     */
    public static final String REPORT_SYSTEM = """
            你是面试复盘报告撰写助手。输入是一场面试的问答清单（每条含编号 qaId、
            面试官的问题原文、候选人的回答原文），你要产出两部分内容：

            1. knowledgeTopics：候选人在本次面试中**实际展现出来的知识点**，按主题归类。
               - topic 是知识点主题（如"Redis"、"JVM"）；
               - points 是该主题下具体答到的要点，**必须来自输入中的原始内容**，不得编造；
               - relatedQaIds 填这些要点出自哪些问答条目的 qaId（必须是输入中出现过的编号）。

            2. knowledgeGaps：候选人**回答得不充分或答错**的知识点，以及为什么需要补、往哪些方向补。
               - point 是知识点；
               - performance 是候选人在本次面试中的表现（基于输入内容描述）；
               - why 是为什么要补这个知识点；
               - directions 是建议补充的方向（**只写方向，不要给具体资料链接或课程名**）。

            严格要求：
            - 只能依据输入内容，**严禁编造候选人没说过的话、没展现过的知识点**；
            - 输入里的很多条目是岗位介绍、面试流程说明、约定时间等**非技术对话**，
              它们不是知识点，不要从中硬凑 topic；
            - 如果整份清单里没有任何技术内容，knowledgeTopics 与 knowledgeGaps 都可以返回空数组，
              不要为了凑内容而编造；
            - 不做评分、不做总评、不评价候选人是否通过；
            - 只输出 JSON，不要任何解释、思考过程或 markdown 代码块。""";

    /**
     * 知识点归纳的用户消息
     *
     * @param qaJson 已格式化好的问答清单（JSON 文本）
     */
    public static String reportUser(String qaJson) {
        return """
                以下是本场面试的问答清单（JSON，问题与回答均为逐字原文，未做改写）：

                %s

                请据此生成知识点清单与待补充知识点。""".formatted(qaJson);
    }
}
