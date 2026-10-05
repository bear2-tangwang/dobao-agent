package com.dobao.dobaobackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dobao.dobaobackend.entity.DbUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 登录用户 Mapper
 */
@Mapper
public interface DbUserMapper extends BaseMapper<DbUser> {
}
