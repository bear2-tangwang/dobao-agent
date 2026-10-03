package com.dobao.dobaobackend.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dobao.dobaobackend.common.BaseResult;
import com.dobao.dobaobackend.entity.AiFileInfo;
import com.dobao.dobaobackend.entity.AiSession;
import com.dobao.dobaobackend.entity.record.pptx.AiPptInst;
import com.dobao.dobaobackend.entity.vo.MessageVO;
import com.dobao.dobaobackend.entity.vo.PageResult;
import com.dobao.dobaobackend.entity.vo.SessionDetailVO;
import com.dobao.dobaobackend.entity.vo.SessionListVO;
import com.dobao.dobaobackend.interview.InterviewSessionRecorder;
import com.dobao.dobaobackend.mapper.AiFileInfoMapper;
import com.dobao.dobaobackend.mapper.AiPptInstMapper;
import com.dobao.dobaobackend.service.AiSessionService;
import com.dobao.dobaobackend.service.InterviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话控制器
 * 提供会话详情查询、会话列表分页查询、会话删除接口
 */
@RestController
@RequestMapping("/session")
@Tag(name = "会话管理", description = "会话查询、列表、删除接口")
@Slf4j
public class SessionController {

    @Autowired
    private AiSessionService aiSessionService;

    @Autowired
    private AiFileInfoMapper aiFileInfoMapper;

    @Autowired
    private AiPptInstMapper aiPptInstMapper;

    @Autowired
    private InterviewService interviewService;

    /**
     * 查询会话详情（含全部问答消息列表）
     */
    @GetMapping("/{conversationId}")
    @Operation(summary = "获取会话详情", description = "根据会话ID获取会话详情及消息列表")
    public BaseResult<SessionDetailVO> getSession(@PathVariable String conversationId) {
        log.info("获取会话详情: conversationId={}", conversationId);

        try {
            LambdaQueryWrapper<AiSession> sessionQuery = new LambdaQueryWrapper<AiSession>()
                    .eq(AiSession::getSessionId, conversationId)
                    .orderByAsc(AiSession::getCreateTime);

            List<AiSession> sessions = aiSessionService.list(sessionQuery);

            if (sessions.isEmpty()) {
                return BaseResult.newError("会话不存在");
            }

            String agentType = normalizeAgentType(sessions.get(0).getAgentType());

            SessionDetailVO detailVO = SessionDetailVO.builder()
                    .conversationId(conversationId)
                    .agentType(agentType)
                    .fileid(sessions.get(0).getFileid())
                    .messages(sessions.stream()
                            .map(this::convertToMessageVO)
                            .collect(Collectors.toList()))
                    .build();

            return BaseResult.newSuccess(detailVO);

        } catch (Exception e) {
            log.error("获取会话详情失败: conversationId={}", conversationId, e);
            return BaseResult.newError("获取会话详情失败: " + e.getMessage());
        }
    }

