package com.flashmart.modules.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashmart.modules.auth.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
