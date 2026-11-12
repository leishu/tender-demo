-- =====================================================================
-- 招标任务与任务事件（断线续传 + 结果缓存）
--
--   t_tender_task       一行 = 一次任务（解析 / 目录生成 / 目录调整 / 章节撰写）
--   t_tender_task_event 一行 = 该任务产出的一个流式事件
--
-- 设计要点：
--   1. 任务与事件都落库，SSE 断线、网关超时、服务重启后仍可按 seq 续看进度，
--      不必重新调用模型；前端重连带 Last-Event-ID 即可。
--   2. 事件的权威来源是数据库：订阅时先按 seq > lastEventId 回放，再接实时事件；
--      进程内只保留“正在运行的任务 + 活跃订阅者”，任务结束即释放。
--   3. (task_id, seq) 唯一：seq 在任务内单调递增，既做 SSE 的 id 字段，也做续传游标；
--      重复写入会被数据库挡掉，避免回放时出现重复事件。
--   4. idempotency_key 唯一（可为 NULL，MySQL 唯一索引不约束多个 NULL）：
--      同一幂等键重复提交返回同一任务；对目录/章节这类结果可缓存的任务，
--      命中已完成的任务直接返回 result_json，不再调用模型。
--   5. result_json 只保存“可缓存的最终结果”（目录 JSON、章节正文），
--      解析任务的结果由事件本身承载，不回写这一列。
--
-- 执行：mysql -u root -p tender_demo < src/main/db/tender_task.sql
-- =====================================================================

CREATE TABLE IF NOT EXISTS `t_tender_task` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID，同时作为对外 taskId',
    `task_type`       VARCHAR(32)   NOT NULL                COMMENT '任务类型：parse/match/catalog/catalog_adjust/chapter',
    `status`          VARCHAR(16)   NOT NULL                COMMENT '任务状态：pending/running/succeeded/failed/cancelled',
    `user_id`         VARCHAR(128)  NOT NULL DEFAULT '__anon__' COMMENT '用户ID，匿名固定为 __anon__',
    `idempotency_key` VARCHAR(191)  NULL                    COMMENT '幂等键；同一键重复提交返回同一任务',
    `input_json`      LONGTEXT      NOT NULL                COMMENT '任务输入快照（JSON）',
    `result_json`     LONGTEXT      NULL                    COMMENT '可缓存的最终结果（目录/章节），解析任务为空',
    `error_message`   VARCHAR(1024) NULL                    COMMENT '失败或取消原因',
    `last_event_seq`  INT           NOT NULL DEFAULT 0      COMMENT '已落库的最大事件序号，0 表示尚无事件',
    `created_at`      BIGINT        NOT NULL                COMMENT '创建时间(epoch ms)',
    `updated_at`      BIGINT        NOT NULL                COMMENT '更新时间(epoch ms)',
    `finished_at`     BIGINT        NULL                    COMMENT '进入终态的时间(epoch ms)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tender_task_idempotency` (`idempotency_key`),
    KEY `idx_tender_task_user` (`user_id`, `created_at`),
    KEY `idx_tender_task_status` (`status`, `updated_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '招标任务（解析/目录/章节）';


CREATE TABLE IF NOT EXISTS `t_tender_task_event` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `task_id`    BIGINT      NOT NULL                COMMENT '所属任务ID',
    `seq`        INT         NOT NULL                COMMENT '任务内事件序号，从 1 单调递增，作为 SSE 的 id',
    `event_type` VARCHAR(32) NOT NULL                COMMENT '事件类型：project/qualification/performance/personnel/scoringItem/error/completed',
    `payload`    LONGTEXT    NOT NULL                COMMENT '事件载荷（JSON），SSE 的 data 行原样发送',
    `created_at` BIGINT      NOT NULL                COMMENT '创建时间(epoch ms)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tender_task_event_seq` (`task_id`, `seq`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '招标任务事件（SSE 回放与续传）';
