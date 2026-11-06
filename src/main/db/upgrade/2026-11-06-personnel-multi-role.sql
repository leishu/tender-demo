-- =====================================================================
-- 一次性升级脚本：人员岗位由单列改为独立表（一人多岗）
--
-- 原 t_personnel.role 一个 VARCHAR 只能记一个岗位，无法表达
-- 「既是项目经理、也能当技术负责人」。岗位拆到 t_personnel_role，
-- 一人多行，主表不再保留 role 列。
--
-- 顺序说明：本脚本会 DROP 掉 t_personnel.role，执行前请确认
-- src/main/db/upgrade/2026-11-06-split-personnel-certificate.sql
-- 已经执行过（它会读取 title 迁移证书，与 role 无关，可独立执行，
-- 但两个脚本都会 ALTER t_personnel，建议按日期顺序串行跑）。
--
-- 仅对已经按旧版 DDL 建过库的环境执行；全新环境直接执行
-- src/main/db/enterprise_data.sql 即可，不需要本脚本。
--
-- 执行：mysql -u root -p tender_demo < src/main/db/upgrade/2026-11-06-personnel-multi-role.sql
-- =====================================================================

CREATE TABLE IF NOT EXISTS `t_personnel_role` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `personnel_id` BIGINT      NOT NULL COMMENT '关联的 t_personnel 主键',
    `role`         VARCHAR(64) NOT NULL COMMENT '可担任的岗位，如 项目经理',
    PRIMARY KEY (`id`),
    KEY `idx_personnel_role` (`personnel_id`, `role`),
    KEY `idx_role` (`role`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '人员可担任岗位';


