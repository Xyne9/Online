package com.oj.teaching.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 导航菜单响应体（契约 §2.6 {@code data}）。
 *
 * <pre>
 * {"groups":[{"key":"study","title":"学习","items":[
 *    {"key":"s-problems","title":"题库","path":"s-problems.html","badge":0,"active":false}]}],
 *  "roleHint":"student"}
 * </pre>
 *
 * <p><b>为什么不再让前端硬编码：</b>原型 {@code shell.js:96-129} 的 {@code NAV} 是
 * 二维常量数组，角标 {@code badge:'3' / '2' / '5'} 是写死的字符串。改由服务端下发后，
 * 角标能反映真实待办数，且 {@code a-sandbox} 这个「幽灵页」得以下架——
 * 它不再是独立页面，而是挂在 {@code a-audit} 组内、指向 {@code a-audit.html#sandbox} 的锚点。</p>
 *
 * @author oj-cloud
 */
@Data
public class MenuVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 导航分组 */
    private List<Group> groups;

    /** 角色提示（小写）：student / teacher / admin，前端用来对齐本地 OJ_ROLE */
    private String roleHint;

    /**
     * 一个导航分组（对应原型的一列 {@code .nav-group}）。
     */
    @Data
    public static class Group implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 分组键：study / review / infra ... */
        private String key;

        /** 分组标题：学习 / 监控与审查 / 基础设施 */
        private String title;

        /** 组内导航项 */
        private List<Item> items;
    }

    /**
     * 一个导航项（对应原型的一个 {@code .nav-item}）。
     */
    @Data
    public static class Item implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 导航项键，与原型 {@code NAV[].items[].id} 同名 */
        private String key;

        /** 展示标题 */
        private String title;

        /** 目标路径（可含 #fragment，如 {@code a-audit.html#sandbox}） */
        private String path;

        /** 角标数字；0 表示不显示角标（前端 {@code it.badge ? ... : ''} 的真值判断） */
        private Integer badge;

        /** 是否当前页；服务端不知道前端在哪一页，恒为 false，由前端按 path 自行高亮 */
        private Boolean active;
    }
}
