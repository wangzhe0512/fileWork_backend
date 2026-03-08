package com.fileproc.template.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.template.entity.SystemModule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 系统模块 Mapper
 */
@Mapper
public interface SystemModuleMapper extends BaseMapper<SystemModule> {

    /**
     * 查询指定系统模板下的所有模块，按 sort 排序
     */
    @Select("SELECT id, system_template_id, name, code, description, sort " +
            "FROM system_module " +
            "WHERE system_template_id = #{systemTemplateId} AND deleted = 0 " +
            "ORDER BY sort ASC")
    List<SystemModule> selectByTemplateId(@Param("systemTemplateId") String systemTemplateId);
}
