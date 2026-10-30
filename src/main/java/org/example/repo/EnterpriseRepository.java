package org.example.repo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.mapper.PersonnelMapper;
import org.example.mapper.ProjectPerformanceMapper;
import org.example.mapper.QualificationMapper;
import org.example.model.Personnel;
import org.example.model.ProjectPerformance;
import org.example.model.Qualification;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.List;

@Repository
public class EnterpriseRepository {
    private final QualificationMapper qualificationMapper;
    private final ProjectPerformanceMapper performanceMapper;
    private final PersonnelMapper personnelMapper;

    public EnterpriseRepository(QualificationMapper qualificationMapper,
                                ProjectPerformanceMapper performanceMapper,
                                PersonnelMapper personnelMapper) {
        this.qualificationMapper = qualificationMapper;
        this.performanceMapper = performanceMapper;
        this.personnelMapper = personnelMapper;
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

    /** 按角色查询，role 为空则返回全部 */
    public List<Personnel> findPersonnel(String role) {
        LambdaQueryWrapper<Personnel> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(role)) {
            wrapper.eq(Personnel::getRole, role);
        }
        return personnelMapper.selectList(wrapper);
    }
}
