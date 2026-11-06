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

    /** 按资质类型查询，type 为空则返回全部 */
    public List<Qualification> findQualifications(String type) {
        LambdaQueryWrapper<Qualification> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(type)) {
            wrapper.eq(Qualification::getType, type);
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
     * 关键词用 like 而非等值，避免「项目负责人」查不到「项目经理」这类漏查。
     */
    public List<Personnel> findPersonnel(String role) {
        LambdaQueryWrapper<Personnel> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(role)) {
            List<Long> ids = roleMapper
                    .selectList(new LambdaQueryWrapper<PersonnelRole>().like(PersonnelRole::getRole, role))
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
