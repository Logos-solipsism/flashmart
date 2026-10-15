package com.flashmart.modules.cache.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashmart.modules.cache.entity.CacheSyncTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CacheSyncTaskMapper extends BaseMapper<CacheSyncTask> {
}
