-- =====================================================================
-- 一次性升级脚本：t_tender_requirements 增加录入时间
--
-- 列表页原先以自增主键倒序近似“最近录入在前”，无法展示录入时间，
-- 也无法在数据迁移/批量导入后保持正确的展示顺序。
-- 这里新增 record_time（epoch 毫秒），列表改为按 record_time 倒序、
-- id 倒序兜底。
--
-- 存量数据回填：统一回填为脚本执行时刻（同一毫秒），排序时由 id 倒序
-- 兜底，与升级前的展示顺序完全一致。
--
-- 仅对已经按旧版 DDL 建过库的环境执行；全新环境直接执行
-- src/main/db/tender_requirements.sql 即可，不需要本脚本。
--
-- 执行：mysql -u root -p tender_demo < src/main/db/upgrade/2026-11-06-add-record-time.sql
-- =====================================================================

ALTER TABLE `t_tender_requirements`
    ADD COLUMN `record_time` BIGINT NULL COMMENT '录入时间(epoch ms)' AFTER `budget`;

UPDATE `t_tender_requirements`
SET `record_time` = CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED)
WHERE `record_time` IS NULL;

ALTER TABLE `t_tender_requirements`
    MODIFY COLUMN `record_time` BIGINT NOT NULL COMMENT '录入时间(epoch ms)，列表按此字段倒序',
    ADD KEY `idx_record_time` (`record_time`);
