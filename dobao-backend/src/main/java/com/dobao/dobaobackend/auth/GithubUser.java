package com.dobao.dobaobackend.auth;

/**
 * 从 GitHub 拿到的用户资料（只保留本项目需要的字段）。
 *
 * @param providerUid GitHub 用户 id，注意 GitHub 返回的是数字，这里统一转字符串存
 * @param login       GitHub 登录名
 * @param name        GitHub 个人资料里的显示名，可能为空
 * @param email       邮箱，可能为空（用户在 GitHub 上把邮箱设为私密）
 * @param avatarUrl   头像地址
 */
public record GithubUser(
        String providerUid,
        String login,
        String name,
        String email,
        String avatarUrl) {

    /**
     * 展示名：优先用 GitHub 的 name，为空时回退到 login
     */
    public String displayName() {
        return (name == null || name.isBlank()) ? login : name;
    }
}
