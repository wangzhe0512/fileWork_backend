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
            "source_report_id, file_path, file_size, status, is_current, created_at, updated_at " +
            "FROM company_template " +
            "WHERE id = #{id} AND deleted = 0")
    CompanyTemplate selectByIdWithFilePath(@Param("id") String id);

    /**
     * 查询企业下所有激活状态的子模板，按创建时间倒序
     */
    @Select("SELECT id, tenant_id, company_id, system_template_id, name, year, " +
            "source_report_id, file_size, status, is_current, created_at, updated_at " +
            "FROM company_template " +
            "WHERE company_id = #{companyId} AND tenant_id = #{tenantId} " +
            "AND status = 'active' AND deleted = 0 " +
            "ORDER BY created_at DESC")
    List<CompanyTemplate> selectActiveByCompany(@Param("companyId") String companyId,
                                                 @Param("tenantId") String tenantId);

    /**
     * 查询企业下最新的激活子模板（用于生成报告时的默认选择）
     * @deprecated 请使用 selectCurrentByCompanyAndYear 按 is_current 查询
     */
    @Deprecated
    @Select("SELECT id, tenant_id, company_id, system_template_id, name, year, " +
            "source_report_id, file_path, file_size, status, is_current, created_at, updated_at " +
            "FROM company_template " +
            "WHERE company_id = #{companyId} AND tenant_id = #{tenantId} " +
            "AND status = 'active' AND deleted = 0 " +
            "ORDER BY created_at DESC LIMIT 1")
    CompanyTemplate selectLatestActiveByCompany(@Param("companyId") String companyId,
                                                 @Param("tenantId") String tenantId);

    /**
     * 查询企业指定年度的当前使用子模板（含 file_path，用于生成报告）
     */
    @Select("SELECT id, tenant_id, company_id, system_template_id, name, year, " +
            "source_report_id, file_path, file_size, status, is_current, created_at, updated_at " +
            "FROM company_template " +
            "WHERE company_id = #{companyId} AND tenant_id = #{tenantId} AND year = #{year} " +
            "AND is_current = 1 AND status = 'active' AND deleted = 0 " +
            "LIMIT 1")
    CompanyTemplate selectCurrentByCompanyAndYear(@Param("companyId") String companyId,
                                                   @Param("tenantId") String tenantId,
                                                   @Param("year") Integer year);

    /**
     * 将企业指定年度的其他模板 is_current 设为 false（用于切换当前使用模板）
     */
    @Update("UPDATE company_template " +
            "SET is_current = 0, updated_at = NOW() " +
            "WHERE company_id = #{companyId} AND tenant_id = #{tenantId} AND year = #{year} " +
            "AND is_current = 1 AND deleted = 0")
    int clearCurrentByCompanyAndYear(@Param("companyId") String companyId,
                                      @Param("tenantId") String tenantId,
                                      @Param("year") Integer year);

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
