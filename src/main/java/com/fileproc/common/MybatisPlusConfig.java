package com.fileproc.common;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * MyBatis-Plus 配置：多租户插件 + 分页插件
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 不参与租户隔离的表（全局共享或超管专用）
     */
    private static final List<String> IGNORE_TABLES = Arrays.asList(
            "sys_tenant",
            "sys_admin",
            "sys_permission",
            "sys_role_permission",
            // 系统级模板表，不绑定租户，全局共享
            "system_template",
            "system_module",
            "system_placeholder",
            // 企业子模板模块和占位符表，通过 company_template_id 关联，不直接存 tenant_id
            "company_template_module",
            "company_template_placeholder",
            // 占位符注册表：系统级 tenant_id=null，企业级用 company_id 区分，不依赖租户过滤
            "placeholder_registry"
    );

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 1. 多租户插件（放在最前面）
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                String tenantId = TenantContext.getTenantId();
                return new StringValue(tenantId != null ? tenantId : "");
            }

            @Override
            public boolean ignoreTable(String tableName) {
                // tenantId 为 null（超管请求/异步线程）时跳过租户过滤，防止注入 tenant_id='' 脏数据
                if (TenantContext.getTenantId() == null) return true;
                return IGNORE_TABLES.contains(tableName.toLowerCase());
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }
        }));

        // 2. 分页插件
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        return interceptor;
    }
}
