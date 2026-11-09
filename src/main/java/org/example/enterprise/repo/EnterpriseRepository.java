package org.example.enterprise.repo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.enterprise.mapper.PersonnelCertificateMapper;
import org.example.enterprise.mapper.PersonnelMapper;
import org.example.enterprise.mapper.PersonnelRoleMapper;
import org.example.enterprise.mapper.ProjectPerformanceMapper;
import org.example.enterprise.mapper.QualificationMapper;
import org.example.enterprise.model.Personnel;
import org.example.enterprise.model.PersonnelCertificate;
import org.example.enterprise.model.PersonnelRole;
import org.example.enterprise.model.ProjectPerformance;
import org.example.enterprise.model.Qualification;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class EnterpriseRepository {
    private final QualificationMapper qualificationMapper;
    private final ProjectPerformanceMapper performanceMapper;
    private final PersonnelMapper personnelMapper;
    private final PersonnelRoleMapper roleMapper;
    private final PersonnelCertificateMapper certificateMapper;

    public EnterpriseRepository(QualificationMapper qualificationMapper,
                                ProjectPerformanceMapper performanceMapper,
                                PersonnelMapper personnelMapper,
                                PersonnelRoleMapper roleMapper,
                                PersonnelCertificateMapper certificateMapper) {
        this.qualificationMapper = qualificationMapper;
        this.performanceMapper = performanceMapper;
        this.personnelMapper = personnelMapper;
        this.roleMapper = roleMapper;
        this.certificateMapper = certificateMapper;
    }

    /**
     * 按资质关键词模糊查询，type 为空则返回全部。
     * 关键词同时匹配 type 与 name：招标写法与库中名称常有等级/后缀差异
     * （如“施工总承包”vs“建筑工程施工总承包壹级”），也可能用名称查编号类资质
     * （如“质量管理体系”vs type=ISO9001）。
     */
    public List<Qualification> findQualifications(String type) {
        LambdaQueryWrapper<Qualification> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(type)) {
            String keyword = type.trim();
            wrapper.and(w -> w.like(Qualification::getType, keyword)
                    .or()
                    .like(Qualification::getName, keyword));
        }
        return qualificationMapper.selectList(wrapper);
    }

    /** 按项目关键词模糊查询，keyword 为空则返回全部 */
    public List<ProjectPerformance> findPerformances(String keyword) {
        LambdaQueryWrapper<ProjectPerformance> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.like(ProjectPerformance::getProjectName, keyword);
        }
        return performanceMapper.selectList(wrapper);
    }

    /**
     * 按岗位关键词查询人员，role 为空则返回全部；每人带上岗位与证书。
     * 岗位在 t_personnel_role 中是一人多行，这里先按关键词捞出人员 id 再查主表，
     * 关键词用 like 而非等值，避免漏查。
     */
    public List<Personnel> findPersonnel(String role) {
        return findPersonnelByRoles(StringUtils.hasText(role) ? List.of(role.trim()) : List.of());
    }

    /**
     * 按一组岗位关键词查询人员（别名字典展开后的词组），任一关键词命中即可；
     * 词组为空返回全部。MySQL 只做普通 OR LIKE 过滤，语义归并在 Java 工具层完成。
     */
    public List<Personnel> findPersonnelByRoles(List<String> roleKeywords) {
        List<String> keywords = roleKeywords == null ? List.of()
                : roleKeywords.stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .distinct()
                        .toList();
        LambdaQueryWrapper<Personnel> wrapper = new LambdaQueryWrapper<>();
        if (!keywords.isEmpty()) {
            LambdaQueryWrapper<PersonnelRole> roleWrapper = new LambdaQueryWrapper<PersonnelRole>()
                    .and(w -> {
                        for (int i = 0; i < keywords.size(); i++) {
                            if (i > 0) {
                                w.or();
                            }
                            w.like(PersonnelRole::getRole, keywords.get(i));
                        }
                    });
            List<Long> ids = roleMapper.selectList(roleWrapper)
                    .stream()
                    .map(PersonnelRole::getPersonnelId)
                    .distinct()
                    .toList();
            if (ids.isEmpty()) {
                return List.of();
            }
            wrapper.in(Personnel::getId, ids);
        }
        List<Personnel> personnel = personnelMapper.selectList(wrapper);
        attachRoles(personnel);
        attachCertificates(personnel);
        return personnel;
    }

    /** 查询单个人员的岗位，personnelId 为空返回空列表 */
    public List<String> findRoles(Long personnelId) {
        if (personnelId == null) {
            return List.of();
        }
        return roleMapper.selectList(new LambdaQueryWrapper<PersonnelRole>()
                        .eq(PersonnelRole::getPersonnelId, personnelId)
                        .orderByAsc(PersonnelRole::getId))
                .stream()
                .map(PersonnelRole::getRole)
                .toList();
    }

    /** 查询单个人员的证书，personnelId 为空返回空列表 */
    public List<PersonnelCertificate> findCertificates(Long personnelId) {
        if (personnelId == null) {
            return List.of();
        }
        return certificateMapper.selectList(new LambdaQueryWrapper<PersonnelCertificate>()
                .eq(PersonnelCertificate::getPersonnelId, personnelId));
    }

    /** 批量装配岗位，一人多岗 */
    private void attachRoles(List<Personnel> personnel) {
        if (personnel.isEmpty()) {
            return;
        }
        List<Long> ids = personnel.stream().map(Personnel::getId).toList();
        Map<Long, List<String>> grouped = roleMapper
                .selectList(new LambdaQueryWrapper<PersonnelRole>()
                        .in(PersonnelRole::getPersonnelId, ids)
                        .orderByAsc(PersonnelRole::getId))
                .stream()
                .collect(Collectors.groupingBy(PersonnelRole::getPersonnelId,
                        Collectors.mapping(PersonnelRole::getRole, Collectors.toList())));
        personnel.forEach(p -> p.setRoles(grouped.getOrDefault(p.getId(), List.of())));
    }

    /** 批量装配证书，避免逐人查询 */
    private void attachCertificates(List<Personnel> personnel) {
        if (personnel.isEmpty()) {
            return;
        }
        List<Long> ids = personnel.stream().map(Personnel::getId).toList();
        Map<Long, List<PersonnelCertificate>> grouped = certificateMapper
                .selectList(new LambdaQueryWrapper<PersonnelCertificate>()
                        .in(PersonnelCertificate::getPersonnelId, ids))
                .stream()
                .collect(Collectors.groupingBy(PersonnelCertificate::getPersonnelId));
        personnel.forEach(p -> p.setCertificates(grouped.getOrDefault(p.getId(), List.of())));
    }
}
