package com.fileproc.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.report.entity.Report;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReportMapper extends BaseMapper<Report> {

    /**
     * 按 ID 查询 file_path（绕过 @TableField(select=false)）
     */
    @Select("SELECT file_path FROM report WHERE id = #{id} AND deleted = 0")
    String selectFilePathById(@Param("id") String id);

    /**
     * P1：统计指定 companyId+year 下 editing 状态的报告数量
     * （用于删除模板前关联检查：若有编辑中报告则不允许删除该模板）
     */
    @Select("SELECT COUNT(*) FROM report WHERE company_id = #{companyId} AND year = #{year} AND status = 'editing' AND deleted = 0")
    long countEditingByCompanyAndYear(@Param("companyId") String companyId, @Param("year") int year);
}
