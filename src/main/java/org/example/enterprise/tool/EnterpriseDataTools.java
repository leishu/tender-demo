package org.example.enterprise.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import org.example.enterprise.model.Personnel;
import org.example.enterprise.model.ProjectPerformance;
import org.example.enterprise.model.Qualification;
import org.example.enterprise.repo.EnterpriseRepository;
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
            @ToolParam(name = "type", required = false,
                    description = "资质类型，如'ISO9001'。传空字符串或省略该参数均返回全部资质")
            String type) {
        return repository.findQualifications(type);
    }

    @Tool(name = "query_performances",
            description = "查询企业类似项目业绩。当需要引用过往案例时调用。")
    public List<ProjectPerformance> queryPerformances(
            @ToolParam(name = "keyword", required = false,
                    description = "项目关键词。传空字符串或省略该参数均返回全部业绩")
            String keyword) {
        return repository.findPerformances(keyword);
    }

    @Tool(name = "query_personnel",
            description = "查询企业人员及其可担任岗位、证书与职称。返回的每个人含 gender/birthDate/yearsOfExperience、"
                    + "roles（可担任岗位数组，一人可多岗）与 certificates（证书数组，一人多张；"
                    + "职称也是其中一条，type 为「职称」，major 为专业）。"
                    + "当需要确认项目团队配置、核对职称或证书要求时调用。")
    public List<Personnel> queryPersonnel(
            @ToolParam(name = "role", required = false,
                    description = "岗位关键词，如'项目经理'。按关键词模糊匹配；传空字符串或省略该参数均返回全部人员")
            String role) {
        return repository.findPersonnel(role);
    }
}
