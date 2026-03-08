package com.fileproc.template.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.template.entity.CompanyTemplatePlaceholder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 企业子模板占位符状态 Mapper
 */
@Mapper
public interface CompanyTemplatePlaceholderMapper extends BaseMapper<CompanyTemplatePlaceholder> {

    /**
     * 根据子模板ID查询所有占位符状态
     */
    @Select("SELECT * FROM company_template_placeholder WHERE company_template_id = #{templateId} ORDER BY created_at")
    List<CompanyTemplatePlaceholder> selectByTemplateId(@Param("templateId") String templateId);

    /**
     * 根据子模板ID和占位符名称查询
     */
    @Select("SELECT * FROM company_template_placeholder WHERE company_template_id = #{templateId} AND placeholder_name = #{name}")
    CompanyTemplatePlaceholder selectByTemplateIdAndName(@Param("templateId") String templateId, @Param("name") String name);

    /**
     * 根据子模板ID和状态查询
     */
    @Select("SELECT * FROM company_template_placeholder WHERE company_template_id = #{templateId} AND status = #{status}")
    List<CompanyTemplatePlaceholder> selectByTemplateIdAndStatus(@Param("templateId") String templateId, @Param("status") String status);

    /**
     * 根据子模板ID列表批量删除占位符状态
     */
    @Update("<script>" +
            "UPDATE company_template_placeholder SET deleted = 1 " +
            "WHERE company_template_id IN " +
            "<foreach collection='templateIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int deleteByTemplateIds(@Param("templateIds") List<String> templateIds);
}
