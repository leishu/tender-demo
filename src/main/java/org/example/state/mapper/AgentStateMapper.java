package org.example.state.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.state.model.AgentStateRecord;

import java.util.List;

/**
 * t_agent_state 访问层。
 *
 * <p>常规读写走 MyBatis-Plus 的 {@link BaseMapper}，另加三条手写 SQL 承载特定语义：</p>
 * <ul>
 *   <li>{@link #upsert}：无条件的“有则更新、无则插入”，版本号自增（用于 {@code save}）；</li>
 *   <li>{@link #casUpdate}：带版本号条件的更新，命中行数为 0 即代表并发冲突（用于 {@code saveIfVersion}）；</li>
 *   <li>{@link #deleteSessionsUpdatedBefore}：分批删除整段过期会话（用于定时清理）。</li>
 * </ul>
 */
@Mapper
public interface AgentStateMapper extends BaseMapper<AgentStateRecord> {

    /**
     * 写入状态快照：槽位不存在时插入（版本 1），已存在时覆盖内容并让版本号 +1。
     *
     * <p>依赖唯一键 {@code uk_agent_state_slot(user_id, session_id, state_key)}；
     * 单条 SQL 完成，避免“先查后写”的竞态。</p>
     */
    @Insert("""
            INSERT INTO t_agent_state (user_id, session_id, state_key, state_json, version, created_at, updated_at)
            VALUES (#{userId}, #{sessionId}, #{stateKey}, #{stateJson}, 1, #{now}, #{now})
            ON DUPLICATE KEY UPDATE
                state_json = #{stateJson},
                version    = version + 1,
                updated_at = #{now}
            """)
    int upsert(@Param("userId") String userId,
               @Param("sessionId") String sessionId,
               @Param("stateKey") String stateKey,
               @Param("stateJson") String stateJson,
               @Param("now") long now);

    /**
     * 版本号匹配才更新（乐观锁）。
     *
     * @return 命中行数：1 表示写入成功，0 表示版本已过期或槽位不存在
     */
    @Update("""
            UPDATE t_agent_state
               SET state_json = #{stateJson},
                   version    = version + 1,
                   updated_at = #{now}
             WHERE user_id   = #{userId}
               AND session_id = #{sessionId}
               AND state_key  = #{stateKey}
               AND version    = #{expectedVersion}
            """)
    int casUpdate(@Param("userId") String userId,
                  @Param("sessionId") String sessionId,
                  @Param("stateKey") String stateKey,
                  @Param("stateJson") String stateJson,
                  @Param("expectedVersion") long expectedVersion,
                  @Param("now") long now);

    /**
     * 按 sessionId 前缀查询已存在的会话（去重）。
     *
     * <p>供服务启动时恢复“当小时流水号”使用：传入 {@code 2026-11-12-09-}
     * 即取出该小时已经用过的全部会话ID，其中包含子 Agent 的
     * {@code 2026-11-12-09-3-<moduleCode>} 形态。</p>
     */
    @Select("SELECT DISTINCT session_id FROM t_agent_state WHERE session_id LIKE CONCAT(#{prefix}, '%')")
    List<String> findSessionIdsByPrefix(@Param("prefix") String prefix);

    /**
     * 分批删除“整段过期会话”：一次最多清理 {@code batchSize} 个会话（不是行）。
     *
     * <p>按会话而不是按行删除，避免出现 {@code agent_state} 已删、
     * {@code memory_messages} 还留着的半截会话。会话的“最后活跃时间”取该会话所有行的
     * {@code MAX(updated_at)}，只有整段会话都超过 {@code cutoff} 才删。</p>
     *
     * <p>MySQL 不允许在 DELETE 的子查询里直接引用被删的表（错误 1093），
     * 这里再套一层派生表 {@code expired} 绕开限制。</p>
     *
     * @param cutoff    过期时间线（epoch ms），{@code updated_at < cutoff} 视为过期
     * @param batchSize 单批最多删除的会话数
     * @return 实际删除的行数（一个会话通常对应 2~3 行）
     */
    @Delete("""
            DELETE FROM t_agent_state
             WHERE (user_id, session_id) IN (
                    SELECT user_id, session_id
                      FROM (
                            SELECT user_id, session_id
                              FROM t_agent_state
                             GROUP BY user_id, session_id
                            HAVING MAX(updated_at) < #{cutoff}
                             LIMIT #{batchSize}
                           ) expired
                   )
            """)
    int deleteSessionsUpdatedBefore(@Param("cutoff") long cutoff,
                                    @Param("batchSize") int batchSize);
}
