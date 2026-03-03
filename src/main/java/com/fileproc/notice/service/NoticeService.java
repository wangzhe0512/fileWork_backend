package com.fileproc.notice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fileproc.auth.filter.UserPrincipal;
import com.fileproc.common.BizException;
import com.fileproc.common.PageResult;
import com.fileproc.common.TenantContext;
import com.fileproc.common.annotation.OperationLog;
import com.fileproc.common.enums.NoticeStatus;
import com.fileproc.notice.entity.Notice;
import com.fileproc.notice.entity.NoticeUser;
import com.fileproc.notice.mapper.NoticeMapper;
import com.fileproc.notice.mapper.NoticeUserMapper;
import com.fileproc.system.entity.SysUser;
import com.fileproc.system.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 消息通知 Service（管理端 + 用户端）
 */
@Service
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeMapper noticeMapper;
    private final NoticeUserMapper noticeUserMapper;
    private final UserMapper userMapper;

    // ===================== 管理端 =====================

    public PageResult<Notice> pageList(int page, int pageSize) {
        // P2-04：显式 tenantId 过滤保底
        String tenantId = TenantContext.getTenantId();
        LambdaQueryWrapper<Notice> wrapper = new LambdaQueryWrapper<Notice>()
                .eq(Notice::getTenantId, tenantId)
                .orderByDesc(Notice::getCreatedAt);
        IPage<Notice> result = noticeMapper.selectPage(new Page<>(page, pageSize), wrapper);
        return PageResult.of(result);
    }

    /**
     * 发布通知：创建 notice + 批量写 notice_user
     * P1-10：publishedByName 直接从 JWT Principal 中取 realName，避免事务内多余查库
     */
    @OperationLog(module = "消息通知", action = "发布通知")
    @Transactional
    public Notice createNotice(Notice notice) {
        String tenantId = TenantContext.getTenantId();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String publishedBy = "";
        String publishedByName = "";
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            publishedBy = principal.getUserId();
            // P1-10：从 JWT Claims 中取 realName，无需额外查库
            String realName = principal.getRealName();
            publishedByName = (realName != null && !realName.isBlank()) ? realName : publishedBy;
        }

        notice.setId(UUID.randomUUID().toString());
        notice.setTenantId(tenantId);
        notice.setPublishedBy(publishedBy);
        notice.setPublishedByName(publishedByName);
        notice.setReadCount(0);
        notice.setCreatedAt(LocalDateTime.now());
        noticeMapper.insert(notice);

        if (NoticeStatus.PUBLISHED.getCode().equals(notice.getStatus())) {
            // 查询投送目标用户
            List<SysUser> targetUsers = getTargetUsers(tenantId, notice);
            notice.setTotalCount(targetUsers.size());
            noticeMapper.updateById(notice);

            // 批量创建投送记录
            List<NoticeUser> noticeUsers = targetUsers.stream()
                    .map(user -> {
                        NoticeUser nu = new NoticeUser();
                        nu.setId(UUID.randomUUID().toString());
                        nu.setNoticeId(notice.getId());
                        nu.setTenantId(tenantId);
                        nu.setUserId(user.getId());
                        nu.setIsRead(false);
                        return nu;
                    })
                    .collect(Collectors.toList());
            if (!noticeUsers.isEmpty()) {
                noticeUserMapper.batchInsert(noticeUsers);
            }
        }
        return notice;
    }

    /**
     * P1：字段合并更新（只覆盖非 null 字段，避免覆盖已有数据为 null）
     */
    @OperationLog(module = "消息通知", action = "编辑通知")
    @Transactional(rollbackFor = Exception.class)
    public Notice updateNotice(String id, Notice input) {
        Notice notice = noticeMapper.selectById(id);
        if (notice == null) throw BizException.notFound("通知");
        if (input.getTitle() != null) notice.setTitle(input.getTitle());
        if (input.getContent() != null) notice.setContent(input.getContent());
        if (input.getStatus() != null) notice.setStatus(input.getStatus());
        if (input.getTargetType() != null) notice.setTargetType(input.getTargetType());
        if (input.getTargetRoleIds() != null) notice.setTargetRoleIds(input.getTargetRoleIds());
        notice.setUpdatedAt(LocalDateTime.now());
        noticeMapper.updateById(notice);
        return notice;
    }

    /**
     * P1：级联删除 notice_user 记录
     */
    @OperationLog(module = "消息通知", action = "删除通知")
    @Transactional
    public void deleteNotice(String id) {
        if (noticeMapper.selectById(id) == null) throw BizException.notFound("通知");
        // 先级联删除投送记录
        noticeUserMapper.deleteByNoticeId(id);
        noticeMapper.deleteById(id);
    }

    // ===================== 用户端 =====================

    public List<Map<String, Object>> getMyNotices() {
        String userId = getCurrentUserId();
        String tenantId = TenantContext.getTenantId();
        return noticeUserMapper.selectMyNotices(userId, tenantId);
    }

    public Map<String, Object> getUnreadCount() {
        String userId = getCurrentUserId();
        int count = noticeUserMapper.countUnread(userId);
        return Map.of("count", count);
    }

    /**
     * P1-TXN-04：markRead 加事务，保证 notice_user 和 notice.read_count 原子更新
     */
    @Transactional(rollbackFor = Exception.class)
    public void markRead(String noticeId) {
        String userId = getCurrentUserId();
        int affected = noticeUserMapper.markRead(noticeId, userId);
        // 仅在本次确实发生了标记（避免重复已读重复 +1）
        if (affected > 0) {
            noticeMapper.incrementReadCount(noticeId);
        }
    }

    public void markAllRead() {
        String userId = getCurrentUserId();
        noticeUserMapper.markAllRead(userId);
    }

    // ===================== 私有工具 =====================

    private List<SysUser> getTargetUsers(String tenantId, Notice notice) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getStatus, "active");
        if ("role".equals(notice.getTargetType()) &&
                notice.getTargetRoleIds() != null && !notice.getTargetRoleIds().isEmpty()) {
            wrapper.in(SysUser::getRoleId, notice.getTargetRoleIds());
        }
        return userMapper.selectList(wrapper);
    }

    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            return principal.getUserId();
        }
        throw BizException.unauthorized("未登录");
    }
}
