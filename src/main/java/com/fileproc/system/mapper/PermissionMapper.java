package com.fileproc.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.system.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PermissionMapper extends BaseMapper<SysPermission> {
}
