package com.fileproc.system.controller;

import com.fileproc.common.R;
import com.fileproc.system.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 首页统计接口
 * GET /stats
 */
@Tag(name = "首页统计")
@RestController
@RequestMapping("/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @Operation(summary = "获取统计数据")
    @PreAuthorize("hasAuthority('stats:view')")
    @GetMapping
    public R<Map<String, Object>> stats() {
        return R.ok(statsService.getStats());
    }
}
