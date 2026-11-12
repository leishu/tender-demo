package org.example.service;

import io.agentscope.core.agent.RuntimeContext;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.state.mapper.AgentStateMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 运行时上下文构建器。
 *
 * <p>sessionId 由五段组成，使用 {@code -} 连接：年-月-日-时-当小时内的流水号，
 * 例如 {@code 2026-11-12-08-3} 表示 2026 年 11 月 12 日 08 点创建的第 3 个会话。
 * 流水号在每个自然小时切换时归零重新计数。</p>
 *
 * <p>流水号只靠进程内存计数，重启后会从 1 重新开始，于是同小时内可能与重启前的会话ID重复，
 * 进而串到上一个进程留下的 {@code agent_state} / {@code memory_messages}。
 * 因此启动时（{@link #initCounterFromDatabase()}）会从 {@code t_agent_state} 读出
 * 当小时已用过的最大流水号，把计数器初始到该值，保证重启后继续往后取号。</p>
 */
@Slf4j
@Component
public class RuntimeContextBuilder {

    /** sessionId 前四段的格式：年-月-日-时 */
    private static final DateTimeFormatter HOUR_PATTERN = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH");

    /** 统一时区，避免容器默认 UTC 导致小时字段与预期不符 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 可注入的时钟，便于测试跨小时逻辑；默认使用系统时钟 */
    private final Clock clock;

    /** 启动时用于回捞当小时已用流水号；为 {@code null} 时跳过（纯单测场景） */
    private final AgentStateMapper agentStateMapper;

    /** 当前小时标识 + 该小时内的计数器；整体替换保证跨小时归零 */
    private volatile HourCounter counter =
            new HourCounter("", new AtomicInteger(0));

    /**
     * 自动装配入口。
     *
     * <p>类里有多个构造函数（下面还有供测试注入时钟的），必须显式标注
     * {@code @Autowired}，否则 Spring 无法判断该用哪个，启动时会报
     * “类不包含用于自动装配的匹配构造函数”。</p>
     */
    @Autowired
    public RuntimeContextBuilder(AgentStateMapper agentStateMapper) {
        this(Clock.system(ZONE), agentStateMapper);
    }

    /** 供测试注入固定时钟与 mock mapper */
    RuntimeContextBuilder(Clock clock, AgentStateMapper agentStateMapper) {
        this.clock = clock;
        this.agentStateMapper = agentStateMapper;
    }

    /**
     * 启动时用数据库里“当小时已用过的最大流水号”初始化计数器。
     *
     * <p>只查当小时前缀的会话ID：跨小时重启本就应该从 1 重新计数，没必要把上一小时的
     * 流水号带过来。查询失败（表未建、库连不上）不阻断启动，降级为从 0 开始并告警。</p>
     */
    @PostConstruct
    public void initCounterFromDatabase() {
        if (agentStateMapper == null) {
            return;
        }
        String hourKey = LocalDateTime.now(clock).format(HOUR_PATTERN);
        try {
            List<String> sessionIds = agentStateMapper.findSessionIdsByPrefix(hourKey + "-");
            int maxSequence = 0;
            if (sessionIds != null) {
                for (String sessionId : sessionIds) {
                    maxSequence = Math.max(maxSequence, parseSequence(sessionId, hourKey));
                }
            }
            synchronized (this) {
                counter = new HourCounter(hourKey, new AtomicInteger(maxSequence));
            }
            log.info("sessionId 流水号已按库内已有会话初始化: hour={}, 已用最大流水号={}, 下一个流水号={}",
                    hourKey, maxSequence, maxSequence + 1);
        } catch (Exception e) {
            // 表没建、库暂时不可用都不该拦住启动，只是重启后本小时可能与旧会话ID重复
            log.warn("从数据库恢复 sessionId 流水号失败，本小时流水号将从 1 开始，"
                    + "可能与重启前的会话ID重复: hour={}", hourKey, e);
        }
    }

    public RuntimeContext build(String userId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(nextSessionId())
                .build();
    }

    /**
     * 生成 sessionId：年-月-日-时-小时流水号。
     *
     * <p>通过 {@code synchronized} 保证"读取/切换计数器"与"自增取号"是原子的，
     * 避免线程在小时切换的瞬间拿到旧小时计数器后仍生成旧小时前缀的 sessionId。
     * 取号是极短的操作，锁竞争可忽略。
     */
    private String nextSessionId() {
        String hourKey = LocalDateTime.now(clock).format(HOUR_PATTERN);
        long seq;
        synchronized (this) {
            if (!hourKey.equals(counter.hourKey())) {
                counter = new HourCounter(hourKey, new AtomicInteger(0));
            }
            seq = counter.sequence().incrementAndGet();
        }
        return hourKey + "-" + seq;
    }

    /**
     * 从 sessionId 中解析当小时流水号。
     *
     * <p>兼容子 Agent 的追加后缀：{@code 2026-11-12-09-7} 与
     * {@code 2026-11-12-09-7-personnel-matcher} 都解析为 7；
     * 前缀不符或流水号非数字（历史脏数据）时返回 0，不参与取最大值。</p>
     */
    static int parseSequence(String sessionId, String hourKey) {
        if (sessionId == null) {
            return 0;
        }
        String prefix = hourKey + "-";
        if (!sessionId.startsWith(prefix)) {
            return 0;
        }
        String rest = sessionId.substring(prefix.length());
        int separator = rest.indexOf('-');
        String number = separator < 0 ? rest : rest.substring(0, separator);
        try {
            return Math.max(Integer.parseInt(number.trim()), 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 某一小时内的流水号计数器 */
    private record HourCounter(String hourKey, AtomicInteger sequence) {
    }
}
