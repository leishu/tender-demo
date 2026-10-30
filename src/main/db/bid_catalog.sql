-- =====================================================================
-- 投标目录相关表（对应 org.example.model.catalog 下的 CatalogResult / CatalogNode / CatalogGap）
--
--   CatalogResult.catalog / businessCatalog / technicalCatalog
--                              -> t_bid_catalog（按 catalog_category 区分归属）
--   CatalogResult.structureDecision / 是否分列
--                              -> t_bid_catalog_run
--   CatalogResult.gaps         -> t_bid_catalog_gap
--   CatalogNode.children       -> t_bid_catalog.parent_id 自关联，最多三级
--   CatalogNode.scoringItemIds -> t_bid_catalog_scoring_item
--
-- 说明：
--   1. 一次目录生成产出 1 条 t_bid_catalog_run，其后挂载章节树与缺口；
--   2. 分列时 business_catalog 落 catalog_category='BUSINESS'，
--      technical_catalog 落 catalog_category='TECHNICAL'；
--      合并时 catalog 落 catalog_category='COMMON'；
--   3. 章节树通过 parent_id 还原 children 嵌套，chapter_level 限制在 1~3；
--   4. 一个招标要求只保留一份目录：t_bid_catalog_run.tender_requirement_id 唯一，
--      重复保存时先删除旧批次（含章节、评分点关联、缺口）再写入新批次。
-- =====================================================================


CREATE TABLE `t_bid_catalog_run` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tender_requirement_id` BIGINT       NOT NULL COMMENT '关联的 t_tender_requirements 主键',
    `enterprise_id`         INT          NOT NULL COMMENT '企业ID（同 t_tender_requirements.enterprise_id）',
    `split_flag`            TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否商务/技术分列：0 合并目录，1 分列',
    `structure_decision`    VARCHAR(512) NULL COMMENT '结构判断说明：合并目录 / 商务技术分列及一句依据',
    `created_at`            BIGINT       NOT NULL COMMENT '创建时间(epoch ms)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tender_requirement_id` (`tender_requirement_id`),
    KEY `idx_enterprise_id` (`enterprise_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '投标目录生成批次（结构判断结果）';


CREATE TABLE `t_bid_catalog` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `catalog_run_id`   BIGINT        NOT NULL COMMENT '关联的 t_bid_catalog_run 主键',
    `parent_id`        BIGINT        NULL COMMENT '父章节主键，NULL 表示一级章节',
    `chapter_level`    TINYINT       NOT NULL DEFAULT 1 COMMENT '层级：1 一级、2 二级、3 三级，最多三级',
    `catalog_category` VARCHAR(16)   NOT NULL DEFAULT 'COMMON' COMMENT '目录归属：COMMON 合并目录、BUSINESS 商务标、TECHNICAL 技术标',
    `chapter_no`       VARCHAR(32)   NOT NULL COMMENT '章节编号，与嵌套位置一致：1、1.1、1.1.1',
    `title`            VARCHAR(64)   NOT NULL COMMENT '章节标题，不超过 20 字',
    `content_hint`     VARCHAR(1024) NULL COMMENT '内容要点提示，1-2 句',
    `sort_no`          INT           NOT NULL DEFAULT 0 COMMENT '同级排序号，按 chapter_no 逐段数字升序',
    `created_at`       BIGINT        NOT NULL COMMENT '创建时间(epoch ms)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_category_chapter_no` (`catalog_run_id`, `catalog_category`, `chapter_no`),
    KEY `idx_run_parent` (`catalog_run_id`, `parent_id`),
    CONSTRAINT `ck_bid_catalog_chapter_level` CHECK (`chapter_level` BETWEEN 1 AND 3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '投标目录章节（parent_id 自关联，最多三级）';


CREATE TABLE `t_bid_catalog_scoring_item` (
    `id`              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `catalog_id`      BIGINT      NOT NULL COMMENT '关联的 t_bid_catalog 主键',
    `scoring_item_id` BIGINT      NULL COMMENT '关联的 t_scoring_item 主键，按 item_id 回填；无法匹配时为 NULL',
    `item_id`         VARCHAR(64) NOT NULL COMMENT '评分点 id，原样引用 t_scoring_item.item_id',
    `sort_no`         INT         NOT NULL DEFAULT 0 COMMENT '同一章节内评分点排序号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_catalog_item_id` (`catalog_id`, `item_id`),
    KEY `idx_scoring_item_id` (`scoring_item_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '投标目录与评分点关联';


CREATE TABLE `t_bid_catalog_gap` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `catalog_run_id` BIGINT       NOT NULL COMMENT '关联的 t_bid_catalog_run 主键',
    `gap_type`       VARCHAR(32)  NOT NULL COMMENT '缺口类型：data_not_found / missing_scoring_item / missing_requirement',
    `detail`         VARCHAR(512) NULL COMMENT '缺口详情',
    `created_at`     BIGINT       NOT NULL COMMENT '创建时间(epoch ms)',
    PRIMARY KEY (`id`),
    KEY `idx_catalog_run_id` (`catalog_run_id`),
    KEY `idx_gap_type` (`gap_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '投标目录缺口说明';
