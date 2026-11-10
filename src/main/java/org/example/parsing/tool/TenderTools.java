package org.example.parsing.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.example.parsing.model.TenderRequirements;
import org.example.parsing.repo.TenderRequirementsRepository;
import org.example.parsing.service.TenderRequirementsService;
import org.springframework.stereotype.Component;

@Component
public class TenderTools {
    private final TenderRequirementsService tenderRequirementsService;
    private final TenderRequirementsRepository tenderRequirementsRepository;

    public TenderTools(TenderRequirementsService tenderRequirementsService,
                       TenderRequirementsRepository tenderRequirementsRepository) {
        this.tenderRequirementsService = tenderRequirementsService;
        this.tenderRequirementsRepository = tenderRequirementsRepository;
    }

    @Tool(name = "save_tender_requirements",
            description = "保存招标文件解析出的结构化要求（含项目概况、资质、业绩、人员、评分点及子表），返回带自增id的完整对象。")
    public TenderRequirements saveTenderRequirements(
            @ToolParam(name = "requirements", description = "招标结构化要求对象")
            TenderRequirements requirements) {
        return tenderRequirementsService.save(requirements);
    }

    @Tool(name = "get_tender_requirements",
            description = "按id查询招标结构化要求，含资质/业绩/人员/评分点子表；不存在返回null。")
    public TenderRequirements getTenderRequirements(
            @ToolParam(name = "id", description = "招标结构化要求主键id")
            Long id) {
        return tenderRequirementsRepository.findById(id);
    }

    @Tool(name = "delete_tender_requirements",
            description = "按id删除招标结构化要求，级联删除四张子表。")
    public void deleteTenderRequirements(
            @ToolParam(name = "id", description = "招标结构化要求主键id")
            Long id) {
        // 走 service 而不是直接调仓储：删除需要事务保证主表与子表一致
        tenderRequirementsService.deleteById(id);
    }
}