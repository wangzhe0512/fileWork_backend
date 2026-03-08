package com.fileproc.template.controller;

import com.fileproc.common.R;
import com.fileproc.template.entity.SystemModule;
import com.fileproc.template.entity.SystemPlaceholder;
import com.fileproc.template.entity.SystemTemplate;
import com.fileproc.template.service.SystemTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 系统标准模板管理接口
 * <p>
 * 接口列表（context-path=/api，以下为相对路径）：
 * POST /admin/system-template/init          — 上传三件套并解析初始化
 * GET  /admin/system-template/active        — 获取当前激活模板详情
 * GET  /admin/system-template/placeholders  — 占位符规则列表
 * GET  /admin/system-template/modules       — 模块列表
 * GET  /admin/system-template/list          — 获取所有模板列表
 * GET  /admin/system-template/{id}          — 获取模板详情
 * POST /admin/system-template/{id}/set-active — 设置激活模板
 * </p>
 */
@RestController
@RequestMapping("/admin/system-template")
@RequiredArgsConstructor
public class SystemTemplateController {

    private final SystemTemplateService systemTemplateService;

    /**
     * 上传标准模板三件套并触发解析
     * 管理员操作，上传后自动归档旧模板
     */
    @PostMapping(value = "/init", consumes = "multipart/form-data")
    public R<SystemTemplate> init(
            @RequestPart(value = "wordFile", required = false) MultipartFile wordFile,
            @RequestPart(value = "listFile", required = false) MultipartFile listFile,
            @RequestPart(value = "bvdFile", required = false) MultipartFile bvdFile,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "description", required = false) String description) {
        SystemTemplate template = systemTemplateService.uploadAndInit(wordFile, listFile, bvdFile, name, description);
        return R.ok("系统模板初始化成功", template);
    }

    /**
     * 获取当前激活的系统模板详情（不含文件路径）
     */
    @GetMapping("/active")
    public R<SystemTemplate> getActive() {
        SystemTemplate template = systemTemplateService.getActive();
        return R.ok(template);
    }

    /**
     * 查询指定系统模板的占位符规则列表
     * 支持按 moduleId 过滤
     */
    @GetMapping("/placeholders")
    public R<List<SystemPlaceholder>> listPlaceholders(
            @RequestParam(value = "templateId", required = false) String templateId,
            @RequestParam(value = "systemTemplateId", required = false) String systemTemplateId,
            @RequestParam(value = "moduleId", required = false) String moduleId) {
        // 优先使用 templateId，兼容 systemTemplateId
        String targetTemplateId = templateId != null ? templateId : systemTemplateId;
        List<SystemPlaceholder> list;
        if (targetTemplateId != null && moduleId != null) {
            // 同时指定了模板ID和模块ID，按模块过滤
            list = systemTemplateService.listPlaceholdersByModule(targetTemplateId, moduleId);
        } else if (targetTemplateId != null) {
            // 只指定了模板ID
            list = systemTemplateService.listPlaceholders(targetTemplateId);
        } else {
            // 未指定模板ID，返回当前激活模板的占位符
            list = systemTemplateService.listActivePlaceholders();
        }
        return R.ok(list);
    }

    /**
     * 查询指定系统模板的模块列表
     * 兼容 templateId 和 systemTemplateId 参数名
     */
    @GetMapping("/modules")
    public R<List<SystemModule>> listModules(
            @RequestParam(value = "templateId", required = false) String templateId,
            @RequestParam(value = "systemTemplateId", required = false) String systemTemplateId) {
        // 优先使用 templateId，兼容 systemTemplateId
        String targetId = templateId != null ? templateId : systemTemplateId;
        if (targetId == null) {
            // 未指定模板ID，使用当前激活模板
            SystemTemplate active = systemTemplateService.getActive();
            targetId = active != null ? active.getId() : null;
        }
        if (targetId == null) return R.ok(List.of());
        return R.ok(systemTemplateService.listModules(targetId));
    }

    /**
     * 查询所有系统模板列表（含 active 和 inactive），用于管理界面
     */
    @GetMapping("/list")
    public R<List<SystemTemplate>> listAll() {
        List<SystemTemplate> list = systemTemplateService.listAllTemplates();
        return R.ok(list);
    }

    /**
     * 获取指定模板详情
     */
    @GetMapping("/{id}")
    public R<SystemTemplate> getById(@PathVariable String id) {
        SystemTemplate template = systemTemplateService.getById(id);
        return R.ok(template);
    }

    /**
     * 设置指定模板为激活状态
     * 原激活模板会自动变为 inactive
     */
    @PostMapping("/{id}/set-active")
    public R<SystemTemplate> setActive(@PathVariable String id) {
        SystemTemplate template = systemTemplateService.setActive(id);
        return R.ok("设置激活模板成功", template);
    }
}
