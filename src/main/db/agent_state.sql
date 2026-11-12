-- =====================================================================
-- AgentScope 状态持久化表（对应 io.agentscope.core.state.AgentStateStore）
--
--   表中一行 = 一个 (user_id, session_id, state_key) 槽位的一份状态快照：
--     state_key = 'agent_state'          -> 智能体上下文（单值，version 参与乐观锁）
--     state_key = 'memory_messages'      -> 会话历史消息（多值，JSONL 形式：一行一条消息）
--     state_key = 'toolkit_activeGroups' -> 工具分组激活状态（单值）
--
-- 说明：
--   1. user_id 对匿名会话固定存 '__anon__'，避免 NULL 破坏唯一键（MySQL 唯一索引不约束 NULL）；
--   2. version 为乐观锁版本号：新建为 1，每次写入 +1。CAS 写入使用
--      UPDATE ... WHERE version = ? 命中行数判断是否冲突，未命中且期望值为 0 时退化为插入；
--   3. state_json 单值状态存单个 JSON 对象，多值状态存 JSONL（每条消息一行），
--      与 JsonFileAgentStateStore 的落盘格式保持一致，便于互相迁移；
--   4. 会话级读取（exists / delete / listSessionIds）走 uk_agent_state_slot
--      与 idx_agent_state_user 两个索引，无需全表扫描；
--   5. 服务启动时会按 session_id 前缀（yyyy-MM-dd-HH-）回捞当小时已用流水号，
--      该查询走 idx_agent_state_session；
--   6. 每天 04:00 由 MysqlAgentStateStore 定时清理过期会话（默认保留 90 天，
--      按 updated_at 判断，整段会话都过期才删），配置项
--      agentscope.state-store.cleanup.{enabled,retain-days,batch-size,cron}。
--
-- 执行：mysql -u root -p tender_demo < src/main/db/agent_state.sql
-- 已建过表的环境只需补索引：
--   ALTER TABLE t_agent_state ADD KEY idx_agent_state_session (session_id);
-- =====================================================================

CREATE TABLE t_agent_state (
    id          BIGINT        NOT NULL AUTO_INCREMENT           COMMENT '主键自增ID',
    user_id     VARCHAR(128)  NOT NULL DEFAULT '__anon__'        COMMENT '用户ID，匿名会话固定为 __anon__',
    session_id  VARCHAR(191)  NOT NULL                          COMMENT '会话ID',
    state_key   VARCHAR(128)  NOT NULL                          COMMENT '状态键：agent_state / memory_messages / toolkit_activeGroups',
    state_json  LONGTEXT      NOT NULL                          COMMENT '状态快照：单值JSON对象或多值JSONL',
    version     BIGINT        NOT NULL DEFAULT 1                COMMENT '乐观锁版本号，新建为1，每次写入+1',
    created_at  BIGINT        NOT NULL                          COMMENT '创建时间(epoch ms)',
    updated_at  BIGINT        NOT NULL                          COMMENT '更新时间(epoch ms)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_state_slot (user_id, session_id, state_key),
    KEY idx_agent_state_user (user_id, updated_at),
    KEY idx_agent_state_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AgentScope 智能体状态持久化';

