package com.fileproc.template.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fileproc.common.BizException;
import com.fileproc.template.entity.CompanyTemplateModule;
import com.fileproc.template.mapper.CompanyTemplateModuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 企业子模板模块 Service
 * <p>
 * 负责：
 * 1. 模块的CRUD操作
 * 2. 按子模板查询模块列表
 * 3. 根据code查找模块
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyTemplateModuleService {

    private final CompanyTemplateModuleMapper moduleMapper;

    /**
     * 根据子模板ID查询所有模块
     */
    public List<CompanyTemplateModule> listByTemplateId(String companyTemplateId) {
        return moduleMapper.selectByTemplateId(companyTemplateId);
    }

    /**
     * 根据ID查询模块
     */
    public CompanyTemplateModule getById(String id) {
        CompanyTemplateModule module = moduleMapper.selectById(id);
        if (module == null) throw BizException.notFound("模块");
        return module;
    }

    /**
     * 根据子模板ID和code查询模块
     */
    public CompanyTemplateModule getByTemplateIdAndCode(String companyTemplateId, String code) {
        return moduleMapper.selectByTemplateIdAndCode(companyTemplateId, code);
    }

    /**
     * 创建或获取模块
     * <p>
     * 如果指定code的模块已存在则返回，否则创建新模块
     * </p>
     *
     * @param companyTemplateId 子模板ID
     * @param code              模块编码
     * @param name              模块名称
     * @param sort              排序序号
     * @return 模块实体
     */
    @Transactional(rollbackFor = Exception.class)
    public CompanyTemplateModule getOrCreate(String companyTemplateId, String code, String name, int sort) {
        CompanyTemplateModule existing = moduleMapper.selectByTemplateIdAndCode(companyTemplateId, code);
        if (existing != null) {
            return existing;
        }

        CompanyTemplateModule module = new CompanyTemplateModule();
        module.setId(UUID.randomUUID().toString());
        module.setCompanyTemplateId(companyTemplateId);
        module.setCode(code);
        module.setName(name);
        module.setSort(sort);
        module.setCreatedAt(LocalDateTime.now());
        module.setUpdatedAt(LocalDateTime.now());
        moduleMapper.insert(module);

        log.info("[CompanyTemplateModuleService] 模块已创建: id={}, code={}, name={}",
                module.getId(), code, name);
        return module;
    }

    /**
     * 批量创建模块
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchCreate(String companyTemplateId, List<ModuleInfo> modules) {
        int sort = 0;
        for (ModuleInfo info : modules) {
            getOrCreate(companyTemplateId, info.getCode(), info.getName(), sort++);
        }
        log.info("[CompanyTemplateModuleService] 批量创建模块完成: templateId={}, count={}",
                companyTemplateId, modules.size());
    }

    /**
     * 更新模块信息
     */
    @Transactional(rollbackFor = Exception.class)
    public CompanyTemplateModule update(String id, String name, String description, Integer sort) {
        CompanyTemplateModule module = getById(id);
        if (name != null) module.setName(name);
        if (description != null) module.setDescription(description);
        if (sort != null) module.setSort(sort);
        module.setUpdatedAt(LocalDateTime.now());
        moduleMapper.updateById(module);
        return module;
    }

    /**
     * 删除子模板的所有模块
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByTemplateId(String companyTemplateId) {
        moduleMapper.delete(
                new LambdaQueryWrapper<CompanyTemplateModule>()
                        .eq(CompanyTemplateModule::getCompanyTemplateId, companyTemplateId)
        );
        log.info("[CompanyTemplateModuleService] 模块已删除: templateId={}", companyTemplateId);
    }

    /**
     * 模块信息（用于批量创建）
     */
    @lombok.Data
    public static class ModuleInfo {
        private String code;
        private String name;

        public ModuleInfo(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }
}
