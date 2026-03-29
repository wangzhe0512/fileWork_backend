package com.fileproc.datafile.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.datafile.entity.DataSourceSchema;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 数据源 Schema Mapper
 */
@Mapper
public interface DataSourceSchemaMapper extends BaseMapper<DataSourceSchema> {

    /**
     * 按 dataFileId 查询全部 Sheet 的 Schema（按 sheet_index 升序）
     */
    @Select("SELECT * FROM data_source_schema WHERE data_file_id = #{dataFileId} ORDER BY sheet_index ASC")
    List<DataSourceSchema> selectByDataFileId(@Param("dataFileId") String dataFileId);

    /**
     * 删除指定文件的全部 Schema（重新解析时先清旧数据）
     */
    @Delete("DELETE FROM data_source_schema WHERE data_file_id = #{dataFileId}")
    int deleteByDataFileId(@Param("dataFileId") String dataFileId);

    /**
     * 查询指定文件是否已有解析结果
     */
    @Select("SELECT COUNT(1) FROM data_source_schema WHERE data_file_id = #{dataFileId}")
    int countByDataFileId(@Param("dataFileId") String dataFileId);
}
