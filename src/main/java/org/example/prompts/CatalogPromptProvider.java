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

    /** 基础模板末尾的自查章节，调整要求需插入到它之前，保持自查位于最后 */
    private static final String SELF_CHECK_HEADING = "## 自查";

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
     * 渲染目录调整提示词：在原生成规则基础上追加上一轮目录与用户调整要求。
     *
     * @param tenderRequirementId 招标要求主键
     * @param previousCatalog     上一轮生成的目录，可为空
     * @param customPrompt        用户调整要求
     * @return 完整的提示词
     */
    public String renderAdjustment(Long tenderRequirementId, CatalogResult previousCatalog, String customPrompt) {
        String prompt = render(tenderRequirementId);
        String adjustment = buildAdjustment(previousCatalog, customPrompt);
        int index = prompt.indexOf(SELF_CHECK_HEADING);
        if (index < 0) {
            return prompt + "\n\n" + adjustment;
        }
        return prompt.substring(0, index) + adjustment + "\n" + prompt.substring(index);
    }

    /** 拼装“上一轮目录 + 用户调整要求”章节。 */
    private String buildAdjustment(CatalogResult previousCatalog, String customPrompt) {
        StringBuilder sb = new StringBuilder("## 上一轮生成的目录\n\n");
        if (previousCatalog == null || (!previousCatalog.isSplit()
                && (previousCatalog.getCatalog() == null || previousCatalog.getCatalog().isEmpty()))) {
            sb.append("请求未提供可用的上一轮目录，请直接依据下方用户调整要求重新生成完整目录。\n\n");
        } else {
            sb.append("以下是上一轮生成的完整目录，字段含义与上文输出格式一致，请在其基础上调整：\n\n")
                    .append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(previousCatalog))
                    .append("\n\n");
        }
        sb.append("""
                ## 用户调整要求

                请在上一轮目录基础上按要求调整，并输出调整后的完整目录（不要只输出差异）：
                1. 用户未要求修改的章节，保留其 chapter_no、title、scoring_item_ids、content_hint 与层级位置；
                2. 新增、删除、移动或改名章节后，按新结构重排 chapter_no，保证与嵌套位置一致、同级升序且不留空号；
                3. 调整后仍须满足评分点覆盖、最多三级、输出格式与自查要求；因调整产生的缺口写入 gaps；
                4. 不得虚构评分点，scoring_item_ids 只能引用上一轮目录与招标要求中已有的 item_id。

                用户调整要求（优先执行）：

                """);
        sb.append(customPrompt == null || customPrompt.isBlank() ? "（未提供）" : customPrompt.trim());
        sb.append("\n\n");
        return sb.toString();
    }
}