    /**
     * 分页查询会话列表（同一会话只保留最新一条记录）
     */
    @GetMapping("/list")
    @Operation(summary = "获取会话列表", description = "分页查询会话列表")
    public BaseResult<PageResult<SessionListVO>> getSessionList(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "智能体类型") @RequestParam(required = false) String agentType) {
        log.info("获取会话列表: pageNum={}, pageSize={}, agentType={}", pageNum, pageSize, agentType);

        try {
            LambdaQueryWrapper<AiSession> queryWrapper = new LambdaQueryWrapper<AiSession>()
                    // 一个会话只保留最新一行：先在 SQL 里挑出每个 session_id 的最大 id，再交给分页
                    .inSql(AiSession::getId, "SELECT MAX(id) FROM ai_session GROUP BY session_id")
                    .orderByDesc(AiSession::getUpdateTime);
            if (StringUtils.hasText(agentType)) {
                // ⚠️ 这个过滤与上面的 MAX(id) GROUP BY session_id 组合在一起有个潜伏问题：
                // 先去重（取每个会话的最新一行）再按 agent_type 过滤，于是"最新行不是该类型"
                // 的会话会被整体过滤掉 —— 混排会话（先在会话里 chat、再跑面试）一旦在面试
                // 之后又聊了一句，按 agent_type=interview 查就查不到它了。
                // 今天前端不传该参数（getSessionList 只传 pageNum/pageSize），所以还没暴露；
                // 要修得把过滤下推到子查询里（或改成 EXISTS），属于另一件事。
                queryWrapper.eq(AiSession::getAgentType, agentType);
            }

            Page<AiSession> page = new Page<>(pageNum, pageSize);
            Page<AiSession> resultPage = aiSessionService.page(page, queryWrapper);

            List<SessionListVO> sessionList = resultPage.getRecords().stream()
                    .map(session -> SessionListVO.fromAiSession(session, null))
                    .collect(Collectors.toList());

            PageResult<SessionListVO> pageResult = PageResult.<SessionListVO>builder()
                    .pageNum(pageNum)
                    .pageSize(pageSize)
                    .total(resultPage.getTotal())
                    .records(sessionList)
                    .build();

            return BaseResult.newSuccess(pageResult);

        } catch (Exception e) {
            log.error("获取会话列表失败", e);
            return BaseResult.newError("获取会话列表失败: " + e.getMessage());
        }
    }

    /**
     * 删除会话及其关联的文件记录、PPT实例记录、面试记录
     *
     * <p>面试记录连带 MinIO 上的录音与报告一起删：录音是敏感数据，
     * 会话没了却还能通过直链取到内容，是不一致、也是隐私问题。
     *
     * <p><b>这里刻意不 try/catch</b>（与本类其它方法不同）：删除是跨库跨存储的多步操作，
     * 靠 {@code @Transactional(rollbackFor = Exception.class)} 保证"要么全成、要么全不动"。
     * 一旦把异常吞掉改成 {@code return BaseResult.newError(...)}，方法就"正常返回"了，
     * 事务拦截器不会回滚 —— 那就是"MinIO 对象删了、DB 只删了一半"的半状态。
     * 因此异常必须抛出（由 Spring 转成 500，前端 `deleteChat` 已按请求失败处理）。
     * 会话不存在是业务分支，直接返回错误码，不抛。
     */
    @DeleteMapping("/{conversationId}")
    @Operation(summary = "删除会话", description = "删除会话及其关联数据")
    @Transactional(rollbackFor = Exception.class)
    public BaseResult<String> deleteSession(@PathVariable String conversationId) {
        log.info("删除会话: conversationId={}", conversationId);

        LambdaQueryWrapper<AiSession> sessionQuery = new LambdaQueryWrapper<AiSession>()
                .eq(AiSession::getSessionId, conversationId);
        List<AiSession> sessions = aiSessionService.list(sessionQuery);

        if (sessions.isEmpty()) {
            return BaseResult.newError("会话不存在");
        }

        // 面试会话：fileid 即 interviewId（见 InterviewSessionRecorder）
        List<String> interviewIds = sessions.stream()
                .filter(session -> InterviewSessionRecorder.AGENT_TYPE.equals(session.getAgentType()))
                .map(AiSession::getFileid)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());

        // 先删面试（MinIO 对象 + ai_interview）：失败即抛，整个事务回滚
        interviewService.deleteInterviews(interviewIds, conversationId);

        // 删除会话关联的文件记录
        LambdaQueryWrapper<AiFileInfo> fileQuery = new LambdaQueryWrapper<AiFileInfo>()
                .eq(AiFileInfo::getConversationId, conversationId);
        aiFileInfoMapper.delete(fileQuery);

        // 删除会话关联的PPT实例记录
        LambdaQueryWrapper<AiPptInst> pptQuery = new LambdaQueryWrapper<AiPptInst>()
                .eq(AiPptInst::getConversationId, conversationId);
        aiPptInstMapper.delete(pptQuery);

        // 删除会话消息记录
        aiSessionService.remove(sessionQuery);

        return BaseResult.newSuccess("会话删除成功");
    }

    /**
     * 将会话记录转换为消息VO，并补齐关联文件信息。
     *
     * <p>面试会话要单独走一条分支：它的 {@code fileid} 存的是 interviewId（见
     * {@link InterviewSessionRecorder}），不是 ai_file_info.file_id —— 拿它去查文件表只会白查一次。
     * 文件名直接用 {@code question}（= 录音文件名），前端据此渲染录音 chip，并用 interviewId
     * 去还原进度与报告。
     */
    private MessageVO convertToMessageVO(AiSession session) {
        boolean interview = InterviewSessionRecorder.AGENT_TYPE.equals(session.getAgentType());
        AiFileInfo fileInfo = null;
        if (!interview && StringUtils.hasText(session.getFileid())) {
            fileInfo = aiFileInfoMapper.selectOne(new LambdaQueryWrapper<AiFileInfo>()
                    .eq(AiFileInfo::getFileId, session.getFileid())
                    .last("LIMIT 1"));
        }

        return MessageVO.builder()
                .id(session.getId())
                .question(session.getQuestion())
                .answer(session.getAnswer())
                .thinking(session.getThinking())
                .tools(session.getTools())
                .reference(session.getReference())
                .createTime(session.getCreateTime())
                .fileid(session.getFileid())
                .interviewId(interview ? session.getFileid() : null)
                .recommend(session.getRecommend())
                .fileName(interview ? session.getQuestion() : (fileInfo != null ? fileInfo.getFileName() : null))
                .fileType(fileInfo != null ? fileInfo.getFileType() : null)
                .fileSize(fileInfo != null ? fileInfo.getFileSize() : null)
                .build();
    }

    /**
     * 规范化智能体类型，历史类型统一归并为chat
     */
    private String normalizeAgentType(String agentType) {
        if (!StringUtils.hasText(agentType)) {
            return "chat";
        }
        if ("websearch".equals(agentType) || "file".equals(agentType)) {
            return "chat";
        }
        return agentType;
    }
}
