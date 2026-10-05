package com.dobao.dobaobackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录用户实体，对应 {@code db_user}。
 *
 * <p>设计要点：{@link #userId} 是本地业务主键（UUID 去横线），所有业务表的
 * {@code user_id} 存的都是它；{@link #providerUid} 才是第三方（GitHub）的账号 ID，
 * 两者分开是为了将来接 Gitee / 微信登录时不必改动任何业务表。
 */
@Data
@TableName("db_user")
public class DbUser {

    /**
     * 自增主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 业务用户 ID（UUID，无横线），业务表的 user_id 存这个值
     */
    @TableField("user_id")
    private String userId;

    /**
     * 登录来源，当前固定 github
     */
    @TableField("provider")
    private String provider;

    /**
     * 第三方账号唯一 ID（GitHub 用户 id，存字符串）
     */
    @TableField("provider_uid")
    private String providerUid;

    /**
     * GitHub 登录名
     */
    @TableField("login")
    private String login;

    /**
     * 显示名（GitHub name，为空时回退 login）
     */
    @TableField("nickname")
    private String nickname;

    /**
     * 邮箱（GitHub 用户把邮箱设为私密时可能为 null）
     */
    @TableField("email")
    private String email;

    /**
     * 头像地址
     */
    @TableField("avatar_url")
    private String avatarUrl;

    /**
     * 1 正常，0 禁用
     */
    @TableField("status")
    private Integer status;

    /**
     * 最近一次登录时间
     */
    @TableField("last_login_time")
    private LocalDateTime lastLoginTime;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
