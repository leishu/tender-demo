CREATE TABLE `t_tender_requirements` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `project_name`  VARCHAR(255) NULL COMMENT '项目名称',
    `location`      VARCHAR(255) NULL COMMENT '建设地点',
    `duration`      VARCHAR(64)  NULL COMMENT '工期要求，如“180 日历天”',
    `budget`        VARCHAR(128) NULL COMMENT '预算/报价上限（金额+币种）',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '招标文件结构化要求';


CREATE TABLE `t_qualification_requirement` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tender_requirement_id` BIGINT      NULL COMMENT '关联的 TenderRequirements 主键',
    `type`                 VARCHAR(128) NULL COMMENT '资质类型，如 ISO9001、施工总承包一级',
    `level`                VARCHAR(64)  NULL COMMENT '资质等级',
    `valid_requirement`    VARCHAR(255) NULL COMMENT '有效期要求',
    `source`               VARCHAR(512) NULL COMMENT '原文出处片段',
    PRIMARY KEY (`id`),
    KEY `idx_tender_requirement_id` (`tender_requirement_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '招标资质要求';


CREATE TABLE `t_performance_requirement` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tender_requirement_id` BIGINT       NULL COMMENT '关联的 TenderRequirements 主键',
    `project_type`          VARCHAR(255) NULL COMMENT '类似项目类型描述',
    `quantity`              VARCHAR(64)  NULL COMMENT '数量要求，如“1 个”',
    `amount`                VARCHAR(128) NULL COMMENT '单项目或累计金额下限',
    `time_range`            VARCHAR(128) NULL COMMENT '业绩时间范围，如“近三年（2022-2025）”',
    `source`                VARCHAR(512) NULL COMMENT '原文出处片段',
    PRIMARY KEY (`id`),
    KEY `idx_tender_requirement_id` (`tender_requirement_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '招标业绩要求';


CREATE TABLE `t_personnel_requirement` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tender_requirement_id` BIGINT       NULL COMMENT '关联的 TenderRequirements 主键',
    `role`                  VARCHAR(64)  NULL COMMENT '岗位名称，如 项目经理、技术负责人',
    `quantity`              INT          NULL COMMENT '人数要求',
    `certificate`           VARCHAR(255) NULL COMMENT '证书/职称要求，如 一级建造师',
    `source`                VARCHAR(512) NULL COMMENT '原文出处片段',
    PRIMARY KEY (`id`),
    KEY `idx_tender_requirement_id` (`tender_requirement_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '招标人员要求';


CREATE TABLE `t_scoring_item` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tender_requirement_id` BIGINT       NULL COMMENT '关联的 TenderRequirements 主键',
    `item_id`               VARCHAR(64)  NULL COMMENT '评分点 id',
    `name`                  VARCHAR(128) NULL COMMENT '评分点名称',
    `score`                 INT          NULL COMMENT '分值',
    `criterion`             VARCHAR(512) NULL COMMENT '评审标准',
    `suggested_chapter`     VARCHAR(128) NULL COMMENT '建议承载章节',
    `source`                VARCHAR(512) NULL COMMENT '原文出处片段',
    PRIMARY KEY (`id`),
    KEY `idx_tender_requirement_id` (`tender_requirement_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '招标评分点';

