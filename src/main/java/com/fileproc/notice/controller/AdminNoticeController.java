package com.fileproc.notice.controller;

import com.fileproc.common.PageResult;
import com.fileproc.common.R;
import com.fileproc.notice.entity.Notice;
import com.fileproc.notice.service.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端通知接口
 * GET    /system/notices        - 分页查询
 * POST   /system/notices        - 发布通知
 * PUT    /system/notices/{id}   - 编辑通知
 * DELETE /system/notices/{id}   - 删除通知
 */
@Tag(name = "通知-管理端")
@Validated
@RestController
@RequestMapping("/system/notices")
@RequiredArgsConstructor
public class AdminNoticeController {

    private final NoticeService noticeService;

    @Operation(summary = "分页查询通知列表")
    @PreAuthorize("hasAuthority('system:notice:manage')")
    @GetMapping
    public R<PageResult<Notice>> list(
            @RequestParam(defaultValue = "1") int page,
            @Max(value = 100, message = "每页最多100条") @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(noticeService.pageList(page, pageSize));
    }

    @Operation(summary = "发布通知")
    @PreAuthorize("hasAuthority('system:notice:manage')")
    @PostMapping
    public R<Notice> create(@Valid @RequestBody Notice notice) {
        return R.ok(noticeService.createNotice(notice));
    }

    @Operation(summary = "编辑通知")
    @PreAuthorize("hasAuthority('system:notice:manage')")
    @PutMapping("/{id}")
    public R<Notice> update(@PathVariable String id, @RequestBody Notice notice) {
        return R.ok(noticeService.updateNotice(id, notice));
    }

    @Operation(summary = "删除通知")
    @PreAuthorize("hasAuthority('system:notice:manage')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        noticeService.deleteNotice(id);
        return R.ok();
    }
}
