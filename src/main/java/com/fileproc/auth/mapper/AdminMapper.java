package com.fileproc.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.auth.entity.SysAdmin;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminMapper extends BaseMapper<SysAdmin> {
}
