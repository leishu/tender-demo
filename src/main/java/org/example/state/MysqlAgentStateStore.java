package org.example.state;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.example.state.mapper.AgentStateMapper;
import org.example.state.model.AgentStateRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 基于 MySQL 的 {@link AgentStateStore} 实现，进程重启、多实例部署下会话状态都不丢。
 *
 * <p>存储模型：{@code t_agent_state} 一行对应一个 {@code (userId, sessionId, key)} 槽位。</p>
 *
 * <p>序列化：复用 AgentScope 全局 {@link JsonCodec}，与 {@code JsonFileAgentStateStore}
 * 完全一致——单值状态存 JSON 对象，多值状态（{@code memory_messages}）存 JSONL，一行一条。
 * 之所以不用 JSON 数组，一是可以逐条 {@code fromJson(line, itemType)} 反序列化（避免泛型擦除后
 * 拿不到元素类型），二是与文件版存储的格式可直接互迁。</p>
 *
 * <p>并发：启用乐观锁（{@link #supportsVersioning()} 返回 {@code true}）。
 * 框架读 {@code agent_state} 时带上版本号，写回时以 {@code WHERE version = ?} 做 CAS，
 * 命中 0 行即视为并发冲突并按 {@code ConflictPolicy} 处理；无条件写入则让版本号自增，
 * 并在同一事务内回读本次写入的版本号，保证返回给框架的版本号不会串到其他写入者。</p>
 *
 * <p>过期清理：每天定时按 {@code updated_at} 删除“整段会话都失去活跃”的历史数据，
 * 默认保留 90 天（约三个月），可通过 {@code agentscope.state-store.cleanup.*} 配置关闭、
 * 调整保留天数、批大小与 cron。删除按批次推进，避免单条大事务长时间持锁。</p>
 */
@Slf4j
public class MysqlAgentStateStore implements AgentStateStore {

    /** 匿名会话命名空间，与 InMemoryAgentStateStore / JsonFileAgentStateStore 保持一致 */
    private static final String ANON_USER = "__anon__";

    /** 多值状态的行分隔符：JSONL 一行一条 */
    private static final String LINE_SEPARATOR = "\n";

    /** 默认保留天数：三个月按 90 天计 */
    public static final int DEFAULT_RETAIN_DAYS = 90;

    /** 默认单批删除的会话数 */
    private static final int DEFAULT_BATCH_SIZE = 1000;

    /** 单次清理最多推进的批次数，防止一次清理跑太久影响线上读写 */
    private static final int MAX_BATCHES_PER_RUN = 100;

    /** 批次之间的间隔，给主从复制和其他写入留出喘息时间 */
    private static final long BATCH_INTERVAL_MILLIS = 100L;

    private static final DateTimeFormatter CUTOFF_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AgentStateMapper mapper;

    /** 是否启用定时清理 */
    private final boolean cleanupEnabled;

    /** 保留天数，{@code <= 0} 表示不按时间清理 */
    private final int cleanupRetainDays;

    /** 单批删除的会话数 */
    private final int cleanupBatchSize;

    /** 默认配置：启用清理、保留 90 天、每批 1000 个会话 */
    public MysqlAgentStateStore(AgentStateMapper mapper) {
        this(mapper, true, DEFAULT_RETAIN_DAYS, DEFAULT_BATCH_SIZE);
    }

    public MysqlAgentStateStore(AgentStateMapper mapper, boolean cleanupEnabled,
                                int cleanupRetainDays, int cleanupBatchSize) {
        this.mapper = mapper;
        this.cleanupEnabled = cleanupEnabled;
        this.cleanupRetainDays = cleanupRetainDays;
        this.cleanupBatchSize = cleanupBatchSize > 0 ? cleanupBatchSize : DEFAULT_BATCH_SIZE;
    }

    // ------------------------------------------------------------------ 写入

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        String json = codec().toJson(value);
        mapper.upsert(normalizeUser(userId), requireSessionId(sessionId), requireKey(key), json, now());
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        StringBuilder json = new StringBuilder();
        for (State value : values) {
            if (json.length() > 0) {
                json.append(LINE_SEPARATOR);
            }
            json.append(codec().toJson(value));
        }
        mapper.upsert(normalizeUser(userId), requireSessionId(sessionId), requireKey(key),
                json.toString(), now());
    }

    @Override
    public boolean supportsVersioning() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>加事务是为了让“无条件写入 + 回读版本号”落在同一条连接上：{@code LAST_INSERT_ID}
     * 之类会话级状态不可用，但同一事务内的两次语句一定看到本次写入的结果，
     * 因此框架拿到的版本号是本方法自己写入的版本，而不是并发写入者的版本。</p>
     */
    @Override
    @Transactional
    public long saveIfVersion(String userId, String sessionId, String key, State value,
                              long expectedVersion) {
        String uid = normalizeUser(userId);
        String sid = requireSessionId(sessionId);
        String stateKey = requireKey(key);
        String json = codec().toJson(value);
        long now = now();

        // expectedVersion == UNVERSIONED：无条件覆盖，语义等同 save()，但要回传新版本号
        if (expectedVersion == UNVERSIONED) {
            mapper.upsert(uid, sid, stateKey, json, now);
            Long written = selectVersion(uid, sid, stateKey);
            return written == null ? UNVERSIONED : written;
        }

        // 常规 CAS：版本号匹配才写，version = version + 1
        if (mapper.casUpdate(uid, sid, stateKey, json, expectedVersion, now) > 0) {
            return expectedVersion + 1L;
        }

        // 槽位不存在且期望版本为 0：插入即成功；并发抢插时由唯一键兜底，转为冲突
        if (expectedVersion == 0L) {
            AgentStateRecord record = new AgentStateRecord();
            record.setUserId(uid);
            record.setSessionId(sid);
            record.setStateKey(stateKey);
            record.setStateJson(json);
            record.setVersion(1L);
            record.setCreatedAt(now);
            record.setUpdatedAt(now);
            try {
                mapper.insert(record);
                return 1L;
            } catch (DuplicateKeyException e) {
                log.debug("状态槽位已被并发创建，本次写入视为冲突: userId={}, sessionId={}, key={}",
                        uid, sid, stateKey);
            }
        }
        return UNVERSIONED;
    }

    // ------------------------------------------------------------------ 读取

    @Override
    public <T extends State> VersionedState<T> getVersioned(String userId, String sessionId,
                                                            String key, Class<T> type) {
        AgentStateRecord record = findOne(normalizeUser(userId), requireSessionId(sessionId), key);
        if (record == null || !StringUtils.hasLength(record.getStateJson())) {
            return new VersionedState<>(null, 0L);
        }
        T value = codec().fromJson(record.getStateJson(), type);
        return new VersionedState<>(value, record.getVersion() == null ? 0L : record.getVersion());
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key,
                                             Class<T> type) {
        return Optional.ofNullable(getVersioned(userId, sessionId, key, type).value());
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key,
                                             Class<T> itemType) {
        AgentStateRecord record = findOne(normalizeUser(userId), requireSessionId(sessionId), key);
        if (record == null || !StringUtils.hasLength(record.getStateJson())) {
            return List.of();
        }
        List<T> items = new ArrayList<>();
        for (String line : record.getStateJson().split(LINE_SEPARATOR)) {
            if (!line.isBlank()) {
                items.add(codec().fromJson(line, itemType));
            }
        }
        return items;
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<AgentStateRecord>()
                .eq(AgentStateRecord::getUserId, normalizeUser(userId))
                .eq(AgentStateRecord::getSessionId, requireSessionId(sessionId)));
        return count != null && count > 0;
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        List<AgentStateRecord> rows = mapper.selectList(new LambdaQueryWrapper<AgentStateRecord>()
                .select(AgentStateRecord::getSessionId)
                .eq(AgentStateRecord::getUserId, normalizeUser(userId)));
        Set<String> sessionIds = new HashSet<>();
        for (AgentStateRecord row : rows) {
            if (row.getSessionId() != null) {
                sessionIds.add(row.getSessionId());
            }
        }
        return sessionIds;
    }

    // ------------------------------------------------------------------ 删除

    @Override
    public void delete(String userId, String sessionId) {
        mapper.delete(new LambdaQueryWrapper<AgentStateRecord>()
                .eq(AgentStateRecord::getUserId, normalizeUser(userId))
                .eq(AgentStateRecord::getSessionId, requireSessionId(sessionId)));
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        mapper.delete(new LambdaQueryWrapper<AgentStateRecord>()
                .eq(AgentStateRecord::getUserId, normalizeUser(userId))
                .eq(AgentStateRecord::getSessionId, requireSessionId(sessionId))
                .eq(AgentStateRecord::getStateKey, requireKey(key)));
    }

    // -------------------------------------------------------------- 过期清理

    /**
     * 每天定时清理过期会话状态；cron 可通过
     * {@code agentscope.state-store.cleanup.cron} 覆盖（默认每天 04:00）。
     */
    @Scheduled(cron = "${agentscope.state-store.cleanup.cron:0 0 4 * * *}")
    public void scheduledCleanup() {
        runCleanup();
    }

    /**
     * 执行一次过期清理，供定时任务与手工触发（如运维接口、测试）共用。
     *
     * <p>判断依据是 {@code updated_at}（最后写入时间）而不是 {@code created_at}：
     * 只要会话最近还在被访问，就不该被删掉。过期判定按“整段会话”处理，
     * 一次最多删 {@link #MAX_BATCHES_PER_RUN} 批，剩下的留到下次执行。</p>
     *
     * <p>任何异常都只记日志不抛出，避免定时任务被一次性失败终止。</p>
     *
     * @return 本次清理的统计结果
     */
    public CleanupResult runCleanup() {
        if (!cleanupEnabled) {
            log.debug("[AgentStateCleanup] 已关闭，跳过");
            return CleanupResult.EMPTY;
        }
        if (cleanupRetainDays <= 0) {
            log.debug("[AgentStateCleanup] 保留天数 <= 0，不按时间清理");
            return CleanupResult.EMPTY;
        }

        long cutoff = now() - Duration.ofDays(cleanupRetainDays).toMillis();
        int deletedRows = 0;
        int batches = 0;
        try {
            while (batches < MAX_BATCHES_PER_RUN) {
                int affected = mapper.deleteSessionsUpdatedBefore(cutoff, cleanupBatchSize);
                if (affected <= 0) {
                    break;
                }
                deletedRows += affected;
                batches++;
                if (!pauseBetweenBatches()) {
                    log.warn("[AgentStateCleanup] 清理被中断，已删除 {} 行，剩余数据留待下次执行", deletedRows);
                    return new CleanupResult(cutoff, deletedRows, batches, true);
                }
            }
            if (batches >= MAX_BATCHES_PER_RUN) {
                log.info("[AgentStateCleanup] 已达单次批次上限 {}，仍有过期数据，留待下次执行", MAX_BATCHES_PER_RUN);
            }
        } catch (Exception e) {
            log.error("[AgentStateCleanup] 清理失败，已删除 {} 行，下次执行时继续", deletedRows, e);
            return new CleanupResult(cutoff, deletedRows, batches, true);
        }

        CleanupResult result = new CleanupResult(cutoff, deletedRows, batches, false);
        if (deletedRows > 0) {
            log.info("[AgentStateCleanup] 清理完成: 保留天数={}, 截止时间={}, 删除行数={}, 批次数={}",
                    cleanupRetainDays, formatCutoff(cutoff), deletedRows, batches);
        } else {
            log.debug("[AgentStateCleanup] 无需清理: 截止时间={}", formatCutoff(cutoff));
        }
        return result;
    }

    /** @return false 表示线程被中断，调用方应尽快退出 */
    private static boolean pauseBetweenBatches() {
        try {
            Thread.sleep(BATCH_INTERVAL_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String formatCutoff(long cutoff) {
        return Instant.ofEpochMilli(cutoff).atZone(ZoneId.systemDefault()).format(CUTOFF_FORMATTER);
    }

    // ------------------------------------------------------------------ 内部

    /** 读取一行槽位（只取写入/读取需要的列，避免把大字段带进无关查询） */
    private AgentStateRecord findOne(String userId, String sessionId, String key) {
        return mapper.selectOne(new LambdaQueryWrapper<AgentStateRecord>()
                .eq(AgentStateRecord::getUserId, userId)
                .eq(AgentStateRecord::getSessionId, sessionId)
                .eq(AgentStateRecord::getStateKey, requireKey(key)));
    }

    private Long selectVersion(String userId, String sessionId, String key) {
        AgentStateRecord record = mapper.selectOne(new LambdaQueryWrapper<AgentStateRecord>()
                .select(AgentStateRecord::getVersion)
                .eq(AgentStateRecord::getUserId, userId)
                .eq(AgentStateRecord::getSessionId, sessionId)
                .eq(AgentStateRecord::getStateKey, key));
        return record == null ? null : record.getVersion();
    }

    /** 每次都取全局编解码器：{@code JsonUtils.setJsonCodec} 可在运行期替换实现 */
    private static JsonCodec codec() {
        return JsonUtils.getJsonCodec();
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    private static String requireSessionId(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return sessionId;
    }

    private static String requireKey(String key) {
        Objects.requireNonNull(key, "state key must not be null");
        if (key.isBlank()) {
            throw new IllegalArgumentException("state key must not be blank");
        }
        return key;
    }

    /**
     * 一次清理的统计结果。
     *
     * @param cutoff     本次的过期时间线（epoch ms），早于它的会话会被删
     * @param deletedRows 实际删除的行数
     * @param batches    推进的批次数
     * @param interrupted 是否因异常或线程中断提前结束
     */
    public record CleanupResult(long cutoff, int deletedRows, int batches, boolean interrupted) {

        static final CleanupResult EMPTY = new CleanupResult(0L, 0, 0, false);
    }
}
