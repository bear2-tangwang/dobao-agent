package com.dobao.dobaobackend.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dobao.dobaobackend.auth.LoginRequired;
import com.dobao.dobaobackend.auth.UserContext;
import com.dobao.dobaobackend.common.BaseResult;
import com.dobao.dobaobackend.config.GithubOAuthProperties;
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
 * 会话控制器：详情查询、列表分页查询、删除。
 *
 * <p>所有接口都按归属用户过滤；越权访问一律按"会话不存在"回应（不回 403），
 * 避免泄露"这个 ID 真实存在"。
 */
@RestController
@RequestMapping("/session")
@Tag(name = "会话管理", description = "会话查询、列表、删除接口")
@LoginRequired
@Slf4j
public class SessionController {

    /**
     * 会话表名，与 {@link AiSession} 上的 {@code @TableName("ai_session")} 对应。
     *
     * <p>列表查询的去重子查询要自己 FROM 一次这张表（见 {@code getSessionList}），
     * 而 MyBatis-Plus 的 Wrapper 没有"拿到实体表名"的公开入口，所以在此留一个常量：
     * 改表名时两处都要改（单测会断言这段 SQL 的形状）。
     */
    private static final String SESSION_TABLE = "ai_session";

    @Autowired
    private AiSessionService aiSessionService;

    @Autowired
    private AiFileInfoMapper aiFileInfoMapper;

    @Autowired
    private AiPptInstMapper aiPptInstMapper;

    @Autowired
    private InterviewService interviewService;

    @Autowired
    private GithubOAuthProperties authProperties;

    /**
     * 取当前登录用户ID（未登录时回退兜底用户，保证本地调试可用）
     */
    private String currentUserId() {
        return UserContext.getUserIdOrDefault(authProperties.getDefaultUserId());
    }

    /**
     * 查当前用户在某会话下的全部问答记录（按时间正序）
     */
    private List<AiSession> listOwnedSessions(String conversationId, String userId) {
        return aiSessionService.list(new LambdaQueryWrapper<AiSession>()
                .eq(AiSession::getSessionId, conversationId)
                .eq(AiSession::getUserId, userId)
                .orderByAsc(AiSession::getCreateTime));
    }

    /**
     * 查询会话详情（含全部问答消息列表）
     *
     * <p>只查当前用户的会话：{@code conversationId} 由前端生成，
     * 不能作为"这就是我的会话"的凭据。
     */
    @GetMapping("/{conversationId}")
    @Operation(summary = "获取会话详情", description = "根据会话ID获取会话详情及消息列表")
    public BaseResult<SessionDetailVO> getSession(@PathVariable String conversationId) {
        String userId = currentUserId();
        log.info("获取会话详情: conversationId={}, userId={}", conversationId, userId);

        try {
            List<AiSession> sessions = listOwnedSessions(conversationId, userId);

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
     *
     * <p>只列当前用户的会话，去重子查询有两个必须一起满足的点：
     * 用户过滤（以及 agentType 过滤）要下推到子查询内部；
     * 去重必须落成真正的相关子查询。
     */
    @GetMapping("/list")
    @Operation(summary = "获取会话列表", description = "分页查询当前用户的会话列表")
    public BaseResult<PageResult<SessionListVO>> getSessionList(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "智能体类型") @RequestParam(required = false) String agentType) {
        String userId = currentUserId();
        log.info("获取会话列表: pageNum={}, pageSize={}, agentType={}, userId={}",
                pageNum, pageSize, agentType, userId);

        try {

            boolean hasAgentType = StringUtils.hasText(agentType);
            String dedupeSql = "id = (SELECT MAX(s2.id) FROM " + SESSION_TABLE + " s2"
                    + " WHERE s2.session_id = " + SESSION_TABLE + ".session_id"
                    + " AND s2.user_id = {0}"
                    + (hasAgentType ? " AND s2.agent_type = {1}" : "")
                    + ")";

            LambdaQueryWrapper<AiSession> queryWrapper = new LambdaQueryWrapper<AiSession>()
                    .eq(AiSession::getUserId, userId);
            if (hasAgentType) {
                queryWrapper.apply(dedupeSql, userId, agentType);
            } else {
                queryWrapper.apply(dedupeSql, userId);
            }
            queryWrapper.orderByDesc(AiSession::getUpdateTime);

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
     * 因此异常必须抛出（由 Spring 转成 500）。会话不存在是业务分支，直接返回错误码，不抛。
     */
    @DeleteMapping("/{conversationId}")
    @Operation(summary = "删除会话", description = "删除会话及其关联数据")
    @Transactional(rollbackFor = Exception.class)
    public BaseResult<String> deleteSession(@PathVariable String conversationId) {
        String userId = currentUserId();
        log.info("删除会话: conversationId={}, userId={}", conversationId, userId);

        // 只删自己的：别人的 conversationId 传进来会命中 0 行，按"会话不存在"返回
        LambdaQueryWrapper<AiSession> sessionQuery = new LambdaQueryWrapper<AiSession>()
                .eq(AiSession::getSessionId, conversationId)
                .eq(AiSession::getUserId, userId);
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

        // 删除会话关联的文件记录（同样限自己的）
        LambdaQueryWrapper<AiFileInfo> fileQuery = new LambdaQueryWrapper<AiFileInfo>()
                .eq(AiFileInfo::getConversationId, conversationId)
                .eq(AiFileInfo::getUserId, userId);
        aiFileInfoMapper.delete(fileQuery);

        // 删除会话关联的PPT实例记录
        LambdaQueryWrapper<AiPptInst> pptQuery = new LambdaQueryWrapper<AiPptInst>()
                .eq(AiPptInst::getConversationId, conversationId)
                .eq(AiPptInst::getUserId, userId);
        aiPptInstMapper.delete(pptQuery);

        // 删除会话消息记录
        aiSessionService.remove(sessionQuery);

        return BaseResult.newSuccess("会话删除成功");
    }

    /**
     * 将会话记录转换为消息VO，并补齐关联文件信息。
     *
     * <p>面试会话单独走一条分支：它的 {@code fileid} 存的是 interviewId（见
     * {@link InterviewSessionRecorder}），不是 ai_file_info.file_id —— 拿它去查文件表只会白查一次。
     * 文件名直接用 {@code question}（= 录音文件名），前端据此渲染录音 chip，并用 interviewId
     * 去还原进度与报告。
     *
     * <p>查文件信息时带上归属用户：{@code fileid} 是文件表主键，
     * 不加这一层就等于"会话行里存了别人的 fileId 就能读到别人的文件名"。
     */
    private MessageVO convertToMessageVO(AiSession session) {
        boolean interview = InterviewSessionRecorder.AGENT_TYPE.equals(session.getAgentType());
        AiFileInfo fileInfo = null;
        if (!interview && StringUtils.hasText(session.getFileid())) {
            fileInfo = aiFileInfoMapper.selectOne(new LambdaQueryWrapper<AiFileInfo>()
                    .eq(AiFileInfo::getFileId, session.getFileid())
                    .eq(AiFileInfo::getUserId, session.getUserId())
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
     * 规范化智能体类型：websearch/file 归并为 chat
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
