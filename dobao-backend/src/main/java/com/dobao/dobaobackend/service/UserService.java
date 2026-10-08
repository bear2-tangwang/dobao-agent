package com.dobao.dobaobackend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dobao.dobaobackend.auth.GithubUser;
import com.dobao.dobaobackend.entity.DbUser;
import com.dobao.dobaobackend.mapper.DbUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 登录用户服务：GitHub 资料 → 本地用户（首次登录自动注册）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final String PROVIDER_GITHUB = "github";

    /**
     * 账号状态：1 正常，0 禁用
     */
    public static final int STATUS_ENABLED = 1;

    private final DbUserMapper userMapper;

    /**
     * 按第三方账号 upsert 用户，并刷新登录时间与资料。
     *
     * <p>幂等的关键在 {@code uk_provider_uid(provider, provider_uid)} 这个唯一索引：
     * 同一 GitHub 账号永远只会命中同一行。这里用"先查后写"而不是 SQL 的
     * {@code ON DUPLICATE KEY UPDATE}，是为了让 userId 的生成时机明确、
     * 也避免依赖 MySQL 方言。
     *
     * @param gu GitHub 用户资料
     * @return 本地用户（已含 userId）
     */
    @Transactional(rollbackFor = Exception.class)
    public DbUser upsertByGithub(GithubUser gu) {
        DbUser existing = findByProviderUid(PROVIDER_GITHUB, gu.providerUid());
        LocalDateTime now = LocalDateTime.now();

        if (existing == null) {
            DbUser user = new DbUser();
            user.setUserId(UUID.randomUUID().toString().replace("-", ""));
            user.setProvider(PROVIDER_GITHUB);
            user.setProviderUid(gu.providerUid());
            user.setLogin(gu.login());
            user.setNickname(gu.displayName());
            user.setEmail(gu.email());
            user.setAvatarUrl(gu.avatarUrl());
            user.setStatus(STATUS_ENABLED);
            user.setLastLoginTime(now);
            user.setCreateTime(now);
            user.setUpdateTime(now);
            userMapper.insert(user);
            log.info("首次登录，已创建本地用户: userId={}, github={}", user.getUserId(), gu.login());
            return user;
        }

        // 老用户：只刷新会变的资料字段，userId 与 create_time 永不变
        existing.setLogin(gu.login());
        existing.setNickname(gu.displayName());
        existing.setEmail(gu.email());
        existing.setAvatarUrl(gu.avatarUrl());
        existing.setLastLoginTime(now);
        existing.setUpdateTime(now);
        userMapper.updateById(existing);
        log.info("已有用户登录: userId={}, github={}", existing.getUserId(), gu.login());
        return existing;
    }

    public DbUser findByProviderUid(String provider, String providerUid) {
        return userMapper.selectOne(new LambdaQueryWrapper<DbUser>()
                .eq(DbUser::getProvider, provider)
                .eq(DbUser::getProviderUid, providerUid)
                .last("LIMIT 1"));
    }

    public DbUser findByUserId(String userId) {
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        return userMapper.selectOne(new LambdaQueryWrapper<DbUser>()
                .eq(DbUser::getUserId, userId)
                .last("LIMIT 1"));
    }

    /**
     * 账号是否可用（存在且未被停用）
     */
    public boolean isEnabled(DbUser user) {
        return user != null && user.getStatus() != null && user.getStatus() == STATUS_ENABLED;
    }
}
