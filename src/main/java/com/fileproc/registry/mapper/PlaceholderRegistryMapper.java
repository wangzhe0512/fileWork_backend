package com.fileproc.registry.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.registry.entity.PlaceholderRegistry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 占位符注册表 Mapper
 */
@Mapper
public interface PlaceholderRegistryMapper extends BaseMapper<PlaceholderRegistry> {

    /**
     * 查询系统级规则（level=system，按 sort 升序）
     */
    @Select("SELECT * FROM placeholder_registry WHERE level = 'system' AND enabled = 1 AND deleted = 0 ORDER BY sort ASC")
    List<PlaceholderRegistry> selectSystemEntries();

    /**
     * 查询指定企业的企业级规则（按 sort 升序）
     */
    @Select("SELECT * FROM placeholder_registry WHERE level = 'company' AND company_id = #{companyId} AND enabled = 1 AND deleted = 0 ORDER BY sort ASC")
    List<PlaceholderRegistry> selectCompanyEntries(@Param("companyId") String companyId);

    /**
     * 查询系统级 + 企业级所有规则（合并查询，用于 getEffectiveRegistry）
     */
    @Select("SELECT * FROM placeholder_registry " +
            "WHERE deleted = 0 AND enabled = 1 " +
            "  AND (level = 'system' OR (level = 'company' AND company_id = #{companyId})) " +
            "ORDER BY sort ASC")
    List<PlaceholderRegistry> selectEffectiveEntries(@Param("companyId") String companyId);

    /**
     * 按 placeholder_name 查询系统级条目（用于校验重复）
     */
    @Select("SELECT * FROM placeholder_registry WHERE level = 'system' AND placeholder_name = #{name} AND deleted = 0 LIMIT 1")
    PlaceholderRegistry selectSystemByName(@Param("name") String name);

    /**
     * 按 placeholder_name + company_id 查询企业级条目（用于校验重复）
     */
    @Select("SELECT * FROM placeholder_registry WHERE level = 'company' AND company_id = #{companyId} AND placeholder_name = #{name} AND deleted = 0 LIMIT 1")
    PlaceholderRegistry selectCompanyByName(@Param("companyId") String companyId, @Param("name") String name);
}
