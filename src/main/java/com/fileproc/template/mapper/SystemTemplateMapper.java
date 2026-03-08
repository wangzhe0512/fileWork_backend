package com.fileproc.template.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.template.entity.SystemTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 系统标准模板 Mapper
 */
@Mapper
public interface SystemTemplateMapper extends BaseMapper<SystemTemplate> {

    /**
     * 查询当前激活的系统模板（含全部文件路径字段，绕过 select=false）
     */
    @Select("SELECT id, name, version, word_file_path, list_excel_path, bvd_excel_path, " +
            "status, description, created_at " +
            "FROM system_template " +
            "WHERE status = 'active' AND deleted = 0 " +
            "ORDER BY created_at DESC LIMIT 1")
    SystemTemplate selectActiveWithAllPaths();

    /**
     * 根据ID查询（含全部文件路径字段）
     */
    @Select("SELECT id, name, version, word_file_path, list_excel_path, bvd_excel_path, " +
            "status, description, created_at " +
            "FROM system_template " +
            "WHERE id = #{id} AND deleted = 0")
    SystemTemplate selectByIdWithPaths(String id);

    /**
     * 查询所有未删除的系统模板（不含文件路径），按创建时间倒序
     */
    @Select("SELECT id, name, version, status, description, created_at " +
            "FROM system_template " +
            "WHERE deleted = 0 " +
            "ORDER BY created_at DESC")
    List<SystemTemplate> selectAllTemplates();
}
