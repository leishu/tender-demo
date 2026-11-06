package org.example.catalog.service;

import org.example.catalog.model.CatalogCategory;
import org.example.catalog.model.dto.CatalogGap;
import org.example.catalog.model.dto.CatalogNode;
import org.example.catalog.model.dto.CatalogResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 投标目录保存前的数据校验。
 *
 * <p>当前校验章节编号：编号需与嵌套位置一致（1、1.1、1.1.1），同级从 1 开始
 * 连续递增，不跳号、不重号、不带前导零。两套目录分列时分别校验商务标与技术标；
 * 超过三级的子节点不落库，也不参与校验。</p>
 *
 * <p>同时校验非空与列宽：标题、缺口类型、评分点 id 不得为空，各字段长度不得超过
 * 建库脚本中对应列的宽度，避免落库时才被数据库拒绝或被静默截断。</p>
 */
@Component
public class CatalogValidator {

    /** 章节编号格式：各段为不带前导零的正整数，段间以点分隔 */
    private static final Pattern CHAPTER_NO_PATTERN = Pattern.compile("^[1-9]\\d*(\\.[1-9]\\d*)*$");

    /** 与落库一致：最多三级 */
    private static final int MAX_CHAPTER_LEVEL = 3;

    /**
     * 校验目录结果的章节编号。
     *
     * @throws ResponseStatusException 编号缺失、格式不合法、层级不匹配或同级不连续时抛出（400）
     */
    public void validate(CatalogResult result) {
        if (result.isSplit()) {
            validateLevel(CatalogCategory.BUSINESS, result.getBusinessCatalog(), null, 1);
            validateLevel(CatalogCategory.TECHNICAL, result.getTechnicalCatalog(), null, 1);
        } else {
            validateLevel(CatalogCategory.COMMON, result.getCatalog(), null, 1);
        }
        validateMaxLength("目录的结构判断说明", result.getStructureDecision(), CatalogLimits.STRUCTURE_DECISION_MAX);
        validateGaps(result.getGaps());
    }

    /**
     * 逐层校验：同级序号从 1 连续递增，编号必须等于父编号 + "." + 序号（一级等于序号）。
     *
     * @param parentChapterNo 父章节编号，一级章节为 {@code null}
     * @param level           当前层级，从 1 开始
     */
    private void validateLevel(CatalogCategory category, List<CatalogNode> nodes,
                               String parentChapterNo, int level) {
        if (CollectionUtils.isEmpty(nodes) || level > MAX_CHAPTER_LEVEL) {
            return;
        }
        int sortNo = 0;
        for (CatalogNode node : nodes) {
            if (node == null) {
                continue;
            }
            sortNo++;
            String expectedNo = parentChapterNo == null
                    ? String.valueOf(sortNo)
                    : parentChapterNo + "." + sortNo;

            String actualNo = node.getChapterNo() == null ? null : node.getChapterNo().trim();
            if (!isFormatValid(actualNo, level)) {
                throw invalid(category, String.format(
                        "第 %s 个章节的位置期望为 %s，实际为 %s（应形如 1、1.1、1.1.1，与嵌套位置一致且不带前导零）",
                        sortNo, expectedNo, actualNo == null || actualNo.isEmpty() ? "空" : actualNo));
            }
            if (!actualNo.equals(expectedNo)) {
                throw invalid(category, String.format(
                        "第 %s 个章节的位置期望为 %s，实际为 %s（同级需从 1 连续递增，不能跳号、重号）",
                        sortNo, expectedNo, actualNo));
            }
            // 去掉首尾空白后回写，保证落库值与校验值一致
            node.setChapterNo(actualNo);
            validateMaxLength(category.getLabel() + "第 " + actualNo + " 章的编号", actualNo, CatalogLimits.CHAPTER_NO_MAX);
            validateNodeFields(category, actualNo, node);
            validateLevel(category, node.getChildren(), actualNo, level + 1);
        }
    }

    /** 编号格式合法且段数等于所在层级 */
    private boolean isFormatValid(String chapterNo, int level) {
        if (chapterNo == null || !CHAPTER_NO_PATTERN.matcher(chapterNo).matches()) {
            return false;
        }
        int segments = 1;
        for (int i = 0; i < chapterNo.length(); i++) {
            if (chapterNo.charAt(i) == '.') {
                segments++;
            }
        }
        return segments == level;
    }

    /** 校验章节必填字段与列宽 */
    private void validateNodeFields(CatalogCategory category, String chapterNo, CatalogNode node) {
        String prefix = category.getLabel() + "第 " + chapterNo + " 章";
        if (!StringUtils.hasText(node.getTitle())) {
            throw badRequest(prefix + "的标题不能为空");
        }
        validateMaxLength(prefix + "的标题", node.getTitle(), CatalogLimits.TITLE_MAX);
        validateMaxLength(prefix + "的内容提示", node.getContentHint(), CatalogLimits.CONTENT_HINT_MAX);
        validateScoringItems(prefix, node.getScoringItemIds());
    }

    /** 校验章节承载的评分点 id：不能为空，宽度不超过 item_id 列 */
    private void validateScoringItems(String prefix, List<String> itemIds) {
        if (CollectionUtils.isEmpty(itemIds)) {
            return;
        }
        for (String itemId : itemIds) {
            if (!StringUtils.hasText(itemId)) {
                throw badRequest(prefix + "的评分点 id 不能为空");
            }
            validateMaxLength(prefix + "的评分点 id（" + itemId + "）", itemId, CatalogLimits.ITEM_ID_MAX);
        }
    }

    /** 校验缺口说明：类型必填，类型与详情不超列宽 */
    private void validateGaps(List<CatalogGap> gaps) {
        if (CollectionUtils.isEmpty(gaps)) {
            return;
        }
        for (int i = 0; i < gaps.size(); i++) {
            CatalogGap gap = gaps.get(i);
            if (gap == null) {
                // 落库时同样跳过 null 元素
                continue;
            }
            String prefix = "目录缺口第 " + (i + 1) + " 项";
            if (!StringUtils.hasText(gap.getType())) {
                throw badRequest(prefix + "的缺口类型不能为空");
            }
            validateMaxLength(prefix + "的缺口类型", gap.getType(), CatalogLimits.GAP_TYPE_MAX);
            validateMaxLength(prefix + "的缺口详情", gap.getDetail(), CatalogLimits.GAP_DETAIL_MAX);
        }
    }

    /** 可选字段的列宽校验：null 视为未填写；长度按字符（code point）计，与列宽口径一致 */
    private void validateMaxLength(String fieldDesc, String value, int max) {
        if (value == null) {
            return;
        }
        int length = textLength(value);
        if (length > max) {
            throw badRequest(String.format("%s超长：长度 %d，上限 %d", fieldDesc, length, max));
        }
    }

    /**
     * 文本长度按字符（code point）计：utf8mb4 的 {@code VARCHAR(n)} 约束的是字符数，
     * 含代理对字符（emoji 等）时 Java 的 {@link String#length()} 会把长度算成两倍。
     */
    private int textLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException invalid(CatalogCategory category, String detail) {
        return badRequest(category.getLabel() + "目录章节编号不合法：" + detail);
    }
}
