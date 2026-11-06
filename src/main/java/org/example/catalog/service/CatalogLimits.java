package org.example.catalog.service;

/**
 * 投标目录落库时的字段长度上限，与建库脚本中的列定义对齐。
 *
 * <p>长度口径为字符（code point）：utf8mb4 的 {@code VARCHAR(n)} 约束字符数，
 * 所以校验与截断都用 {@code codePointCount}，不用 {@code String#length()}。</p>
 *
 * <p>生成阶段由 {@link CatalogNormalizer} 据此截断超长自由文本，保存阶段由
 * {@link CatalogValidator} 据此拒绝越界数据；两处共用同一份上限，改表结构时不必两头找。</p>
 */
final class CatalogLimits {

    /** t_bid_catalog.chapter_no VARCHAR(32) */
    static final int CHAPTER_NO_MAX = 32;
    /** t_bid_catalog.title VARCHAR(64) */
    static final int TITLE_MAX = 64;
    /** t_bid_catalog.content_hint VARCHAR(1024) */
    static final int CONTENT_HINT_MAX = 1024;
    /** t_bid_catalog_run.structure_decision VARCHAR(512) */
    static final int STRUCTURE_DECISION_MAX = 512;
    /** t_bid_catalog_gap.gap_type VARCHAR(32) */
    static final int GAP_TYPE_MAX = 32;
    /** t_bid_catalog_gap.detail VARCHAR(512) */
    static final int GAP_DETAIL_MAX = 512;
    /** t_bid_catalog_scoring_item.item_id VARCHAR(64) */
    static final int ITEM_ID_MAX = 64;

    private CatalogLimits() {
    }
}
