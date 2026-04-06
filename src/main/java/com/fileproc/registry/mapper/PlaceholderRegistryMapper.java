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

    /**
     * 按 placeholder_name 或 display_name 查询有效规则（企业级优先系统级，用于获取 registryLevel 标签）
     * <p>
     * 支持两种查询方式：
     * 1. 按标准名（placeholder_name）查询，如"清单模板-数据表-B1"
     * 2. 按展示名（display_name）查询，如"企业名称"
     * 返回企业级和系统级均包含的结果，由业务层取优先级最高的那条。
     * </p>
     */
    @Select("SELECT * FROM placeholder_registry " +
            "WHERE deleted = 0 AND enabled = 1 " +
            "  AND (level = 'system' OR (level = 'company' AND company_id = #{companyId})) " +
            "  AND (placeholder_name = #{name} OR display_name = #{name}) " +
            "ORDER BY CASE level WHEN 'company' THEN 0 ELSE 1 END " +
            "LIMIT 1")
    PlaceholderRegistry selectEffectiveByName(@Param("name") String name, @Param("companyId") String companyId);

    /**
     * 按 ID 查询注册表条目（不过滤 enabled，用于"从库添加"场景）
     */
    @Select("SELECT * FROM placeholder_registry WHERE id = #{id} AND deleted = 0 LIMIT 1")
    PlaceholderRegistry selectActiveById(@Param("id") String id);
}
