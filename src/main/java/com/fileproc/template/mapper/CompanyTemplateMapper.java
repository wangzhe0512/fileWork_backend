package com.fileproc.template.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.template.entity.CompanyTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 企业子模板 Mapper
 */
@Mapper
public interface CompanyTemplateMapper extends BaseMapper<CompanyTemplate> {

    /**
     * 根据ID查询（含 file_path，绕过 select=false）
     */
    @Select("SELECT id, tenant_id, company_id, system_template_id, name, year, " +
            "source_report_id, file_path, file_size, status, created_at, updated_at " +
            "FROM company_template " +
            "WHERE id = #{id} AND deleted = 0")
    CompanyTemplate selectByIdWithFilePath(@Param("id") String id);

    /**
     * 查询企业下所有激活状态的子模板，按创建时间倒序
     */
    @Select("SELECT id, tenant_id, company_id, system_template_id, name, year, " +
            "source_report_id, file_path, file_size, status, created_at, updated_at " +
            "FROM company_template " +
            "WHERE company_id = #{companyId} AND tenant_id = #{tenantId} " +
            "AND status = 'active' AND deleted = 0 " +
            "ORDER BY created_at DESC")
    List<CompanyTemplate> selectActiveByCompany(@Param("companyId") String companyId,
                                                 @Param("tenantId") String tenantId);

    /**
     * 查询企业下最新的激活子模板（用于生成报告时的默认选择）
     */
    @Select("SELECT id, tenant_id, company_id, system_template_id, name, year, " +
            "source_report_id, file_path, file_size, status, created_at, updated_at " +
            "FROM company_template " +
            "WHERE company_id = #{companyId} AND tenant_id = #{tenantId} " +
            "AND status = 'active' AND deleted = 0 " +
            "ORDER BY created_at DESC LIMIT 1")
    CompanyTemplate selectLatestActiveByCompany(@Param("companyId") String companyId,
                                                 @Param("tenantId") String tenantId);

    /**
     * 根据企业ID逻辑删除所有子模板
     */
    @Update("UPDATE company_template SET deleted = 1 WHERE company_id = #{companyId} AND deleted = 0")
    int deleteByCompanyId(@Param("companyId") String companyId);

    /**
     * 根据企业ID查询所有子模板ID
     */
    @Select("SELECT id FROM company_template WHERE company_id = #{companyId} AND deleted = 0")
    List<String> selectIdsByCompanyId(@Param("companyId") String companyId);
}
