package org.example.state.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 智能体状态快照（对应表 t_agent_state）。
 *
 * <p>一个 {@code (userId, sessionId, key)} 三元组对应一行；{@code stateJson} 保存
 * {@link io.agentscope.core.state.State} 的 JSON 序列化结果：单值状态为 JSON 对象，
 * 多值状态（如会话消息）为 JSONL（一行一条），与 {@code JsonFileAgentStateStore}
 * 的落盘格式一致。</p>
 *
 * <p>{@code version} 为乐观锁版本号：框架通过 {@code getVersioned} 读出、写入时以
 * {@code WHERE version = ?} 做 CAS，冲突即放弃本次写入，避免多实例并发覆盖上下文。</p>
 */
@Data
@TableName("t_agent_state")
public class AgentStateRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID；匿名会话固定为 {@code __anon__}，与内存实现保持同一套命名空间 */
    private String userId;

    /** 会话ID */
    private String sessionId;

    /** 状态键，如 agent_state / memory_messages / toolkit_activeGroups */
    private String stateKey;

    /** 状态快照：单值 JSON 对象，或多值 JSONL */
    private String stateJson;

    /** 乐观锁版本号，新建为 1，每次写入 +1 */
    private Long version;

    /** 创建时间（epoch 毫秒） */
    private Long createdAt;

    /** 更新时间（epoch 毫秒） */
    private Long updatedAt;
}
