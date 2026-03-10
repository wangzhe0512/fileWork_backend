package com.fileproc.template.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.template.entity.SystemPlaceholder;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 系统占位符规则 Mapper
 */
@Mapper
public interface SystemPlaceholderMapper extends BaseMapper<SystemPlaceholder> {

    /**
     * 查询指定系统模板下的所有占位符规则，按 sort 排序
     */
    @Select("SELECT id, system_template_id, module_code, name, display_name, type, " +
            "data_source, source_sheet, source_field, chart_type, sort, description " +
            "FROM system_placeholder " +
            "WHERE system_template_id = #{systemTemplateId} AND deleted = 0 " +
            "ORDER BY sort ASC")
    List<SystemPlaceholder> selectByTemplateId(@Param("systemTemplateId") String systemTemplateId);

    /**
     * 查询指定模板下指定类型的占位符
     */
    @Select("SELECT id, system_template_id, module_code, name, display_name, type, " +
            "data_source, source_sheet, source_field, chart_type, sort, description " +
            "FROM system_placeholder " +
            "WHERE system_template_id = #{systemTemplateId} AND type = #{type} AND deleted = 0 " +
            "ORDER BY sort ASC")
    List<SystemPlaceholder> selectByTemplateIdAndType(@Param("systemTemplateId") String systemTemplateId,
                                                       @Param("type") String type);

    /**
     * 查询指定模板下指定模块的所有占位符
     */
    @Select("SELECT id, system_template_id, module_code, name, display_name, type, " +
            "data_source, source_sheet, source_field, chart_type, sort, description " +
            "FROM system_placeholder " +
            "WHERE system_template_id = #{systemTemplateId} AND module_code = #{moduleCode} AND deleted = 0 " +
            "ORDER BY sort ASC")
    List<SystemPlaceholder> selectByTemplateIdAndModuleCode(@Param("systemTemplateId") String systemTemplateId,
                                                             @Param("moduleCode") String moduleCode);

    /**
     * 物理删除指定系统模板下的所有占位符（重新解析时使用）
     */
    @Delete("DELETE FROM system_placeholder WHERE system_template_id = #{systemTemplateId}")
    int deleteByTemplateId(@Param("systemTemplateId") String systemTemplateId);
}
