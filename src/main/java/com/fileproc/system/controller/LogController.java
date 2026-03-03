package com.fileproc.system.controller;

import com.fileproc.common.PageResult;
import com.fileproc.common.R;
import com.fileproc.system.entity.SysLog;
import com.fileproc.system.service.LogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志接口
 * GET /system/logs
 */
@Tag(name = "系统-操作日志")
@RestController
@RequestMapping("/system/logs")
@RequiredArgsConstructor
public class LogController {

    private final LogService logService;

    @Operation(summary = "分页查询操作日志")
    @PreAuthorize("hasAuthority('system:log:list')")
    @GetMapping
    public R<PageResult<SysLog>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(logService.pageList(page, pageSize));
    }
}
