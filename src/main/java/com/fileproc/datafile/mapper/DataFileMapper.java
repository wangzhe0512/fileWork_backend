package com.fileproc.datafile.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.datafile.entity.DataFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DataFileMapper extends BaseMapper<DataFile> {

    @Select("SELECT COUNT(*) FROM data_file WHERE company_id = #{companyId} AND year = #{year} AND deleted = 0")
    int countByCompanyAndYear(@Param("companyId") String companyId, @Param("year") int year);

    /**
     * 查询包含 file_path 字段的数据文件（绕过 @TableField(select=false)）
     */
    @Select("SELECT id, tenant_id, company_id, name, type, year, size, file_path, upload_at " +
            "FROM data_file " +
            "WHERE company_id = #{companyId} AND year = #{year} AND deleted = 0")
    List<DataFile> selectWithFilePathByCompanyAndYear(@Param("companyId") String companyId,
                                                      @Param("year") int year);

    /**
     * 按 ID 查询 file_path
     */
    @Select("SELECT file_path FROM data_file WHERE id = #{id} AND deleted = 0")
    String selectFilePathById(@Param("id") String id);
}
