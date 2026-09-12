package com.dobao.dobaobackend.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.dobao.dobaobackend.entity.AiSession;
import com.dobao.dobaobackend.entity.vo.SaveQuestionRequest;
import com.dobao.dobaobackend.entity.vo.UpdateAnswerRequest;

import java.util.List;

/**
 * AI会话服务接口
 */
public interface AiSessionService extends IService<AiSession> {

    /**
     * 根据会话ID查询最近的对话记录
     * @param sessionId 会话ID
     * @param maxRecords 最大记录数
     * @return 对话记录列表，按时间倒序排列
     */
    List<AiSession> findRecentBySessionId(String sessionId, int maxRecords);

    /**
     * 保存用户问题
     * @param request 保存请求
     * @return 保存的会话记录
     */
    AiSession saveQuestion(SaveQuestionRequest request);

    /**
     * 更新AI回复
     * @param request 更新请求，只更新非null的字段
     * @return 更新的会话记录数量
     */
    boolean updateAnswer(UpdateAnswerRequest request);
}
