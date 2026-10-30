package org.example.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import org.example.model.Personnel;
import org.example.model.ProjectPerformance;
import org.example.model.Qualification;
import org.example.repo.EnterpriseRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EnterpriseDataTools {
    private final EnterpriseRepository repository;

    public EnterpriseDataTools(EnterpriseRepository repository) {
        this.repository = repository;
    }

    @Tool(name = "query_qualifications",
            description = "查询企业资质列表。当需要确认企业是否具备某项资质时调用。")
    public List<Qualification> queryQualifications(
            @ToolParam(name = "type", description = "资质类型，如'ISO9001'。不传则返回全部")
            String type) {
        return repository.findQualifications(type);
    }

    @Tool(name = "query_performances",
            description = "查询企业类似项目业绩。当需要引用过往案例时调用。")
    public List<ProjectPerformance> queryPerformances(
            @ToolParam(name = "keyword", description = "项目关键词")
            String keyword) {
        return repository.findPerformances(keyword);
    }

    @Tool(name = "query_personnel",
            description = "查询企业人员及其资质证书。当需要确认项目团队配置时调用。")
    public List<Personnel> queryPersonnel(
            @ToolParam(name = "role", description = "角色，如'项目经理'")
            String role) {
        return repository.findPersonnel(role);
    }
}
