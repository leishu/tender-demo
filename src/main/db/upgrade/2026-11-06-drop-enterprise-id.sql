-- =====================================================================
-- 一次性升级脚本：移除企业维度（enterprise_id）
--
-- 系统不再支持多企业隔离，各表的企业字段与索引一并删除。
-- 仅对已经按旧版 DDL 建过库的环境执行；全新环境直接执行
-- src/main/db 下的建表脚本即可，不需要本脚本。
--
-- 执行：mysql -u root -p tender_demo < src/main/db/upgrade/2026-11-06-drop-enterprise-id.sql
-- =====================================================================

ALTER TABLE `t_tender_requirements`
    DROP INDEX `idx_enterprise_id`,
    DROP COLUMN `enterprise_id`;

ALTER TABLE `t_qualification_requirement` DROP COLUMN `enterprise_id`;
ALTER TABLE `t_performance_requirement`  DROP COLUMN `enterprise_id`;
ALTER TABLE `t_personnel_requirement`    DROP COLUMN `enterprise_id`;
ALTER TABLE `t_scoring_item`             DROP COLUMN `enterprise_id`;

ALTER TABLE `t_bid_catalog_run`
    DROP INDEX `idx_enterprise_id`,
    DROP COLUMN `enterprise_id`;
