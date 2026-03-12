package com.fileproc.template.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.template.entity.CompanyTemplatePlaceholder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 企业子模板占位符状态 Mapper
 */
@Mapper
public interface CompanyTemplatePlaceholderMapper extends BaseMapper<CompanyTemplatePlaceholder> {

    /**
     * 根据子模板ID查询所有占位符状态
     */
    @Select("SELECT * FROM company_template_placeholder WHERE company_template_id = #{templateId} AND deleted = 0 ORDER BY created_at")
    List<CompanyTemplatePlaceholder> selectByTemplateId(@Param("templateId") String templateId);

    /**
     * 根据子模板ID和占位符名称查询
     */
    @Select("SELECT * FROM company_template_placeholder WHERE company_template_id = #{templateId} AND placeholder_name = #{name} AND deleted = 0")
    CompanyTemplatePlaceholder selectByTemplateIdAndName(@Param("templateId") String templateId, @Param("name") String name);

    /**
     * 根据子模板ID和状态查询
     */
    @Select("SELECT * FROM company_template_placeholder WHERE company_template_id = #{templateId} AND status = #{status} AND deleted = 0")
    List<CompanyTemplatePlaceholder> selectByTemplateIdAndStatus(@Param("templateId") String templateId, @Param("status") String status);

    /**
     * 根据子模板ID和状态统计数量
     */
    @Select("SELECT COUNT(*) FROM company_template_placeholder WHERE company_template_id = #{templateId} AND status = #{status} AND deleted = 0")
    int countByTemplateIdAndStatus(@Param("templateId") String templateId, @Param("status") String status);

    /**
     * 根据子模板ID列表批量删除占位符状态
     */
    @Update("<script>" +
            "UPDATE company_template_placeholder SET deleted = 1 " +
            "WHERE company_template_id IN " +
            "<foreach collection='templateIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int deleteByTemplateIds(@Param("templateIds") List<String> templateIds);

    /**
     * 根据模块ID查询占位符列表
     */
    @Select("SELECT * FROM company_template_placeholder " +
            "WHERE module_id = #{moduleId} AND deleted = 0 " +
            "ORDER BY sort ASC, created_at ASC")
    List<CompanyTemplatePlaceholder> selectByModuleId(@Param("moduleId") String moduleId);

    /**
     * 根据子模板ID查询所有占位符（包含模块信息，用于同步）
     */
    @Select("SELECT p.*, m.code as module_code FROM company_template_placeholder p " +
            "LEFT JOIN company_template_module m ON p.module_id = m.id " +
            "WHERE p.company_template_id = #{templateId} AND p.deleted = 0 " +
            "ORDER BY p.sort ASC, p.created_at ASC")
    List<Map<String, Object>> selectWithModuleByTemplateId(@Param("templateId") String templateId);

    /**
     * 根据子模板ID和占位符名称查询
     */
    @Select("SELECT p.* FROM company_template_placeholder p " +
            "LEFT JOIN company_template_module m ON p.module_id = m.id " +
            "WHERE p.company_template_id = #{templateId} " +
            "AND m.code = #{moduleCode} " +
            "AND p.placeholder_name = #{placeholderName} " +
            "AND p.deleted = 0")
    CompanyTemplatePlaceholder selectByTemplateIdAndModuleCodeAndName(@Param("templateId") String templateId,
                                                                       @Param("moduleCode") String moduleCode,
                                                                       @Param("placeholderName") String placeholderName);

    /**
     * 批量更新占位符元数据（用于同步）
     */
    @Update("<script>" +
            "<foreach collection='placeholders' item='item' separator=';'>" +
            "UPDATE company_template_placeholder " +
            "SET name = #{item.name}, " +
            "type = #{item.type}, " +
            "data_source = #{item.dataSource}, " +
            "source_sheet = #{item.sourceSheet}, " +
            "source_field = #{item.sourceField}, " +
            "description = #{item.description}, " +
            "sort = #{item.sort}, " +
            "updated_at = NOW() " +
            "WHERE id = #{item.id}" +
            "</foreach>" +
            "</script>")
    int batchUpdateMetadata(@Param("placeholders") List<CompanyTemplatePlaceholder> placeholders);
}
