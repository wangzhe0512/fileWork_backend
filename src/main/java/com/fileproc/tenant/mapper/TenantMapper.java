package com.fileproc.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.tenant.entity.SysTenant;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantMapper extends BaseMapper<SysTenant> {
}
