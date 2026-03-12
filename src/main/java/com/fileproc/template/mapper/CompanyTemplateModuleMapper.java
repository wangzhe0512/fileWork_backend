package com.fileproc.template.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.template.entity.CompanyTemplateModule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 企业子模板模块 Mapper
 */
@Mapper
public interface CompanyTemplateModuleMapper extends BaseMapper<CompanyTemplateModule> {

    /**
     * 根据子模板ID查询所有模块，按sort和created_at排序
     */
    @Select("SELECT * FROM company_template_module " +
            "WHERE company_template_id = #{templateId} AND deleted = 0 " +
            "ORDER BY sort ASC, created_at ASC")
    List<CompanyTemplateModule> selectByTemplateId(@Param("templateId") String templateId);

    /**
     * 根据子模板ID和模块code查询
     */
    @Select("SELECT * FROM company_template_module " +
            "WHERE company_template_id = #{templateId} AND code = #{code} AND deleted = 0")
    CompanyTemplateModule selectByTemplateIdAndCode(@Param("templateId") String templateId,
                                                     @Param("code") String code);

    /**
     * 批量查询模块（用于同步时的批量查找）
     */
    @Select("<script>" +
            "SELECT * FROM company_template_module " +
            "WHERE company_template_id = #{templateId} AND deleted = 0 " +
            "AND code IN " +
            "<foreach collection='codes' item='code' open='(' separator=',' close=')'>#{code}</foreach> " +
            "ORDER BY sort ASC, created_at ASC" +
            "</script>")
    List<CompanyTemplateModule> selectByTemplateIdAndCodes(@Param("templateId") String templateId,
                                                            @Param("codes") List<String> codes);
}
