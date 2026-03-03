package com.fileproc.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.system.entity.SysLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LogMapper extends BaseMapper<SysLog> {
}
