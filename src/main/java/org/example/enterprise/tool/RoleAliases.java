package org.example.enterprise.tool;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

/**
 * 岗位别名字典：把招标要求里常见的岗位称谓归一到同一组关键词。
 *
 * <p>企业人员库的岗位词表很小（十几条），用 MySQL LIKE/分词做语义归并既不准也不划算，
 * 因此在 Java 工具层维护一张静态别名表：查询前先展开成组内全部关键词，
 * 再由仓储层做普通的 OR LIKE 过滤。</p>
 *
 * <p>未收录的关键词原样返回，不影响已有的模糊匹配行为。</p>
 */
public final class RoleAliases {

    /** 每组内的称谓视为可互相替代的同一岗位 */
    private static final List<Set<String>> GROUPS = List.of(
            Set.of("项目经理", "项目负责人"),
            Set.of("技术负责人", "总工程师", "项目总工", "技术总监"),
            Set.of("安全负责人", "安全员", "安全总监"),
            Set.of("造价负责人", "造价工程师", "造价员", "预算员"),
            Set.of("质量负责人", "质量员", "质检员"));

    private RoleAliases() {
    }

    /**
     * 展开岗位关键词：命中别名组时返回组内全部关键词（含原词），
     * 未命中时返回仅含原词的列表；空输入返回空列表（表示查询全部人员）。
     */
    public static List<String> expand(String role) {
        if (!StringUtils.hasText(role)) {
            return List.of();
        }
        String keyword = role.trim();
        for (Set<String> group : GROUPS) {
            if (group.contains(keyword)) {
                return List.copyOf(group);
            }
        }
        return List.of(keyword);
    }
}