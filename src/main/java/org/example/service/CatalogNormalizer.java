package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.model.catalog.dto.CatalogGap;
import org.example.model.catalog.dto.CatalogNode;
import org.example.model.catalog.dto.CatalogResult;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 模型输出在保存前校验之前的确定性修正。
 *
 * <p>只修正能由结构或列宽唯一确定的内容，尽量把“格式不对就重新调用模型”变成“就地改好”：</p>
 * <ol>
 *   <li>章节编号按嵌套位置重排：第 n 个同级章节编号为 {@code 父编号.n}，一级为 {@code n}；</li>
 *   <li>内容提示、结构判断说明、缺口详情超出列宽时截断，避免仅因长度超限就重新生成整个目录。</li>
 * </ol>
 *
 * <p>编号只在“能由结构唯一确定”时重排：编号为空，或点分段数与所在层级一致。段数与层级不一致
 * （例如顶层写 {@code 1.1}、二级写 {@code 1}）说明模型把层级压平了，属于结构错误，重排只会掩盖问题，
 * 保持原样交给 {@link CatalogValidator} 报出，让模型重生成。</p>
 *
 * <p>标题、缺口类型、评分点 id 不做截断：标题是直接展示的章节名，截断会得到半句话；缺口类型是枚举值、
 * 评分点 id 用于跨表引用，截断会破坏语义。这几类超限仍按校验失败处理。</p>
 *
 * <p>仅用于目录生成与调整流程。保存接口不经过本类，客户端提交的数据仍由
 * {@link CatalogValidator} 严格校验，不会在落库时被悄悄改写。</p>
 */
@Slf4j
@Component
public class CatalogNormalizer {

    /** 与落库、校验一致：最多三级 */
    private static final int MAX_CHAPTER_LEVEL = 3;

    /**
     * 就地规范化模型输出的目录：重排章节编号，截断超长自由文本。
     *
     * @param result 解析后的目录结果
     */
    public void normalize(CatalogResult result) {
        int changed = result.isSplit()
                ? renumber(result.getBusinessCatalog(), null, 1)
                + renumber(result.getTechnicalCatalog(), null, 1)
                : renumber(result.getCatalog(), null, 1);
        if (changed > 0) {
            log.info("投标目录章节编号已按嵌套位置重排 {} 处", changed);
        }
        result.setStructureDecision(truncate("的结构判断说明",
                result.getStructureDecision(), CatalogLimits.STRUCTURE_DECISION_MAX));
        truncateGaps(result.getGaps());
    }

    /**
     * 逐层重排章节编号，编号由嵌套位置唯一确定。
     *
     * @param parentChapterNo 父章节编号，一级章节为 {@code null}
     * @param level           当前层级，从 1 开始
     * @return 被改写的编号数量
     */
    private int renumber(List<CatalogNode> nodes, String parentChapterNo, int level) {
        if (CollectionUtils.isEmpty(nodes) || level > MAX_CHAPTER_LEVEL) {
            return 0;
        }
        int changed = 0;
        int sortNo = 0;
        for (CatalogNode node : nodes) {
            if (node == null) {
                continue;
            }
            sortNo++;
            String expectedNo = parentChapterNo == null
                    ? String.valueOf(sortNo)
                    : parentChapterNo + "." + sortNo;
            String actualNo = node.getChapterNo();
            if (isRepairable(actualNo, level)) {
                if (!expectedNo.equals(actualNo)) {
                    log.debug("投标目录章节编号按嵌套位置重排：{} -> {}", actualNo, expectedNo);
                    node.setChapterNo(expectedNo);
                    changed++;
                }
            }
            node.setContentHint(truncate("第 " + expectedNo + " 章的内容提示",
                    node.getContentHint(), CatalogLimits.CONTENT_HINT_MAX));
            // 层级压平的节点保持原编号，但子节仍按树的期望位置继续编号，交给校验整体报出
            changed += renumber(node.getChildren(), expectedNo, level + 1);
        }
        return changed;
    }

    /** 编号为空、或点分段数与所在层级一致时，可由嵌套位置直接确定 */
    private boolean isRepairable(String chapterNo, int level) {
        if (!StringUtils.hasText(chapterNo)) {
            return true;
        }
        String trimmed = chapterNo.trim();
        int segments = 1;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == '.') {
                segments++;
            }
        }
        return segments == level;
    }

    /** 缺口详情属于自由文本，超列宽时截断；类型是枚举值，超限交给校验 */
    private void truncateGaps(List<CatalogGap> gaps) {
        if (CollectionUtils.isEmpty(gaps)) {
            return;
        }
        for (int i = 0; i < gaps.size(); i++) {
            CatalogGap gap = gaps.get(i);
            if (gap == null) {
                // 落库时同样跳过 null 元素
                continue;
            }
            gap.setDetail(truncate("缺口第 " + (i + 1) + " 项的缺口详情",
                    gap.getDetail(), CatalogLimits.GAP_DETAIL_MAX));
        }
    }

    /**
     * 按字符（code point）截断超长文本到列宽上限；null 原样返回。
     *
     * <p>不能直接用 {@link String#length()} 判长与 {@code substring(0, max)} 截断：两者按 UTF-16
     * code unit 计，含代理对字符（emoji 等）时既会比落库口径多算长度，也可能把代理对切成孤立代理。
     * {@code offsetByCodePoints} 保证截断点落在字符边界上。</p>
     */
    private String truncate(String fieldDesc, String value, int max) {
        if (value == null) {
            return null;
        }
        int length = value.codePointCount(0, value.length());
        if (length <= max) {
            return value;
        }
        log.warn("投标目录{}超长，已截断：长度 {}，上限 {}", fieldDesc, length, max);
        return value.substring(0, value.offsetByCodePoints(0, max));
    }
}
