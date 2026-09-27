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
import com.dobao.dobaobackend.mapper.AiFileInfoMapper;
import com.dobao.dobaobackend.mapper.AiPptInstMapper;
import com.dobao.dobaobackend.service.AiSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
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
            LambdaQueryWrapper<AiSession> queryWrapper = new LambdaQueryWrapper<>();
            if (StringUtils.hasText(agentType)) {
                queryWrapper.eq(AiSession::getAgentType, agentType);
            }

            Page<AiSession> page = new Page<>(pageNum, pageSize);
            Page<AiSession> resultPage = aiSessionService.page(page, queryWrapper.orderByDesc(AiSession::getUpdateTime));

            Map<String, AiSession> latestSessionMap = resultPage.getRecords().stream()
                    .collect(Collectors.toMap(
                            AiSession::getSessionId,
                            session -> session,
                            (s1, s2) -> s1.getUpdateTime().isBefore(s2.getUpdateTime()) ? s1 : s2
                    ));

            List<SessionListVO> sessionList = latestSessionMap.values().stream()
                    .map(session -> SessionListVO.fromAiSession(session, null))
                    .sorted((s1, s2) -> s2.getUpdateTime().compareTo(s1.getUpdateTime()))
                    .collect(Collectors.toList());

            PageResult<SessionListVO> pageResult = PageResult.<SessionListVO>builder()
                    .pageNum(pageNum)
                    .pageSize(pageSize)
                    .total((long) sessionList.size())
                    .records(sessionList)
                    .build();

            return BaseResult.newSuccess(pageResult);

        } catch (Exception e) {
            log.error("获取会话列表失败", e);
            return BaseResult.newError("获取会话列表失败: " + e.getMessage());
        }
    }

    /**
     * 删除会话及其关联的文件记录、PPT实例记录
     */
    @DeleteMapping("/{conversationId}")
    @Operation(summary = "删除会话", description = "删除会话及其关联数据")
    @Transactional(rollbackFor = Exception.class)
    public BaseResult<String> deleteSession(@PathVariable String conversationId) {
        log.info("删除会话: conversationId={}", conversationId);

        try {
            LambdaQueryWrapper<AiSession> sessionQuery = new LambdaQueryWrapper<AiSession>()
                    .eq(AiSession::getSessionId, conversationId)
                    .last("LIMIT 1");
            AiSession session = aiSessionService.getOne(sessionQuery);

            if (session == null) {
                return BaseResult.newError("会话不存在");
            }

            // 删除会话关联的文件记录
            LambdaQueryWrapper<AiFileInfo> fileQuery = new LambdaQueryWrapper<AiFileInfo>()
                    .eq(AiFileInfo::getConversationId, conversationId);
            aiFileInfoMapper.delete(fileQuery);

            // 删除会话关联的PPT实例记录
            LambdaQueryWrapper<AiPptInst> pptQuery = new LambdaQueryWrapper<AiPptInst>()
                    .eq(AiPptInst::getConversationId, conversationId);
            aiPptInstMapper.delete(pptQuery);

            // 删除会话消息记录
            LambdaQueryWrapper<AiSession> deleteSessionQuery = new LambdaQueryWrapper<AiSession>()
                    .eq(AiSession::getSessionId, conversationId);
            aiSessionService.remove(deleteSessionQuery);

            return BaseResult.newSuccess("会话删除成功");

        } catch (Exception e) {
            log.error("删除会话失败: conversationId={}", conversationId, e);
            return BaseResult.newError("删除会话失败: " + e.getMessage());
        }
    }

    /**
     * 将会话记录转换为消息VO，并补齐关联文件信息
     */
    private MessageVO convertToMessageVO(AiSession session) {
        AiFileInfo fileInfo = null;
        if (StringUtils.hasText(session.getFileid())) {
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
                .recommend(session.getRecommend())
                .fileName(fileInfo != null ? fileInfo.getFileName() : null)
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
