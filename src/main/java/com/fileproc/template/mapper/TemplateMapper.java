package com.fileproc.template.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.template.entity.Template;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TemplateMapper extends BaseMapper<Template> {

    /**
     * 查询包含 file_path 字段的模板（绕过 @TableField(select=false)）
     */
    @Select("SELECT id, tenant_id, company_id, name, year, status, description, file_path, created_at " +
            "FROM template " +
            "WHERE company_id = #{companyId} AND year = #{year} AND status = 'active' " +
            "AND tenant_id = #{tenantId} AND deleted = 0 " +
            "ORDER BY created_at DESC LIMIT 1")
    Template selectActiveWithFilePath(@Param("companyId") String companyId,
                                      @Param("year") int year,
                                      @Param("tenantId") String tenantId);
}
