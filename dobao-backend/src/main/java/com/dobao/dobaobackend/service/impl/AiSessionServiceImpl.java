package com.dobao.dobaobackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dobao.dobaobackend.auth.UserContext;
import com.dobao.dobaobackend.entity.AiSession;
import com.dobao.dobaobackend.entity.vo.SaveQuestionRequest;
import com.dobao.dobaobackend.entity.vo.UpdateAnswerRequest;
import com.dobao.dobaobackend.mapper.AiSessionMapper;
import com.dobao.dobaobackend.service.AiSessionService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI会话服务实现类
 * 负责会话问答记录的保存、查询与答案回填
 */
@Service
public class AiSessionServiceImpl extends ServiceImpl<AiSessionMapper, AiSession> implements AiSessionService {

    /**
     * 未登录/无上下文时的兜底用户，与各表 user_id 列的 DEFAULT 'default' 保持一致
     */
    private static final String FALLBACK_USER_ID = "default";

    /**
     * 三级解析归属用户：请求显式传入 → 当前请求上下文 → 兜底用户
     */
    private String resolveUserId(String fromRequest) {
        if (StringUtils.hasText(fromRequest)) {
            return fromRequest;
        }
        String fromContext = UserContext.getUserId();
        return StringUtils.hasText(fromContext) ? fromContext : FALLBACK_USER_ID;
    }

    /**
     * 查询指定会话最近的对话记录（按创建时间倒序）
     */
    @Override
    public List<AiSession> findRecentBySessionId(String sessionId, int maxRecords) {
        return findRecentBySessionId(sessionId, maxRecords, null);
    }

    /**
     * 按归属用户 + 会话ID查询最近记录。
     */
    @Override
    public List<AiSession> findRecentBySessionId(String sessionId, int maxRecords, String userId) {
        LambdaQueryWrapper<AiSession> queryWrapper = new LambdaQueryWrapper<AiSession>()
                .eq(AiSession::getSessionId, sessionId);
        if (StringUtils.hasText(userId)) {
            queryWrapper.eq(AiSession::getUserId, userId);
        }
        queryWrapper.orderByDesc(AiSession::getCreateTime)
                .last("LIMIT " + maxRecords);

        return this.list(queryWrapper);
    }

    /**
     * 保存用户提问，返回生成的会话记录（用于后续回填答案）
     *
     * <p>归属用户优先取请求里的 {@code userId}（SSE/异步链路只能这么传），
     * 没有再回退到 {@code UserContext}（普通请求线程里顺带能用），
     * 最后兜底到 'default'（与各表 user_id 列的默认值一致）。
     */
    @Override
    public AiSession saveQuestion(SaveQuestionRequest request) {
        AiSession aiSession = new AiSession();
        aiSession.setUserId(resolveUserId(request.getUserId()));
        aiSession.setSessionId(request.getSessionId());
        aiSession.setQuestion(request.getQuestion());
        aiSession.setFileid(request.getFileid());
        aiSession.setTools(request.getTools());
        aiSession.setFirstResponseTime(request.getFirstResponseTime());
        if (request.getAgentType() == null) {
            aiSession.setAgentType("chat");
        } else {
            aiSession.setAgentType(request.getAgentType());
        }
        aiSession.setCreateTime(LocalDateTime.now());
        aiSession.setUpdateTime(LocalDateTime.now());

        this.save(aiSession);
        return aiSession;
    }

    /**
     * 回填AI回复，只更新请求中非null的字段
     */
    @Override
    public boolean updateAnswer(UpdateAnswerRequest request) {
        AiSession session = this.getById(request.getId());
        if (session != null) {
            session.setAnswer(request.getAnswer());
            session.setUpdateTime(LocalDateTime.now());
            if (request.getThinking() != null) {
                session.setThinking(request.getThinking());
            }
            if (request.getTools() != null) {
                session.setTools(request.getTools());
            }
            if (request.getReference() != null) {
                session.setReference(request.getReference());
            }
            if (request.getFirstResponseTime() != null) {
                session.setFirstResponseTime(request.getFirstResponseTime());
            }
            if (request.getTotalResponseTime() != null) {
                session.setTotalResponseTime(request.getTotalResponseTime());
            }
            if (request.getRecommend() != null) {
                session.setRecommend(request.getRecommend());
            }
            return this.updateById(session);
        }
        return false;
    }
}
