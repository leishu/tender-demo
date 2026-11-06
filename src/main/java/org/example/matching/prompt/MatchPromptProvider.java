package org.example.matching.prompt;

import org.example.parsing.model.PersonnelRequirement;
import org.example.parsing.model.QualificationRequirement;
import org.example.parsing.model.TenderRequirements;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;

/**
 * 人员匹配 / 企业资质匹配子 Agent 的运行时提示词。
 *
 * <p>子 Agent 的职责、匹配规则与输出格式写在
 * {@code agentscope-workspace/subagents/*.md}；这里只负责把本次待匹配的招标要求
 * 按 JSON 原样交给子 Agent，避免动态规则与静态规则重复维护。</p>
 */
@Component
public class MatchPromptProvider {

    private final JsonMapper mapper;

    public MatchPromptProvider(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** 人员匹配提示词：逐条给出招标人员要求，要求子 Agent 匹配一条就输出一条。 */
    public String renderPersonnel(TenderRequirements requirements,
                                  List<PersonnelRequirement> personnel) {
        return """
                ## 本次任务
                请将下列招标人员要求逐条与企业人员库匹配。

                ## 项目概况
                %s

                ## 当前日期
                %s（判断证书、职称有效期是否已过期的基准日）

                ## 待匹配人员要求（JSON，id 为 t_personnel_requirement 主键）
                %s

                ## 执行要求
                1. 必须调用 `query_personnel` 查询企业人员（可一次查询全部并复用），不得凭记忆编造人员、岗位或证书。
                2. 按输入顺序逐条匹配；每匹配完一条就立即输出一个 `personnelMatch` JSON 对象，
                   不要等全部匹配完再统一输出。
                3. 输入中的每一条要求都必须有且仅有一个结果对象；没有合适人员时也要输出
                   `status` 为 `UNMATCHED`、`candidates` 为空数组的结果。
                4. 只输出 JSON 对象本身，不要 Markdown 代码围栏、不要解释文字、不要输出数组外壳。
                """.formatted(projectSummary(requirements), LocalDate.now(), toJson(personnel));
    }

    /** 企业资质匹配提示词：逐条给出招标资质要求，要求子 Agent 匹配一条就输出一条。 */
    public String renderQualification(TenderRequirements requirements,
                                      List<QualificationRequirement> qualifications) {
        return """
                ## 本次任务
                请将下列招标企业资质要求逐条与企业资质库匹配。

                ## 项目概况
                %s

                ## 当前日期
                %s（判断资质有效期是否已过期的基准日）

                ## 待匹配资质要求（JSON，id 为 t_qualification_requirement 主键）
                %s

                ## 执行要求
                1. 必须调用 `query_qualifications` 查询企业资质（可一次查询全部并复用），不得凭记忆编造资质、等级或有效期。
                2. 按输入顺序逐条匹配；每匹配完一条就立即输出一个 `qualificationMatch` JSON 对象，
                   不要等全部匹配完再统一输出。
                3. 输入中的每一条要求都必须有且仅有一个结果对象；没有可用资质时也要输出
                   `status` 为 `UNMATCHED`、`candidates` 为空数组的结果。
                4. 只输出 JSON 对象本身，不要 Markdown 代码围栏、不要解释文字、不要输出数组外壳。
                """.formatted(projectSummary(requirements), LocalDate.now(), toJson(qualifications));
    }

    private String projectSummary(TenderRequirements requirements) {
        if (requirements == null) {
            return "（未提供项目概况）";
        }
        return """
                项目名称：%s
                建设地点：%s
                工期要求：%s
                预算/报价上限：%s
                """.formatted(nullToDash(requirements.getProjectName()),
                nullToDash(requirements.getLocation()),
                nullToDash(requirements.getDuration()),
                nullToDash(requirements.getBudget())).trim();
    }

    private String toJson(Object value) {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "未明确（原文未提供）" : value;
    }
}
