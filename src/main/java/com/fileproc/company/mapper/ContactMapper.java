package com.fileproc.company.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.company.entity.Contact;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ContactMapper extends BaseMapper<Contact> {

    // P2-08：明确指定列名，排除 deleted=1 的软删除记录
    @Select("SELECT id, company_id, tenant_id, name, phone, email, position " +
            "FROM company_contact WHERE company_id = #{companyId} AND deleted = 0")
    List<Contact> selectByCompanyId(@Param("companyId") String companyId);

    @Delete("DELETE FROM company_contact WHERE company_id = #{companyId}")
    void deleteByCompanyId(@Param("companyId") String companyId);

    // P2-09：批量插入联系人
    @Insert("<script>" +
            "INSERT INTO company_contact (id, company_id, tenant_id, name, phone, email, position) VALUES " +
            "<foreach collection='list' item='c' separator=','>" +
            "(#{c.id}, #{c.companyId}, #{c.tenantId}, #{c.name}, #{c.phone}, #{c.email}, #{c.position})" +
            "</foreach>" +
            "</script>")
    void batchInsert(@Param("list") List<Contact> list);
}
