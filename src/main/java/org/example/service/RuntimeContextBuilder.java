package org.example.service;

import io.agentscope.core.agent.RuntimeContext;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.state.mapper.AgentStateMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

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
 *
 * <p><b>会话 ID 只由服务端签发</b>：客户端不自己造 ID，只有两种动作——</p>
 * <ul>
 *   <li>开始新会话：不传 {@code sessionId}，由本类生成；</li>
 *   <li>续写旧会话：把服务端上一次返回的 {@code sessionId} 原样回传。</li>
 * </ul>
 *
 * <p>因此校验只接受服务端生成的那种形态（{@code 年-月-日-时-流水号}）。这样客户端
 * 既不会编出与服务端序号撞车的 ID，也不会因为自造的命名规则变化而让历史会话失联。
 * 同一个 {@code (userId, sessionId)} 再次调用时，AgentScope 会从 {@code t_agent_state}
 * 读回上一次的 {@code agent_state}，把历史消息作为上下文继续推理。</p>
 */
@Slf4j
@Component
public class RuntimeContextBuilder {

    /** sessionId 前四段的格式：年-月-日-时 */
    private static final DateTimeFormatter HOUR_PATTERN = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH");

    /**
     * 会话 ID 的响应头名。
     *
     * <p>调用模型的接口（解析、匹配、目录、章节）一律在响应里回传本次会话：
     * 客户端要续写就把这个值原样放进下一次请求的 {@code sessionId} 字段。
     * 任务型接口同时也把它放在响应体里，便于日志与调试。</p>
     */
    public static final String SESSION_HEADER = "X-Session-Id";

    /**
     * 允许回传的 sessionId 形态：服务端生成的 {@code 年-月-日-时-流水号}。
     *
     * <p>客户端只能原样回传服务端签发过的 ID，不能自造。这样两件事同时成立：</p>
     * <ul>
     *   <li><b>不会撞号</b>：服务端的按小时计数器只在这个命名空间里取号，客户端
     *       造不出 {@code 2026-11-13-09-7} 之外的写法来抢占同一槽位；</li>
     *   <li><b>不会路径穿越</b>：sessionId 会直接作为 workspace 目录名与 transcript
     *       文件名（{@code <workspace>/<userId>/agents/<agentId>/sessions/<sessionId>.jsonl}），
     *       只放行数字与连字符，天然排除 {@code /}、{@code ..}、空白与控制字符。</li>
     * </ul>
     *
     * <p>与 {@link #HOUR_PATTERN} 的生成规则是一对：改了生成格式，这里必须同步改，
     * 否则历史会话会突然无法续写。</p>
     */
    private static final Pattern SERVER_SESSION_ID_PATTERN =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{1,10}");

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

    /** 使用服务端按小时生成的 sessionId 构建上下文，等价于 {@code build(userId, null)}。 */
    public RuntimeContext build(String userId) {
        return build(userId, null);
    }

    /**
     * 构建运行时上下文；{@code sessionId} 非空时复用客户端传入的稳定会话 ID。
     *
     * <p>复用后 AgentScope 会按 {@code (userId, sessionId)} 从 {@code t_agent_state}
     * 读回上一次的 {@code agent_state}，历史对话因此成为本轮推理的上下文；
     * 传空白则由服务端生成新 ID，不会串到任何历史会话。</p>
     *
     * <p>不做“会话必须属于该用户”的校验：状态表的槽位键本身包含 {@code userId}，
     * 不同用户即使传同一个 sessionId，读写的也是各自的行。</p>
     *
     * @param userId    调用者身份
     * @param sessionId 客户端传入的会话 ID，可为空
     * @throws IllegalArgumentException sessionId 格式非法（全局异常处理会映射成 400）
     */
    public RuntimeContext build(String userId, String sessionId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(resolveSessionId(sessionId))
                .build();
    }

    /**
     * 校验并归一化客户端回传的 sessionId，供接口层在创建任务前做同步校验。
     *
     * <p>返回 {@code null} 表示客户端没有指定会话（要开新会话），应由服务端生成。
     * 放在任务创建之前调用，非法 ID 会直接 400，而不是先落一条任务、再在后台以
     * error 事件失败。</p>
     *
     * @param sessionId 客户端原始值，可为空
     * @return 去掉首尾空白后的 sessionId；未指定时返回 {@code null}
     * @throws IllegalArgumentException 不是服务端签发的形态
     */
    public static String normalizeRequestedSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        String trimmed = sessionId.trim();
        if (!SERVER_SESSION_ID_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("sessionId 不合法：只能原样回传服务端返回的会话 ID"
                    + "（形如 2026-11-13-09-3）；要开始新会话请不要传该字段。收到：" + trimmed);
        }
        return trimmed;
    }

    /**
     * 决定本次调用使用哪个会话：有回传就复用，否则新发一个。
     *
     * <p>返回值一定是服务端签发形态的 ID，调用方可以安全地写进任务行并返回给客户端。</p>
     *
     * @param requestedSessionId 客户端回传的 sessionId，可为空
     * @throws IllegalArgumentException 回传值不是服务端签发的形态（全局异常处理会映射成 400）
     */
    public String resolveSessionId(String requestedSessionId) {
        String requested = normalizeRequestedSessionId(requestedSessionId);
        if (requested != null) {
            log.debug("复用客户端回传的会话: sessionId={}", requested);
            return requested;
        }
        String generated = nextSessionId();
        log.debug("新建会话: sessionId={}", generated);
        return generated;
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
