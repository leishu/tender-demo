package org.example.enterprise.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import org.example.enterprise.model.Personnel;
import org.example.enterprise.model.ProjectPerformance;
import org.example.enterprise.model.Qualification;
import org.example.enterprise.repo.EnterpriseRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Component
public class EnterpriseDataTools {

    /**
     * 同参查询缓存的有效期。一次匹配任务通常几分钟内结束，模型在任务中反复发起
     * 完全相同的查询（trace 里出现过多次 role="" 的 query_personnel），缓存命中
     * 既省数据库往返，也让重复调用拿到的结果一致；过期后自动回源，保证数据更新可见。
     */
    private static final long CACHE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();

    /** 缓存条目上限，防止极端情况下无界增长（岗位/资质词表本身很小） */
    private static final int MAX_CACHE_ENTRIES = 256;

    private final EnterpriseRepository repository;
    private final Map<String, CacheEntry> queryCache = new ConcurrentHashMap<>();

    public EnterpriseDataTools(EnterpriseRepository repository) {
        this.repository = repository;
    }

    @Tool(name = "query_qualifications",
            description = "查询企业资质列表。当需要确认企业是否具备某项资质时调用。"
                    + "按类型关键词模糊匹配；短时间内相同参数的查询返回缓存结果，请勿用相同参数反复调用。")
    public List<Qualification> queryQualifications(
            @ToolParam(name = "type", required = false,
                    description = "资质类型关键词，如'ISO9001'或'施工总承包'，模糊匹配。"
                            + "传空字符串或省略该参数均返回全部资质")
            String type) {
        return cachedQuery("qualifications:" + normalize(type),
                () -> repository.findQualifications(type));
    }

    @Tool(name = "query_performances",
            description = "查询企业类似项目业绩。当需要引用过往案例时调用。"
                    + "短时间内相同参数的查询返回缓存结果，请勿用相同参数反复调用。")
    public List<ProjectPerformance> queryPerformances(
            @ToolParam(name = "keyword", required = false,
                    description = "项目关键词。传空字符串或省略该参数均返回全部业绩")
            String keyword) {
        return cachedQuery("performances:" + normalize(keyword),
                () -> repository.findPerformances(keyword));
    }

    @Tool(name = "query_personnel",
            description = "查询企业人员及其可担任岗位、证书与职称。返回的每个人含 gender/birthDate/yearsOfExperience、"
                    + "roles（可担任岗位数组，一人可多岗）与 certificates（证书数组，一人多张；"
                    + "职称也是其中一条，type 为「职称」，major 为专业）。"
                    + "常见岗位别名会自动展开（如'安全负责人'同时匹配'安全员'、'安全总监'）。"
                    + "当需要确认项目团队配置、核对职称或证书要求时调用。"
                    + "短时间内相同参数的查询返回缓存结果，请勿用相同参数反复调用。")
    public List<Personnel> queryPersonnel(
            @ToolParam(name = "role", required = false,
                    description = "岗位关键词，如'项目经理'。按关键词模糊匹配并自动展开常见别名；"
                            + "传空字符串或省略该参数均返回全部人员")
            String role) {
        List<String> keywords = RoleAliases.expand(role);
        return cachedQuery("personnel:" + normalize(role),
                () -> repository.findPersonnelByRoles(keywords));
    }

    /** null/空白统一成空串，保证「省略参数」「空串」「纯空格」命中同一个缓存键 */
    private static String normalize(String param) {
        return param == null ? "" : param.trim();
    }

    /**
     * 同参去重：TTL 内直接返回上次结果，不再查库。并发下最多重复查一次，
     * 属于可接受的最终一致；缓存只存读多写少的企业台账数据。
     */
    @SuppressWarnings("unchecked")
    private <T> T cachedQuery(String key, Supplier<T> loader) {
        long now = System.currentTimeMillis();
        CacheEntry entry = queryCache.get(key);
        if (entry != null && now - entry.createdAtMillis() < CACHE_TTL_MILLIS) {
            return (T) entry.value();
        }
        T value = loader.get();
        queryCache.put(key, new CacheEntry(value, now));
        if (queryCache.size() > MAX_CACHE_ENTRIES) {
            queryCache.entrySet().removeIf(e -> now - e.getValue().createdAtMillis() >= CACHE_TTL_MILLIS);
            if (queryCache.size() > MAX_CACHE_ENTRIES) {
                queryCache.clear();
            }
        }
        return value;
    }

    private record CacheEntry(Object value, long createdAtMillis) {
    }
}