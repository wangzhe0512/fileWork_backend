package com.fileproc.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fileproc.system.entity.SysPermission;
import com.fileproc.system.mapper.PermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 权限 Service：构建权限树
 * P2：改为 O(n) Map 分组，避免递归 O(n²) 遍历
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final PermissionMapper permissionMapper;

    /** 返回完整的权限树（含 children 嵌套） */
    public List<SysPermission> getPermissionTree() {
        List<SysPermission> all = permissionMapper.selectList(
                new LambdaQueryWrapper<SysPermission>().orderByAsc(SysPermission::getSort)
        );
        return buildTree(all);
    }

    /**
     * P2：O(n) 构建权限树，用 Map<parentId, List<children>> 分组，避免嵌套遍历
     * P2-PERF-04：过滤条件改为 != null && !isEmpty()，避免空字符串误入分组
     */
    private List<SysPermission> buildTree(List<SysPermission> all) {
        // 按 parentId 分组（过滤 parentId 为 null 或空字符串的节点）
        Map<String, List<SysPermission>> byParent = all.stream()
                .filter(p -> p.getParentId() != null && !p.getParentId().isEmpty())
                .collect(Collectors.groupingBy(SysPermission::getParentId));

        // 为每个节点设置 children
        for (SysPermission p : all) {
            List<SysPermission> children = byParent.get(p.getId());
            if (children != null && !children.isEmpty()) {
                p.setChildren(children);
            }
        }

        // 返回根节点（parentId 为 null 或空字符串）
        return all.stream()
                .filter(p -> p.getParentId() == null || p.getParentId().isEmpty())
                .collect(Collectors.toList());
    }
}
