package org.example.prompts;

import org.example.model.catalog.dto.CatalogResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@Component
public class CatalogPromptProvider {

    /** 基础模板末尾的自查章节，调整规则需插入到它之前，保持自查位于最后 */
    private static final String SELF_CHECK_HEADING = "## 自查";

    /** 调整模式规则：说明本条消息的组成，并约束如何在上一轮目录上调整 */
    private static final String ADJUSTMENT_RULES = """
            ## 调整模式

            本次请求为目录调整：上文是目录生成规则，本条消息后续还有两个独立内容块——
            「上一轮生成的目录」（JSON，字段含义与上文输出格式一致）与「用户调整要求」。
            请在上一轮目录的基础上输出调整后的完整目录（不要只输出差异），并遵循：

            1. 角色、结构判断与输出格式仍按上文规则执行，除 JSON 外不输出任何解释性文字；
            2. 用户未要求修改的章节，保留其 chapter_no、title、scoring_item_ids、content_hint 与层级位置；
            3. 新增、删除、移动或改名章节后，按新结构重排 chapter_no，保证与嵌套位置一致、同级升序且不留空号；
            4. 调整后仍须满足评分点覆盖、最多三级、输出格式与自查要求；因调整产生的缺口写入 gaps；
            5. 不得虚构评分点，scoring_item_ids 只能引用上一轮目录与招标要求中已有的 item_id。

            """;

    private final String template;
    private final JsonMapper mapper;

    public CatalogPromptProvider(@Value("classpath:prompts/catalog-generation.md") Resource resource,
                                 JsonMapper mapper) throws IOException {
        this.template = Files.readString(resource.getFile().toPath(), StandardCharsets.UTF_8);
        this.mapper = mapper;
    }

    public String render(Long tenderRequirementId) {
        return template.replace("{{id}}", String.valueOf(tenderRequirementId));
    }

    /**
     * 渲染目录调整提示词：拆成“生成规则（含调整规则）”“上一轮目录”“用户调整要求”三块，
     * 供调用方分别封装为独立 ContentBlock，避免拼成一段超长文本。
     *
     * @param tenderRequirementId 招标要求主键
     * @param previousCatalog     上一轮生成的目录，可为空
     * @param customPrompt        用户调整要求
     * @return 分块后的提示词
     */
    public CatalogAdjustPrompt renderAdjustment(Long tenderRequirementId, CatalogResult previousCatalog,
                                                String customPrompt) {
        return new CatalogAdjustPrompt(renderAdjustmentRules(tenderRequirementId),
                formatPreviousCatalog(previousCatalog),
                formatCustomPrompt(customPrompt));
    }

    /** 在生成规则中插入调整模式说明，自查章节仍保持在最后。 */
    private String renderAdjustmentRules(Long tenderRequirementId) {
        String prompt = render(tenderRequirementId);
        int index = prompt.indexOf(SELF_CHECK_HEADING);
        if (index < 0) {
            return prompt + "\n" + ADJUSTMENT_RULES;
        }
        return prompt.substring(0, index) + ADJUSTMENT_RULES + "\n" + prompt.substring(index);
    }

    /** 上一轮目录内容块：以 JSON 原样呈现，字段含义与生成规则中的输出格式一致。 */
    private String formatPreviousCatalog(CatalogResult previousCatalog) {
        StringBuilder sb = new StringBuilder("## 上一轮生成的目录\n\n");
        if (previousCatalog == null || (!previousCatalog.isSplit()
                && (previousCatalog.getCatalog() == null || previousCatalog.getCatalog().isEmpty()))) {
            return sb.append("请求未提供可用的上一轮目录，请直接依据用户调整要求重新生成完整目录。\n")
                    .toString();
        }
        return sb.append("以下是上一轮生成的完整目录，请在其基础上调整：\n\n")
                .append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(previousCatalog))
                .append("\n")
                .toString();
    }

    /** 用户调整要求内容块。 */
    private String formatCustomPrompt(String customPrompt) {
        return "## 用户调整要求\n\n（优先执行，与其他规则冲突时以本块为准）\n\n"
                + (customPrompt == null || customPrompt.isBlank() ? "（未提供）" : customPrompt.trim())
                + "\n";
    }

    /**
     * 目录调整提示词的分块结果，每个字段对应一个独立的文本内容块。
     *
     * @param rules          目录生成规则 + 调整模式规则
     * @param previousCatalog 上一轮生成的目录
     * @param customPrompt   用户调整要求
     */
    public record CatalogAdjustPrompt(String rules, String previousCatalog, String customPrompt) {
    }
}