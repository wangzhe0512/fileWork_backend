package com.fileproc.template.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fileproc.common.BizException;
import com.fileproc.template.entity.SystemModule;
import com.fileproc.template.entity.SystemPlaceholder;
import com.fileproc.template.entity.SystemTemplate;
import com.fileproc.template.mapper.SystemModuleMapper;
import com.fileproc.template.mapper.SystemPlaceholderMapper;
import com.fileproc.template.mapper.SystemTemplateMapper;
import com.fileproc.common.util.FileUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 系统标准模板管理 Service
 * <p>
 * 负责：
 * 1. 上传三件套（Word + 清单Excel + BVD Excel）并触发解析
 * 2. 查询当前激活模板及占位符列表
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemTemplateService {

    private final SystemTemplateMapper systemTemplateMapper;
    private final SystemModuleMapper systemModuleMapper;
    private final SystemPlaceholderMapper systemPlaceholderMapper;
    private final SystemTemplateParser systemTemplateParser;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    private static final String SYSTEM_TEMPLATE_DIR = "system-templates";

    /**
     * 上传标准模板三件套并触发解析，写入 system_template / system_module / system_placeholder 表
     * <p>
     * 新上传的模板设为 active，旧的 active 模板自动归档。
     * </p>
     *
     * @param wordFile    标准Word模板文件（含占位符标记）
     * @param listFile    清单Excel模板文件
     * @param bvdFile     BVD数据Excel模板文件
     * @param name        模板名称
     * @param description 模板描述（可选）
     * @return 创建完成的系统模板对象
     */
    @Transactional(rollbackFor = Exception.class)
    public SystemTemplate uploadAndInit(MultipartFile wordFile, MultipartFile listFile,
                                        MultipartFile bvdFile, String name, String description) {
        validateFiles(wordFile, listFile, bvdFile);

        // 1. 保存三个文件到磁盘
        String templateId = UUID.randomUUID().toString();
        String baseDir = SYSTEM_TEMPLATE_DIR + "/" + templateId + "/";

        String wordRelPath = saveFile(wordFile, baseDir, "template.docx");
        String listRelPath = saveFile(listFile, baseDir, "list-template.xlsx");
        String bvdRelPath  = saveFile(bvdFile,  baseDir, "bvd-template.xlsx");

        String wordAbsPath = toAbsPath(wordRelPath);
        String listAbsPath = toAbsPath(listRelPath);
        String bvdAbsPath  = toAbsPath(bvdRelPath);

        // 2. 将旧 active 模板归档
        archiveOldActiveTemplates();

        // 3. 创建系统模板记录
        SystemTemplate template = new SystemTemplate();
        template.setId(templateId);
        template.setName(name != null ? name : wordFile.getOriginalFilename());
        template.setVersion("1.0");
        template.setWordFilePath(wordRelPath);
        template.setListExcelPath(listRelPath);
        template.setBvdExcelPath(bvdRelPath);
        template.setStatus("active");
        template.setDescription(description != null ? description : "");
        template.setCreatedAt(LocalDateTime.now());
        template.setDeleted(0);
        systemTemplateMapper.insert(template);

        // 4. 解析Word模板，提取占位符
        List<SystemPlaceholder> placeholders;
        try {
            placeholders = systemTemplateParser.parseWordTemplate(wordAbsPath, templateId);
        } catch (Exception e) {
            throw BizException.of("解析Word模板失败：" + e.getMessage());
        }

        // 5. 生成模块列表
        List<SystemModule> modules = systemTemplateParser.buildModules(placeholders, templateId);

        // 6. 批量保存占位符和模块
        if (!modules.isEmpty()) {
            modules.forEach(m -> {
                m.setId(UUID.randomUUID().toString());
                m.setDeleted(0);
                systemModuleMapper.insert(m);
            });
        }
        if (!placeholders.isEmpty()) {
            placeholders.forEach(ph -> {
                ph.setId(UUID.randomUUID().toString());
                ph.setDeleted(0);
                systemPlaceholderMapper.insert(ph);
            });
        }

        log.info("[SystemTemplateService] 系统模板初始化完成：id={}, 占位符数={}, 模块数={}",
                templateId, placeholders.size(), modules.size());

        // 返回不含文件路径的对象（符合 select=false 规范）
        template.setWordFilePath(null);
        template.setListExcelPath(null);
        template.setBvdExcelPath(null);
        return template;
    }

    /**
     * 获取当前激活的系统模板（不含文件路径）
     */
    public SystemTemplate getActive() {
        return systemTemplateMapper.selectOne(
                new LambdaQueryWrapper<SystemTemplate>()
                        .eq(SystemTemplate::getStatus, "active")
                        .eq(SystemTemplate::getDeleted, 0)
                        .orderByDesc(SystemTemplate::getCreatedAt)
                        .last("LIMIT 1")
        );
    }

    /**
     * 获取当前激活的系统模板（含全部文件路径，供引擎使用）
     */
    public SystemTemplate getActiveWithPaths() {
        SystemTemplate template = systemTemplateMapper.selectActiveWithAllPaths();
        if (template == null) {
            throw BizException.of(400, "尚未初始化系统标准模板，请先上传三件套");
        }
        return template;
    }

    /**
     * 查询指定系统模板下的所有占位符规则
     */
    public List<SystemPlaceholder> listPlaceholders(String systemTemplateId) {
        return systemPlaceholderMapper.selectByTemplateId(systemTemplateId);
    }

    /**
     * 查询当前激活模板的所有占位符规则
     */
    public List<SystemPlaceholder> listActivePlaceholders() {
        SystemTemplate active = getActive();
        if (active == null) return List.of();
        return listPlaceholders(active.getId());
    }

    /**
     * 查询指定系统模板下的所有模块
     */
    public List<SystemModule> listModules(String systemTemplateId) {
        return systemModuleMapper.selectByTemplateId(systemTemplateId);
    }

    /**
     * 查询所有系统模板列表（含 active 和 inactive）
     */
    public List<SystemTemplate> listAllTemplates() {
        return systemTemplateMapper.selectAllTemplates();
    }

    /**
     * 根据ID获取模板详情
     */
    public SystemTemplate getById(String id) {
        SystemTemplate template = systemTemplateMapper.selectById(id);
        if (template == null) {
            throw BizException.of(404, "模板不存在：" + id);
        }
        return template;
    }

    /**
     * 设置指定模板为激活状态
     * 原激活模板自动变为 inactive
     */
    @Transactional(rollbackFor = Exception.class)
    public SystemTemplate setActive(String id) {
        // 1. 验证模板存在
        SystemTemplate target = systemTemplateMapper.selectById(id);
        if (target == null) {
            throw BizException.of(404, "模板不存在：" + id);
        }

        // 2. 将当前所有 active 模板置为 inactive
        List<SystemTemplate> activeList = systemTemplateMapper.selectList(
                new LambdaQueryWrapper<SystemTemplate>()
                        .eq(SystemTemplate::getStatus, "active")
                        .eq(SystemTemplate::getDeleted, 0)
        );
        for (SystemTemplate old : activeList) {
            old.setStatus("inactive");
            systemTemplateMapper.updateById(old);
        }

        // 3. 设置目标模板为 active
        target.setStatus("active");
        systemTemplateMapper.updateById(target);

        log.info("[SystemTemplateService] 已切换激活模板：id={}, name={}", id, target.getName());
        return target;
    }

    /**
     * 查询指定模板下指定模块的所有占位符
     */
    public List<SystemPlaceholder> listPlaceholdersByModule(String systemTemplateId, String moduleId) {
        // 先获取模块的 code
        SystemModule module = systemModuleMapper.selectById(moduleId);
        if (module == null) {
            throw BizException.of(404, "模块不存在：" + moduleId);
        }
        return systemPlaceholderMapper.selectByTemplateIdAndModuleCode(systemTemplateId, module.getCode());
    }

    /**
     * 重新解析指定模板的 Word 文件，刷新 system_module 和 system_placeholder 数据
     * 适用于占位符格式变更后补全已存在模板的解析结果
     */
    @Transactional(rollbackFor = Exception.class)
    public void reparseById(String id) {
        // 1. 获取含文件路径的模板
        SystemTemplate template = systemTemplateMapper.selectByIdWithPaths(id);
        if (template == null) {
            throw BizException.of(404, "模板不存在：" + id);
        }

        String wordAbsPath = toAbsPath(template.getWordFilePath());

        // 2. 清除旧的模块和占位符数据
        systemModuleMapper.deleteByTemplateId(id);
        systemPlaceholderMapper.deleteByTemplateId(id);
        log.info("[SystemTemplateService] 已清除旧解析数据，templateId={}", id);

        // 3. 重新解析 Word 模板
        List<SystemPlaceholder> placeholders;
        try {
            placeholders = systemTemplateParser.parseWordTemplate(wordAbsPath, id);
        } catch (Exception e) {
            throw BizException.of("重新解析Word模板失败：" + e.getMessage());
        }

        // 4. 生成模块列表
        List<SystemModule> modules = systemTemplateParser.buildModules(placeholders, id);

        // 5. 批量写入
        if (!modules.isEmpty()) {
            modules.forEach(m -> {
                m.setId(UUID.randomUUID().toString());
                m.setDeleted(0);
                systemModuleMapper.insert(m);
            });
        }
        if (!placeholders.isEmpty()) {
            placeholders.forEach(ph -> {
                ph.setId(UUID.randomUUID().toString());
                ph.setDeleted(0);
                systemPlaceholderMapper.insert(ph);
            });
        }

        log.info("[SystemTemplateService] 重新解析完成：id={}, 占位符数={}, 模块数={}",
                id, placeholders.size(), modules.size());
    }

    /**
     * 删除指定模板（逻辑删除）
     * 只能删除状态为 inactive 的模板
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        // 1. 验证模板存在
        SystemTemplate template = systemTemplateMapper.selectById(id);
        if (template == null) {
            throw BizException.of(404, "模板不存在：" + id);
        }

        // 2. 检查是否为激活状态
        if ("active".equals(template.getStatus())) {
            throw BizException.of(400, "不能删除当前激活的模板，请先切换其他模板为激活状态");
        }

        // 3. 逻辑删除（MyBatis-Plus @TableLogic 会自动处理）
        systemTemplateMapper.deleteById(id);

        log.info("[SystemTemplateService] 已删除模板：id={}, name={}", id, template.getName());
    }

    // ========== 私有方法 ==========

    private void validateFiles(MultipartFile wordFile, MultipartFile listFile, MultipartFile bvdFile) {
        if (wordFile == null || wordFile.isEmpty()) {
            throw BizException.of(400, "Word模板文件不能为空");
        }
        if (listFile == null || listFile.isEmpty()) {
            throw BizException.of(400, "清单Excel模板文件不能为空");
        }
        if (bvdFile == null || bvdFile.isEmpty()) {
            throw BizException.of(400, "BVD数据Excel模板文件不能为空");
        }
        validateExt(wordFile, ".docx");
        validateExt(listFile, ".xlsx");
        validateExt(bvdFile, ".xlsx");
    }

    private void validateExt(MultipartFile file, String requiredExt) {
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(requiredExt)) {
            throw BizException.of(400, "文件格式不正确，需要 " + requiredExt + " 格式：" + originalName);
        }
    }

    private String saveFile(MultipartFile file, String relativeDir, String fileName) {
        Path baseDir = Paths.get(uploadDir).normalize().toAbsolutePath();
        Path fullDir = baseDir.resolve(relativeDir).normalize();
        if (!fullDir.startsWith(baseDir)) {
            throw BizException.of("非法路径");
        }
        try {
            Files.createDirectories(fullDir);
            file.transferTo(fullDir.resolve(fileName));
        } catch (IOException e) {
            throw BizException.of("文件保存失败：" + e.getMessage());
        }
        String relativePath = relativeDir + fileName;
        log.debug("[SystemTemplateService] 文件已保存: {}", relativePath);
        return relativePath;
    }

    private String toAbsPath(String relativePath) {
        return uploadDir + "/" + relativePath;
    }

    private void archiveOldActiveTemplates() {
        List<SystemTemplate> activeList = systemTemplateMapper.selectList(
                new LambdaQueryWrapper<SystemTemplate>()
                        .eq(SystemTemplate::getStatus, "active")
                        .eq(SystemTemplate::getDeleted, 0)
        );
        for (SystemTemplate old : activeList) {
            old.setStatus("inactive");
            systemTemplateMapper.updateById(old);
        }
        if (!activeList.isEmpty()) {
            log.info("[SystemTemplateService] 已归档旧标准模板 {} 个", activeList.size());
        }
    }
}
