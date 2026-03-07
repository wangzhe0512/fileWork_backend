package com.fileproc.notice.controller;

import com.fileproc.common.R;
import com.fileproc.notice.service.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户端通知接口
 * GET  /notices/mine         - 获取我的通知列表
 * GET  /notices/unread-count - 获取未读数
 * POST /notices/{id}/read    - 标记单条已读
 * POST /notices/read-all     - 全部已读
 */
@Tag(name = "通知-用户端")
@RestController
@RequestMapping("/notices")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    @Operation(summary = "获取我的通知列表")
    @PreAuthorize("hasAuthority('notice:read')")
    @GetMapping("/mine")
    public R<Map<String, Object>> myNotices() {
        return R.ok(Map.of("list", noticeService.getMyNotices()));
    }

    @Operation(summary = "获取未读通知数")
    @PreAuthorize("hasAuthority('notice:read')")
    @GetMapping("/unread-count")
    public R<Map<String, Object>> unreadCount() {
        return R.ok(noticeService.getUnreadCount());
    }

    @Operation(summary = "标记单条已读")
    @PreAuthorize("hasAuthority('notice:read')")
    @PostMapping("/{id}/read")
    public R<Void> markRead(@PathVariable String id) {
        noticeService.markRead(id);
        return R.ok();
    }

    @Operation(summary = "全部已读")
    @PreAuthorize("hasAuthority('notice:read')")
    @PostMapping("/read-all")
    public R<Void> markAllRead() {
        noticeService.markAllRead();
        return R.ok();
    }
}
