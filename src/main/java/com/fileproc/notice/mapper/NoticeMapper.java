package com.fileproc.notice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.notice.entity.Notice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NoticeMapper extends BaseMapper<Notice> {

    /**
     * P1：原子递增 readCount，避免并发读-改-写竞态
     */
    @Update("UPDATE notice SET read_count = read_count + 1 WHERE id = #{noticeId} AND deleted = 0")
    int incrementReadCount(@Param("noticeId") String noticeId);
}
