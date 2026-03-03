package com.fileproc.common.annotation;

import java.lang.annotation.*;

/**
 * 操作日志注解，标注在 Service 方法上
 * AOP 切面 OperationLogAspect 会自动捕获并异步写入 sys_log 表
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperationLog {

    /** 操作模块，如：企业管理、数据文件、报告管理 */
    String module();

    /** 操作动作，如：新建、编辑、删除、归档 */
    String action();
}
