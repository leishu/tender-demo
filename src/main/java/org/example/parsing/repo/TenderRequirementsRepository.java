package org.example.parsing.repo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.example.parsing.mapper.PerformanceRequirementMapper;
import org.example.parsing.mapper.PersonnelRequirementMapper;
import org.example.parsing.mapper.QualificationRequirementMapper;
import org.example.parsing.mapper.ScoringItemMapper;
import org.example.parsing.mapper.TenderRequirementsMapper;
import org.example.parsing.model.PerformanceRequirement;
import org.example.parsing.model.PersonnelRequirement;
import org.example.parsing.model.QualificationRequirement;
import org.example.parsing.model.ScoringItem;
import org.example.parsing.model.TenderRequirements;
import org.example.parsing.model.dto.TenderRequirementSummary;
import org.example.util.Texts;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class TenderRequirementsRepository {
    private final TenderRequirementsMapper tenderRequirementsMapper;
    private final QualificationRequirementMapper qualificationRequirementMapper;
    private final PerformanceRequirementMapper performanceRequirementMapper;
    private final PersonnelRequirementMapper personnelRequirementMapper;
    private final ScoringItemMapper scoringItemMapper;

    public TenderRequirementsRepository(TenderRequirementsMapper tenderRequirementsMapper,
                                        QualificationRequirementMapper qualificationRequirementMapper,
                                        PerformanceRequirementMapper performanceRequirementMapper,
                                        PersonnelRequirementMapper personnelRequirementMapper,
                                        ScoringItemMapper scoringItemMapper) {
        this.tenderRequirementsMapper = tenderRequirementsMapper;
        this.qualificationRequirementMapper = qualificationRequirementMapper;
        this.performanceRequirementMapper = performanceRequirementMapper;
        this.personnelRequirementMapper = personnelRequirementMapper;
        this.scoringItemMapper = scoringItemMapper;
    }

    /**
     * 保存招标结构化要求，连同四张子表级联入库。
     * 入库前会对各字段做长度归一化（截断到对应列的最大长度），避免因超长导致写入异常。
     *
     * <p>主键与录入时间一律由服务端生成：调用方传入的 {@code id}（含子表 id）会被清空，
     * 避免通过请求体指定主键覆盖已有记录（over-posting）。</p>
     */
    public TenderRequirements save(TenderRequirements requirements) {
        normalize(requirements);
        // 新记录永远是 insert：忽略调用方传入的主键，避免覆盖历史数据
        requirements.setId(null);
        // 录入时间统一由服务端写入，避免调用方漏传或传入客户端时间
        requirements.setRecordTime(System.currentTimeMillis());
        tenderRequirementsMapper.insert(requirements);
        Long reqId = requirements.getId();
        saveQualifications(reqId, requirements.getQualifications());
        savePerformances(reqId, requirements.getPerformances());
        savePersonnel(reqId, requirements.getPersonnel());
        saveScoringItems(reqId, requirements.getScoringItems());
        return requirements;
    }

    /** 按 id 查询主表并聚合子表 */
    public TenderRequirements findById(Long id) {
        TenderRequirements requirements = tenderRequirementsMapper.selectById(id);
        if (requirements == null) {
            return null;
        }
        requirements.setQualifications(findQualifications(id));
        requirements.setPerformances(findPerformances(id));
        requirements.setPersonnel(findPersonnel(id));
        requirements.setScoringItems(findScoringItems(id));
        return requirements;
    }

    /** 判断招标要求是否存在 */
    public boolean existsById(Long id) {
        return id != null && tenderRequirementsMapper.selectById(id) != null;
    }

    /**
     * 查询已解析招标文件的列表项，按录入时间倒序（最近录入在前），录入时间相同时按主键倒序兜底。
     *
     * @param keyword 项目名称模糊匹配关键字，为空时不过滤
     * @param limit   返回条数上限，调用方需保证已做合法区间校验
     */
    public List<TenderRequirementSummary> findSummaries(String keyword, int limit) {
        LambdaQueryWrapper<TenderRequirements> wrapper = new LambdaQueryWrapper<TenderRequirements>()
                .select(TenderRequirements::getId,
                        TenderRequirements::getProjectName,
                        TenderRequirements::getLocation,
                        TenderRequirements::getDuration,
                        TenderRequirements::getBudget,
                        TenderRequirements::getRecordTime);
        if (StringUtils.hasText(keyword)) {
            wrapper.like(TenderRequirements::getProjectName, keyword.trim());
        }
        wrapper.orderByDesc(TenderRequirements::getRecordTime)
                .orderByDesc(TenderRequirements::getId)
                .last("LIMIT " + limit);

        List<TenderRequirements> rows = tenderRequirementsMapper.selectList(wrapper);
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> ids = rows.stream().map(TenderRequirements::getId).toList();
        Map<Long, Integer> qualificationCounts = countQualifications(ids);
        Map<Long, Integer> performanceCounts = countPerformances(ids);
        Map<Long, Integer> personnelCounts = countPersonnel(ids);
        Map<Long, Integer> scoringItemCounts = countScoringItems(ids);

        List<TenderRequirementSummary> summaries = new ArrayList<>(rows.size());
        for (TenderRequirements row : rows) {
            TenderRequirementSummary summary = new TenderRequirementSummary();
            summary.setId(row.getId());
            summary.setProjectName(row.getProjectName());
            summary.setLocation(row.getLocation());
            summary.setDuration(row.getDuration());
            summary.setBudget(row.getBudget());
            summary.setRecordTime(row.getRecordTime());
            summary.setQualificationCount(qualificationCounts.getOrDefault(row.getId(), 0));
            summary.setPerformanceCount(performanceCounts.getOrDefault(row.getId(), 0));
            summary.setPersonnelCount(personnelCounts.getOrDefault(row.getId(), 0));
            summary.setScoringItemCount(scoringItemCounts.getOrDefault(row.getId(), 0));
            summaries.add(summary);
        }
        return summaries;
    }

    /** 按 id 删除，连同子表一并删除 */
    public void deleteById(Long id) {
        qualificationRequirementMapper.delete(new LambdaQueryWrapper<QualificationRequirement>()
                .eq(QualificationRequirement::getTenderRequirementId, id));
        performanceRequirementMapper.delete(new LambdaQueryWrapper<PerformanceRequirement>()
                .eq(PerformanceRequirement::getTenderRequirementId, id));
        personnelRequirementMapper.delete(new LambdaQueryWrapper<PersonnelRequirement>()
                .eq(PersonnelRequirement::getTenderRequirementId, id));
        scoringItemMapper.delete(new LambdaQueryWrapper<ScoringItem>()
                .eq(ScoringItem::getTenderRequirementId, id));
        tenderRequirementsMapper.deleteById(id);
    }

    private void saveQualifications(Long reqId, List<QualificationRequirement> list) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        for (QualificationRequirement item : list) {
            item.setId(null);
            item.setTenderRequirementId(reqId);
            normalize(item);
        }
        qualificationRequirementMapper.insertBatch(list);
    }

    private void savePerformances(Long reqId, List<PerformanceRequirement> list) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        for (PerformanceRequirement item : list) {
            item.setId(null);
            item.setTenderRequirementId(reqId);
            normalize(item);
        }
        performanceRequirementMapper.insertBatch(list);
    }

    private void savePersonnel(Long reqId, List<PersonnelRequirement> list) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        for (PersonnelRequirement item : list) {
            item.setId(null);
            item.setTenderRequirementId(reqId);
            normalize(item);
        }
        personnelRequirementMapper.insertBatch(list);
    }

    private void saveScoringItems(Long reqId, List<ScoringItem> list) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        for (ScoringItem item : list) {
            item.setId(null);
            item.setTenderRequirementId(reqId);
            normalize(item);
        }
        scoringItemMapper.insertBatch(list);
    }

    private List<QualificationRequirement> findQualifications(Long reqId) {
        return qualificationRequirementMapper.selectList(new LambdaQueryWrapper<QualificationRequirement>()
                .eq(QualificationRequirement::getTenderRequirementId, reqId));
    }

    private List<PerformanceRequirement> findPerformances(Long reqId) {
        return performanceRequirementMapper.selectList(new LambdaQueryWrapper<PerformanceRequirement>()
                .eq(PerformanceRequirement::getTenderRequirementId, reqId));
    }

    private List<PersonnelRequirement> findPersonnel(Long reqId) {
        return personnelRequirementMapper.selectList(new LambdaQueryWrapper<PersonnelRequirement>()
                .eq(PersonnelRequirement::getTenderRequirementId, reqId));
    }

    private List<ScoringItem> findScoringItems(Long reqId) {
        return scoringItemMapper.selectList(new LambdaQueryWrapper<ScoringItem>()
                .eq(ScoringItem::getTenderRequirementId, reqId));
    }

    // ---------- 列表页统计：按招标要求分组统计四张子表的条数，一次查询取回 ----------

    private Map<Long, Integer> countQualifications(Collection<Long> ids) {
        return countGrouped(qualificationRequirementMapper, new QueryWrapper<QualificationRequirement>()
                .select("tender_requirement_id AS req_id", "COUNT(*) AS cnt")
                .in("tender_requirement_id", ids)
                .groupBy("tender_requirement_id"));
    }

    private Map<Long, Integer> countPerformances(Collection<Long> ids) {
        return countGrouped(performanceRequirementMapper, new QueryWrapper<PerformanceRequirement>()
                .select("tender_requirement_id AS req_id", "COUNT(*) AS cnt")
                .in("tender_requirement_id", ids)
                .groupBy("tender_requirement_id"));
    }

    private Map<Long, Integer> countPersonnel(Collection<Long> ids) {
        return countGrouped(personnelRequirementMapper, new QueryWrapper<PersonnelRequirement>()
                .select("tender_requirement_id AS req_id", "COUNT(*) AS cnt")
                .in("tender_requirement_id", ids)
                .groupBy("tender_requirement_id"));
    }

    private Map<Long, Integer> countScoringItems(Collection<Long> ids) {
        return countGrouped(scoringItemMapper, new QueryWrapper<ScoringItem>()
                .select("tender_requirement_id AS req_id", "COUNT(*) AS cnt")
                .in("tender_requirement_id", ids)
                .groupBy("tender_requirement_id"));
    }

    /** 执行分组统计查询，把 {req_id, cnt} 结果整理成 map；无数据时返回空 map */
    private <T> Map<Long, Integer> countGrouped(BaseMapper<T> mapper, QueryWrapper<T> wrapper) {
        List<Map<String, Object>> rows = mapper.selectMaps(wrapper);
        if (CollectionUtils.isEmpty(rows)) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object reqId = lookupValue(row, "req_id", "tender_requirement_id");
            Object count = lookupValue(row, "cnt", "count(*)");
            if (reqId instanceof Number idNumber && count instanceof Number countNumber) {
                counts.put(idNumber.longValue(), countNumber.intValue());
            }
        }
        return counts;
    }

    /** 按忽略大小写的列名取值，规避不同驱动/数据库返回列名大小写差异 */
    private static Object lookupValue(Map<String, Object> row, String... keys) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            for (String key : keys) {
                if (entry.getKey().equalsIgnoreCase(key)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    // ---------- 长度归一化：与建库脚本列定义对齐，超长字段截断，避免写入时报错 ----------

    private static final int TR_PROJECT_NAME_MAX = 255;
    private static final int TR_LOCATION_MAX = 255;
    private static final int TR_DURATION_MAX = 64;
    private static final int TR_BUDGET_MAX = 128;

    private static final int QUAL_TYPE_MAX = 128;
    private static final int QUAL_LEVEL_MAX = 64;
    private static final int QUAL_VALID_MAX = 255;
    private static final int QUAL_SOURCE_MAX = 512;

    private static final int PERF_PROJECT_TYPE_MAX = 255;
    private static final int PERF_QUANTITY_MAX = 64;
    private static final int PERF_AMOUNT_MAX = 128;
    private static final int PERF_TIME_RANGE_MAX = 128;
    private static final int PERF_SOURCE_MAX = 512;

    private static final int PERSONNEL_ROLE_MAX = 64;
    private static final int PERSONNEL_CERT_MAX = 255;
    private static final int PERSONNEL_SOURCE_MAX = 512;

    private static final int SCORING_ITEM_ID_MAX = 64;
    private static final int SCORING_NAME_MAX = 128;
    private static final int SCORING_CRITERION_MAX = 512;
    private static final int SCORING_CHAPTER_MAX = 128;
    private static final int SCORING_SOURCE_MAX = 512;

    private void normalize(TenderRequirements r) {
        r.setProjectName(truncate(r.getProjectName(), TR_PROJECT_NAME_MAX));
        r.setLocation(truncate(r.getLocation(), TR_LOCATION_MAX));
        r.setDuration(truncate(r.getDuration(), TR_DURATION_MAX));
        r.setBudget(truncate(r.getBudget(), TR_BUDGET_MAX));
    }

    private void normalize(QualificationRequirement q) {
        q.setType(truncate(q.getType(), QUAL_TYPE_MAX));
        q.setLevel(truncate(q.getLevel(), QUAL_LEVEL_MAX));
        q.setValidRequirement(truncate(q.getValidRequirement(), QUAL_VALID_MAX));
        q.setSource(truncate(q.getSource(), QUAL_SOURCE_MAX));
    }

    private void normalize(PerformanceRequirement p) {
        p.setProjectType(truncate(p.getProjectType(), PERF_PROJECT_TYPE_MAX));
        p.setQuantity(truncate(p.getQuantity(), PERF_QUANTITY_MAX));
        p.setAmount(truncate(p.getAmount(), PERF_AMOUNT_MAX));
        p.setTimeRange(truncate(p.getTimeRange(), PERF_TIME_RANGE_MAX));
        p.setSource(truncate(p.getSource(), PERF_SOURCE_MAX));
    }

    private void normalize(PersonnelRequirement p) {
        p.setRole(truncate(p.getRole(), PERSONNEL_ROLE_MAX));
        p.setCertificate(truncate(p.getCertificate(), PERSONNEL_CERT_MAX));
        p.setSource(truncate(p.getSource(), PERSONNEL_SOURCE_MAX));
    }

    private void normalize(ScoringItem s) {
        s.setItemId(truncate(s.getItemId(), SCORING_ITEM_ID_MAX));
        s.setName(truncate(s.getName(), SCORING_NAME_MAX));
        s.setCriterion(truncate(s.getCriterion(), SCORING_CRITERION_MAX));
        s.setSuggestedChapter(truncate(s.getSuggestedChapter(), SCORING_CHAPTER_MAX));
        s.setSource(truncate(s.getSource(), SCORING_SOURCE_MAX));
    }

    /**
     * 按字符（code point）截断到目标长度：超长则保留前 max 个字符，
     * 避免插入时触发 "Data too long" 报错，也避免把代理对截成乱码。
     */
    private static String truncate(String value, int max) {
        return Texts.truncate(value, max);
    }
}