package com.fileproc.template.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fileproc.common.BizException;
import com.fileproc.template.entity.CompanyTemplatePlaceholder;
import com.fileproc.template.mapper.CompanyTemplatePlaceholderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 企业子模板占位符状态 Service
 * <p>
 * 负责：
 * 1. 初始化占位符状态（反向生成时）
 * 2. 查询占位符状态列表
 * 3. 更新占位符确认状态
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyTemplatePlaceholderService {

    private final CompanyTemplatePlaceholderMapper placeholderMapper;

    /**
     * 初始化子模板的占位符状态
     * <p>
     * 反向生成时调用，为每个占位符创建初始状态记录（status=uncertain）
     * </p>
     *
     * @param companyTemplateId 子模板ID
     * @param items             待确认的占位符列表
     */
    @Transactional(rollbackFor = Exception.class)
    public void initPlaceholders(String companyTemplateId, List<com.fileproc.report.service.ReverseTemplateEngine.PendingConfirmItem> items) {
        // 先删除该子模板已有的占位符记录（防止重复初始化）
        placeholderMapper.delete(
                new LambdaQueryWrapper<CompanyTemplatePlaceholder>()
                        .eq(CompanyTemplatePlaceholder::getCompanyTemplateId, companyTemplateId)
        );

        // 批量插入新的占位符状态记录
        for (com.fileproc.report.service.ReverseTemplateEngine.PendingConfirmItem item : items) {
            CompanyTemplatePlaceholder ph = new CompanyTemplatePlaceholder();
            ph.setId(UUID.randomUUID().toString());
            ph.setCompanyTemplateId(companyTemplateId);
            ph.setPlaceholderName(item.getPlaceholderName());
            ph.setStatus("uncertain");
            ph.setExpectedValue(item.getExpectedValue());
            ph.setActualValue(item.getActualValue());
            ph.setReason(item.getReason());
            ph.setPositionJson(item.getPositionJson());
            ph.setCreatedAt(LocalDateTime.now());
            ph.setUpdatedAt(LocalDateTime.now());
            placeholderMapper.insert(ph);
        }

        log.info("[CompanyTemplatePlaceholderService] 占位符状态已初始化: templateId={}, count={}",
                companyTemplateId, items.size());
    }

    /**
     * 查询子模板的所有占位符状态
     */
    public List<CompanyTemplatePlaceholder> listByTemplateId(String companyTemplateId) {
        return placeholderMapper.selectByTemplateId(companyTemplateId);
    }

    /**
     * 查询子模板中待确认的占位符列表
     */
    public List<CompanyTemplatePlaceholder> listUncertainByTemplateId(String companyTemplateId) {
        return placeholderMapper.selectByTemplateIdAndStatus(companyTemplateId, "uncertain");
    }

    /**
     * 批量更新占位符确认状态
     * <p>
     * 用户在前端确认后调用，更新状态为 confirmed 或 ignored
     * </p>
     *
     * @param companyTemplateId 子模板ID
     * @param confirmItems      确认项列表（placeholderName + confirmedType）
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirmPlaceholders(String companyTemplateId,
                                     List<com.fileproc.report.service.ReverseTemplateEngine.PendingConfirmItem> confirmItems) {
        for (com.fileproc.report.service.ReverseTemplateEngine.PendingConfirmItem item : confirmItems) {
            CompanyTemplatePlaceholder ph = placeholderMapper.selectByTemplateIdAndName(
                    companyTemplateId, item.getPlaceholderName());
            if (ph == null) {
                log.warn("[CompanyTemplatePlaceholderService] 占位符不存在: templateId={}, name={}",
                        companyTemplateId, item.getPlaceholderName());
                continue;
            }

            // 处理 confirmed 和 confirmedType 字段
            String confirmedType = item.getConfirmedType();
            if (item.isConfirmed()) {
                // confirmed=true 时，检查 confirmedType
                if ("ignore".equals(confirmedType)) {
                    // 标记为忽略
                    ph.setStatus("ignored");
                    ph.setConfirmedType(null);
                } else {
                    // 正常确认，记录类型
                    ph.setStatus("confirmed");
                    ph.setConfirmedType(confirmedType);
                }
            } else {
                // confirmed=false，保持 uncertain 或设为 ignored（根据业务需求）
                ph.setStatus("ignored");
                ph.setConfirmedType(null);
            }
            ph.setUpdatedAt(LocalDateTime.now());
            placeholderMapper.updateById(ph);
        }

        log.info("[CompanyTemplatePlaceholderService] 占位符确认完成: templateId={}, count={}",
                companyTemplateId, confirmItems.size());
    }

    /**
     * 检查是否还有未确认的占位符
     *
     * @return 未确认的占位符数量
     */
    public int countUncertain(String companyTemplateId) {
        return placeholderMapper.selectByTemplateIdAndStatus(companyTemplateId, "uncertain").size();
    }

    /**
     * 删除子模板的所有占位符状态记录
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByTemplateId(String companyTemplateId) {
        placeholderMapper.delete(
                new LambdaQueryWrapper<CompanyTemplatePlaceholder>()
                        .eq(CompanyTemplatePlaceholder::getCompanyTemplateId, companyTemplateId)
        );
        log.info("[CompanyTemplatePlaceholderService] 占位符状态已删除: templateId={}", companyTemplateId);
    }
}
