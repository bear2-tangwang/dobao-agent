package com.dobao.dobaobackend.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dobao.dobaobackend.auth.UserContext;
import com.dobao.dobaobackend.entity.record.pptx.AiPptInst;
import com.dobao.dobaobackend.entity.record.pptx.PptInstStatus;
import com.dobao.dobaobackend.mapper.AiPptInstMapper;
import com.dobao.dobaobackend.service.AiPptInstService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI PPT 实例服务实现
 */
@Slf4j
@Service
public class AiPptInstServiceImpl extends ServiceImpl<AiPptInstMapper, AiPptInst> implements AiPptInstService {

    /**
     * 未登录/无上下文时的兜底用户，与各表 user_id 列的 DEFAULT 'default' 保持一致
     */
    private static final String FALLBACK_USER_ID = "default";

    /**
     * 取归属用户。
     *
     * <p>注意本类的调用点既有请求线程（上传/查询）也有 SSE 线程（PPT 生成策略链），
     * 后者读不到 {@code UserContext}，因此这里只能作为兜底；
     * 真正需要精确归属的场景应在 Controller 层显式传入（见 {@code AgentController}）。
     */
    private String currentUserId() {
        return UserContext.getUserIdOrDefault(FALLBACK_USER_ID);
    }

    @Override
    public AiPptInst createInst(String conversationId, String query) {
        AiPptInst inst = AiPptInst.builder()
                .userId(currentUserId())
                .conversationId(conversationId)
                .query(query)
                .status(PptInstStatus.INIT.getCode())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        save(inst);
        log.info("创建PPT实例: id={}, conversationId={}, userId={}", inst.getId(), conversationId, inst.getUserId());
        return inst;
    }

    @Override
    public AiPptInst getLatestInst(String conversationId) {
        LambdaQueryWrapper<AiPptInst> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiPptInst::getConversationId, conversationId)
                // conversationId 由前端生成，按用户过滤避免读到别人的 PPT 实例
                .eq(AiPptInst::getUserId, currentUserId())
                .orderByDesc(AiPptInst::getCreateTime)
                .last("LIMIT 1");
        return getOne(wrapper);
    }

    @Override
    public List<AiPptInst> getInstsByConversationId(String conversationId) {
        LambdaQueryWrapper<AiPptInst> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiPptInst::getConversationId, conversationId)
                .eq(AiPptInst::getUserId, currentUserId())
                .orderByDesc(AiPptInst::getCreateTime);
        return list(wrapper);
    }

    @Override
    public List<AiPptInst> getCompletedInsts(String conversationId) {
        LambdaQueryWrapper<AiPptInst> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiPptInst::getConversationId, conversationId)
                .eq(AiPptInst::getUserId, currentUserId())
                .eq(AiPptInst::getStatus, PptInstStatus.SUCCESS.getCode())
                .orderByDesc(AiPptInst::getCreateTime);
        return list(wrapper);
    }

    @Override
    public boolean updateStatus(Long id, PptInstStatus status) {
        AiPptInst inst = new AiPptInst();
        inst.setId(id);
        inst.setStatus(status.getCode());
        inst.setUpdateTime(LocalDateTime.now());
        return updateById(inst);
    }

    @Override
    public boolean updateRequirement(Long id, String requirement, PptInstStatus status) {
        AiPptInst inst = new AiPptInst();
        inst.setId(id);
        inst.setRequirement(requirement);
        inst.setStatus(status.getCode());
        inst.setUpdateTime(LocalDateTime.now());
        return updateById(inst);
    }

    @Override
    public boolean updateSearchInfo(Long id, String searchInfo, PptInstStatus status) {
        AiPptInst inst = new AiPptInst();
        inst.setId(id);
        inst.setSearchInfo(searchInfo);
        inst.setStatus(status.getCode());
        inst.setUpdateTime(LocalDateTime.now());
        return updateById(inst);
    }

    @Override
    public boolean updateOutline(Long id, String outline, PptInstStatus status) {
        AiPptInst inst = new AiPptInst();
        inst.setId(id);
        inst.setOutline(outline);
        inst.setStatus(status.getCode());
        inst.setUpdateTime(LocalDateTime.now());
        return updateById(inst);
    }

    @Override
    public boolean updateTemplateCode(Long id, String templateCode, PptInstStatus status) {
        AiPptInst inst = new AiPptInst();
        inst.setId(id);
        inst.setTemplateCode(templateCode);
        inst.setStatus(status.getCode());
        inst.setUpdateTime(LocalDateTime.now());
        return updateById(inst);
    }

    @Override
    public boolean updatePptSchema(Long id, String pptSchema, PptInstStatus status) {
        AiPptInst inst = new AiPptInst();
        inst.setId(id);
        inst.setPptSchema(pptSchema);
        inst.setStatus(status.getCode());
        inst.setUpdateTime(LocalDateTime.now());
        return updateById(inst);
    }

    @Override
    public boolean updateFileUrl(Long id, String fileUrl, PptInstStatus status) {
        AiPptInst inst = new AiPptInst();
        inst.setId(id);
        inst.setFileUrl(fileUrl);
        inst.setStatus(status.getCode());
        inst.setUpdateTime(LocalDateTime.now());
        return updateById(inst);
    }

    @Override
    public boolean updateError(Long id, String errorMsg, PptInstStatus status) {
        AiPptInst inst = new AiPptInst();
        inst.setId(id);
        inst.setErrorMsg(errorMsg);
        inst.setStatus(status.getCode());
        inst.setUpdateTime(LocalDateTime.now());
        return updateById(inst);
    }
}
