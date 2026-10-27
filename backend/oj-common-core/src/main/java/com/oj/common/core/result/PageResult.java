package com.oj.common.core.result;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 分页响应包装（设计文档 §1.3 / 基线决策 §3.2）。
 *
 * <p>对外 JSON 固定为：</p>
 * <pre>
 * "data": { "list": [...], "total": 137, "page": 1, "pageSize": 20, "hasNext": true }
 * </pre>
 *
 * <p><b>hasNext 由服务端算，不要让前端猜</b>：{@code page * pageSize < total}。
 * 这条规则在分页组件（原型 shell.js 的 OJ.pager）里被直接消费。</p>
 *
 * @param <T> 列表元素类型
 * @author oj-cloud
 */
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页数据 */
    private List<T> list;

    /** 总记录数 */
    private long total;

    /** 当前页码，从 1 起 */
    private int page;

    /** 每页条数，1~100，超过 100 按 100 处理 */
    private int pageSize;

    /** 是否存在下一页 */
    private boolean hasNext;

    public PageResult() {
        this.list = new ArrayList<>();
    }

    public PageResult(List<T> list, long total, int page, int pageSize) {
        this.list = (list == null) ? new ArrayList<>() : list;
        this.total = total;
        this.page = normalizePage(page);
        this.pageSize = normalizePageSize(pageSize);
        this.hasNext = (long) this.page * this.pageSize < this.total;
    }

    /**
     * 构造分页结果。
     *
     * @param list     当前页数据
     * @param total    总记录数
     * @param page     页码（≤0 视为 1）
     * @param pageSize 每页条数（≤0 视为 20，>100 按 100）
     */
    public static <T> PageResult<T> of(List<T> list, long total, int page, int pageSize) {
        return new PageResult<>(list, total, page, pageSize);
    }

    /** 空分页结果（查不到数据时用，保证前端永远能遍历 list） */
    public static <T> PageResult<T> empty(int page, int pageSize) {
        return new PageResult<>(new ArrayList<>(), 0L, page, pageSize);
    }

    /** 参数水位修正：page 至少为 1 */
    public static int normalizePage(int page) {
        return Math.max(page, 1);
    }

    /** 参数水位修正：pageSize 落在 [1,100] */
    public static int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }

    public List<T> getList() {
        return list;
    }

    public void setList(List<T> list) {
        this.list = list;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public boolean isHasNext() {
        return hasNext;
    }

    public void setHasNext(boolean hasNext) {
        this.hasNext = hasNext;
    }
}
