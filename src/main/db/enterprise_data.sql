CREATE TABLE `t_qualification` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `type`          VARCHAR(64)  NULL COMMENT '资质类型，如 ISO9001',
    `name`          VARCHAR(255) NULL COMMENT '资质名称',
    `cert_no`       VARCHAR(128) NULL COMMENT '证书编号',
    `valid_until`   DATE         NULL COMMENT '证书有效期至',
    PRIMARY KEY (`id`),
    KEY `idx_type` (`type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '企业资质';


CREATE TABLE `t_project_performance` (
    `id`            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `project_name`  VARCHAR(255)   NULL COMMENT '项目名称',
    `client`        VARCHAR(255)   NULL COMMENT '业主/委托方',
    `amount`        DECIMAL(18, 2) NULL COMMENT '合同金额',
    `sign_date`     DATE           NULL COMMENT '完工/签合同日期',
    PRIMARY KEY (`id`),
    KEY `idx_project_name` (`project_name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '企业类似项目业绩';


CREATE TABLE `t_personnel` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name`                VARCHAR(64)  NULL COMMENT '姓名',
    `gender`              VARCHAR(8)   NULL COMMENT '性别：男 / 女，未知留空',
    `birth_date`          DATE         NULL COMMENT '出生年月，日期部分固定为 01',
    `years_of_experience` INT          NULL COMMENT '从业年限',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '企业人员';


CREATE TABLE `t_personnel_role` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `personnel_id` BIGINT      NOT NULL COMMENT '关联的 t_personnel 主键',
    `role`         VARCHAR(64) NOT NULL COMMENT '可担任的岗位，如 项目经理',
    PRIMARY KEY (`id`),
    KEY `idx_personnel_role` (`personnel_id`, `role`),
    KEY `idx_role` (`role`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '人员可担任岗位';


CREATE TABLE `t_personnel_certificate` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `personnel_id`  BIGINT       NOT NULL COMMENT '关联的 t_personnel 主键',
    `type`          VARCHAR(64)  NULL COMMENT '证书类型：职称 / 执业资格 / 岗位证书 等',
    `name`          VARCHAR(255) NULL COMMENT '证书名称，如 高级工程师（职称）、一级建造师',
    `major`         VARCHAR(128) NULL COMMENT '专业/等级，如 电气、机电工程',
    `cert_no`       VARCHAR(128) NULL COMMENT '证书编号',
    `valid_until`   DATE         NULL COMMENT '有效期至，长期有效留空（职称通常留空）',
    PRIMARY KEY (`id`),
    KEY `idx_personnel_id` (`personnel_id`),
    KEY `idx_type` (`type`),
    KEY `idx_name` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '人员证书与职称';

