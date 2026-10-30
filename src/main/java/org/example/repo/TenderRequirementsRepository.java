package org.example.repo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.mapper.PerformanceRequirementMapper;
import org.example.mapper.PersonnelRequirementMapper;
import org.example.mapper.QualificationRequirementMapper;
import org.example.mapper.ScoringItemMapper;
import org.example.mapper.TenderRequirementsMapper;
import org.example.model.require.PerformanceRequirement;
import org.example.model.require.PersonnelRequirement;
import org.example.model.require.QualificationRequirement;
import org.example.model.require.ScoringItem;
import org.example.model.require.TenderRequirements;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;

import java.util.List;

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
     */
    public TenderRequirements save(TenderRequirements requirements) {
        normalize(requirements);
        tenderRequirementsMapper.insert(requirements);
        Long reqId = requirements.getId();
        saveQualifications(reqId, requirements.getEnterpriseId(), requirements.getQualifications());
        savePerformances(reqId, requirements.getEnterpriseId(), requirements.getPerformances());
        savePersonnel(reqId, requirements.getEnterpriseId(), requirements.getPersonnel());
        saveScoringItems(reqId, requirements.getEnterpriseId(), requirements.getScoringItems());
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

    private void saveQualifications(Long reqId, Long enterpriseId, List<QualificationRequirement> list) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        for (QualificationRequirement item : list) {
            item.setTenderRequirementId(reqId);
            item.setEnterpriseId(enterpriseId);
            normalize(item);
            qualificationRequirementMapper.insert(item);
        }
    }

    private void savePerformances(Long reqId, Long enterpriseId, List<PerformanceRequirement> list) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        for (PerformanceRequirement item : list) {
            item.setTenderRequirementId(reqId);
            item.setEnterpriseId(enterpriseId);
            normalize(item);
            performanceRequirementMapper.insert(item);
        }
    }

    private void savePersonnel(Long reqId, Long enterpriseId, List<PersonnelRequirement> list) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        for (PersonnelRequirement item : list) {
            item.setTenderRequirementId(reqId);
            item.setEnterpriseId(enterpriseId);
            normalize(item);
            personnelRequirementMapper.insert(item);
        }
    }

    private void saveScoringItems(Long reqId, Long enterpriseId, List<ScoringItem> list) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        for (ScoringItem item : list) {
            item.setTenderRequirementId(reqId);
            item.setEnterpriseId(enterpriseId);
            normalize(item);
            scoringItemMapper.insert(item);
        }
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

    /** 按字符截断到目标长度：超长则保留前 max 个字符，避免插入时触发 "Data too long" 报错 */
    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}