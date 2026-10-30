CREATE TABLE `t_qualification` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `enterprise_id` BIGINT       NOT NULL COMMENT '企业ID',
    `type`          VARCHAR(64)  NULL COMMENT '资质类型，如 ISO9001',
    `name`          VARCHAR(255) NULL COMMENT '资质名称',
    `cert_no`       VARCHAR(128) NULL COMMENT '证书编号',
    `valid_until`   DATE         NULL COMMENT '证书有效期至',
    PRIMARY KEY (`id`),
    KEY `idx_enterprise_id` (`enterprise_id`),
    KEY `idx_type` (`type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '企业资质';


CREATE TABLE `t_project_performance` (
    `id`            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `enterprise_id` BIGINT         NOT NULL COMMENT '企业ID',
    `project_name`  VARCHAR(255)   NULL COMMENT '项目名称',
    `client`        VARCHAR(255)   NULL COMMENT '业主/委托方',
    `amount`        DECIMAL(18, 2) NULL COMMENT '合同金额',
    `sign_date`     DATE           NULL COMMENT '完工/签合同日期',
    PRIMARY KEY (`id`),
    KEY `idx_enterprise_id` (`enterprise_id`),
    KEY `idx_project_name` (`project_name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '企业类似项目业绩';


CREATE TABLE `t_personnel` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `enterprise_id`       BIGINT       NOT NULL COMMENT '企业ID',
    `name`                VARCHAR(64)  NULL COMMENT '姓名',
    `role`                VARCHAR(64)  NULL COMMENT '角色，如 项目经理',
    `title`               VARCHAR(255) NULL COMMENT '职称/资格证书',
    `years_of_experience` INT          NULL COMMENT '从业年限',
    PRIMARY KEY (`id`),
    KEY `idx_enterprise_id` (`enterprise_id`),
    KEY `idx_role` (`role`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '企业人员及其资质';

