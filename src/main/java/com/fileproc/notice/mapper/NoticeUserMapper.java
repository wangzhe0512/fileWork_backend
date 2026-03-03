package com.fileproc.notice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fileproc.notice.entity.NoticeUser;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface NoticeUserMapper extends BaseMapper<NoticeUser> {

    @Select("SELECT COUNT(*) FROM notice_user WHERE user_id = #{userId} AND is_read = 0")
    int countUnread(@Param("userId") String userId);

    @Select("""
        SELECT nu.*, n.title, n.content, n.created_at AS notice_created_at
        FROM notice_user nu
        JOIN notice n ON nu.notice_id = n.id
        WHERE nu.user_id = #{userId} AND nu.tenant_id = #{tenantId}
        ORDER BY nu.is_read ASC, n.created_at DESC
        """)
    List<java.util.Map<String, Object>> selectMyNotices(@Param("userId") String userId,
                                                        @Param("tenantId") String tenantId);

    @Update("UPDATE notice_user SET is_read = 1, read_at = NOW() WHERE notice_id = #{noticeId} AND user_id = #{userId} AND is_read = 0")
    int markRead(@Param("noticeId") String noticeId, @Param("userId") String userId);

    @Update("UPDATE notice_user SET is_read = 1, read_at = NOW() WHERE user_id = #{userId} AND is_read = 0")
    void markAllRead(@Param("userId") String userId);

    /**
     * 批量插入通知接收记录（替代循环单条 INSERT）
     */
    @Insert("<script>" +
            "INSERT INTO notice_user (id, notice_id, tenant_id, user_id, is_read) VALUES " +
            "<foreach collection='list' item='nu' separator=','>" +
            "(#{nu.id}, #{nu.noticeId}, #{nu.tenantId}, #{nu.userId}, #{nu.isRead})" +
            "</foreach>" +
            "</script>")
    void batchInsert(@Param("list") List<NoticeUser> list);

    /**
     * P2-QUAL-06：级联删除通知投送记录（删除通知时调用），@Delete 注解匹配 SQL 语义
     */
    @Delete("DELETE FROM notice_user WHERE notice_id = #{noticeId}")
    int deleteByNoticeId(@Param("noticeId") String noticeId);
}
