-- =====================================================================
-- 一次性升级脚本：t_tender_task 的幂等键从全局唯一改为按用户唯一
--
-- 背景：
--   幂等键由客户端生成，原先 uk_tender_task_idempotency (idempotency_key) 是全局唯一：
--   两个用户撞同一个 key（客户端复用同一模板键、随机数碰撞、恶意构造）时，
--   后到者会命中先到者的任务行，直接拿到对方 taskId 及其 input_json / result_json，
--   既是串号也是越权；requireSameType 只挡类型不同，挡不住这种串号。
--
--   改为 (user_id, idempotency_key) 后，幂等只在“同一个用户”内生效，
--   不同用户用同一个键各自建各自的任务。
--
-- 说明：
--   - 老索引在新的复合索引里已被覆盖（user_id 在前，仍可单独按 user 查），
--     因此顺带删除 uk_tender_task_idempotency，避免多维护一份全局唯一约束。
--   - idempotency_key 允许为 NULL，MySQL 唯一索引不约束多个 NULL，
--     所以“不带幂等键的请求每次都新建任务”这一行为不受影响。
--   - 存量数据不需要回填：user_id 列早已存在且有默认值 __anon__。
--
-- 仅对已经按旧版 DDL 建过库的环境执行；全新环境直接执行
-- src/main/db/tender_task.sql 即可，不需要本脚本。
--
-- 执行：mysql -u root -p tender_demo < src/main/db/upgrade/2026-11-12-idempotency-per-user.sql
-- =====================================================================

ALTER TABLE `t_tender_task`
    DROP INDEX `uk_tender_task_idempotency`,
    ADD UNIQUE KEY `uk_tender_task_user_idempotency` (`user_id`, `idempotency_key`);
