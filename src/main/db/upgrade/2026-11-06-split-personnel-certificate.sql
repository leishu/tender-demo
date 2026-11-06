-- =====================================================================
-- 一次性升级脚本：拆分 t_personnel，证书独立成表
--
-- 人员与证书由一对一变为一对多：t_personnel 只保留人的属性，证书移到
-- t_personnel_certificate，通过 personnel_id 关联。
--
-- 旧字段 title 语义混合（职称 / 资格证书），脚本把非空值整体迁成一条
-- 证书记录（type 置空），title 原地保留并重新定义为「职称」，迁移后请
-- 人工核对：确实是职称的，把刚生成的那条证书删掉。
--
-- 仅对已经按旧版 DDL 建过库的环境执行；全新环境直接执行
-- src/main/db/enterprise_data.sql 即可，不需要本脚本。
--
-- 执行：mysql -u root -p tender_demo < src/main/db/upgrade/2026-11-06-split-personnel-certificate.sql
-- =====================================================================

CREATE TABLE IF NOT EXISTS `t_personnel_certificate` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `personnel_id`  BIGINT       NOT NULL COMMENT '关联的 t_personnel 主键',
    `type`          VARCHAR(64)  NULL COMMENT '证书类型，如 执业资格 / 岗位证书',
    `name`          VARCHAR(255) NULL COMMENT '证书名称，如 一级建造师',
    `major`         VARCHAR(128) NULL COMMENT '专业/等级，如 机电工程',
    `cert_no`       VARCHAR(128) NULL COMMENT '证书编号',
    `valid_until`   DATE         NULL COMMENT '有效期至，长期有效留空',
    PRIMARY KEY (`id`),
    KEY `idx_personnel_id` (`personnel_id`),
    KEY `idx_name` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '人员证书';


