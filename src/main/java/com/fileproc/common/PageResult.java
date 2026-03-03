package com.fileproc.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 分页响应体，与前端 PageResult<T> 字段完全对齐：list/total/page/pageSize
 */
@Data
public class PageResult<T> implements Serializable {

    private List<T> list;
    private long total;
    private int page;
    private int pageSize;

    public PageResult(List<T> list, long total, int page, int pageSize) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }

    /**
     * 从 MyBatis-Plus IPage 直接转换（实体与 VO 类型相同时使用）
     */
    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(
                page.getRecords(),
                page.getTotal(),
                (int) page.getCurrent(),
                (int) page.getSize()
        );
    }

    /**
     * 从 MyBatis-Plus IPage 转换并映射 VO（实体 → VO 转换）
     */
    public static <E, V> PageResult<V> of(IPage<E> page, Function<E, V> mapper) {
        List<V> voList = page.getRecords().stream()
                .map(mapper)
                .collect(Collectors.toList());
        return new PageResult<>(
                voList,
                page.getTotal(),
                (int) page.getCurrent(),
                (int) page.getSize()
        );
    }
}
