package org.example.task.service;

import lombok.extern.slf4j.Slf4j;
import org.example.task.mapper.TenderTaskEventMapper;
import org.example.task.mapper.TenderTaskMapper;
import org.example.task.model.TenderTaskEventRecord;
import org.example.task.model.dto.TaskEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务事件的落库与分发：把“一次性 SSE 流”变成“可回放、可续传的事件流”。
 *
 * <p>核心是回放与实时的无缝衔接。订阅方可能在任何时刻接入：</p>
 * <ul>
 *   <li>任务还没开始：先拿到空回放，再等实时事件；</li>
 *   <li>任务跑到一半：先回放已落库的事件，再接着收后续事件，不重不漏；</li>
 *   <li>任务已结束：回放全部事件（含 completed/error）后直接关闭连接。</li>
 * </ul>
 *
 * <p>之所以不会漏事件，是因为“读回放 + 注册监听”与“写库 + 通知监听”用的是同一把
 * 任务级锁：写入方在锁内先落库再通知，订阅方在锁内先读库再注册，两条路径互斥，
 * 不存在“刚读完库、还没注册，事件已经发出去”的空窗。</p>
 *
 * <p>内存里只保留正在运行的任务；任务结束时 {@link #close(long)} 释放，之后来的订阅
 * 一律走数据库回放（任务已终态，回放完即关闭），因此事件数量再多也不会常驻内存。</p>
 *
 * <p><b>单实例语义</b>：实时分发依赖进程内监听者，多实例部署时，订阅到“非本实例执行”的
 * 任务只能看到已落库的事件（连接会在回放结束时关闭），需要再配合 Redis 广播或
 * 粘性路由。当前部署形态为单实例，故未引入额外中间件。</p>
 */
@Slf4j
@Component
public class TenderTaskEventHub {

    private final TenderTaskEventMapper eventMapper;
    private final TenderTaskMapper taskMapper;
    private final JsonMapper jsonMapper;

    /** 正在运行的任务；任务结束即移除，避免长期占用内存 */
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();

    public TenderTaskEventHub(TenderTaskEventMapper eventMapper,
                              TenderTaskMapper taskMapper,
                              JsonMapper jsonMapper) {
        this.eventMapper = eventMapper;
        this.taskMapper = taskMapper;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 为即将执行的任务登记会话。
     *
     * @param lastEventSeq 数据库中已有的最大事件序号（重跑或恢复时可能非 0）
     */
    public void open(long taskId, int lastEventSeq) {
        sessions.computeIfAbsent(taskId, id -> new Session(id, lastEventSeq));
    }

    /**
     * 以事件表里的真实最大序号打开会话。
     *
     * <p>用于取消、重启恢复等“任务不在本实例内存、但还要补一条终止事件”的场景。
     * 不要用 {@code t_tender_task.last_event_seq}：它只是缓存列，崩溃时可能滞后，
     * 按它分配 seq 会撞唯一键。</p>
     */
    public void openFromDatabase(long taskId) {
        int maxSeq = eventMapper.maxSeq(taskId);
        sessions.computeIfAbsent(taskId, id -> new Session(id, maxSeq));
    }

    /**
     * 追加一条事件：先落库，再通知当前订阅者。
     *
     * <p>锁内写库有两个作用：一是保证 seq 分配与落库顺序一致；二是让订阅方的
     * “读回放 + 注册监听”不会插到这次写入的中间。单条事件只有几十字节到几 KB，
     * 锁竞争只发生在同一任务内，代价可接受。</p>
     *
     * <p>任务不在内存（已结束或本实例没有执行它）时直接丢弃：事件已经不会再有，
     * 也没人在等。</p>
     */
    public void append(long taskId, String eventType, Object payload) {
        Session session = sessions.get(taskId);
        if (session == null) {
            log.debug("任务 {} 不在本实例运行，丢弃事件 {}", taskId, eventType);
            return;
        }
        String json;
        try {
            json = jsonMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("任务 {} 的事件 {} 序列化失败，已跳过", taskId, eventType, e);
            return;
        }

        synchronized (session.lock) {
            if (session.terminal) {
                return;
            }
            int seq = session.lastSeq + 1;
            TenderTaskEventRecord record = newRecord(taskId, seq, eventType, json);
            try {
                eventMapper.insert(record);
            } catch (DuplicateKeyException e) {
                // 上次 insert 可能已提交但连接中断，或另一实例抢先用了同一个 seq。
                // 以事件表的最大序号为准重新取号，保证终止事件不会因为一次碰撞而丢掉。
                int next = Math.max(seq + 1, eventMapper.maxSeq(taskId) + 1);
                record = newRecord(taskId, next, eventType, json);
                eventMapper.insert(record);
                seq = next;
            }
            // 只有落库成功才推进内存游标，避免失败时白白烧掉一个序号
            session.lastSeq = seq;

            try {
                taskMapper.advanceEventSeq(taskId, seq, record.getCreatedAt());
            } catch (Exception e) {
                // 该列只是进度缓存；事件已经落库，回放以事件表为准，更新失败不影响正确性
                log.warn("任务 {} 的事件序号缓存更新失败: seq={}", taskId, seq, e);
            }

            TaskEvent event = new TaskEvent(seq, eventType, json);
            for (Listener listener : session.listeners) {
                try {
                    listener.next(event);
                } catch (Exception e) {
                    // 单个订阅者（通常是已断开的浏览器连接）失败不能影响其它订阅者
                    log.debug("任务 {} 的事件 {} 推送失败，跳过该订阅者", taskId, seq, e);
                }
            }
        }
    }

    private static TenderTaskEventRecord newRecord(long taskId, int seq,
                                                   String eventType, String payload) {
        TenderTaskEventRecord record = new TenderTaskEventRecord();
        record.setTaskId(taskId);
        record.setSeq(seq);
        record.setEventType(eventType);
        record.setPayload(payload);
        record.setCreatedAt(System.currentTimeMillis());
        return record;
    }

    /**
     * 订阅任务事件：先回放 {@code seq > lastEventId} 的历史事件，再接实时事件。
     *
     * <p>返回的流在下列情况结束：任务已进入终态（回放完即关闭），或客户端主动断开。</p>
     *
     * @param lastEventId 客户端已收到的最大序号，取自 SSE 的 {@code Last-Event-ID}
     */
    public Flux<TaskEvent> stream(long taskId, int lastEventId) {
        int cursor = Math.max(lastEventId, 0);
        return Flux.create(emitter -> {
            Session session = sessions.get(taskId);
            if (session == null) {
                // 任务不在本实例运行：只能依赖数据库。终态任务回放完即结束；
                // 若仍在运行（例如刚创建的瞬间），先回放已有事件，客户端会按需重连。
                List<TaskEvent> replay = load(taskId, cursor);
                replay.forEach(emitter::next);
                emitter.complete();
                return;
            }

            synchronized (session.lock) {
                List<TaskEvent> replay = load(taskId, cursor);
                replay.forEach(emitter::next);

                if (session.terminal) {
                    emitter.complete();
                    return;
                }

                Listener listener = new Listener() {
                    @Override
                    public void next(TaskEvent event) {
                        emitter.next(event);
                    }

                    @Override
                    public void complete() {
                        emitter.complete();
                    }
                };
                session.listeners.add(listener);
                emitter.onDispose(() -> {
                    synchronized (session.lock) {
                        session.listeners.remove(listener);
                    }
                });
            }
        });
    }

    /**
     * 终止任务的所有活跃订阅（任务已产出 completed/error 事件之后调用）。
     *
     * <p>只标记终态并通知订阅者，不立即移除会话：紧接着到达的订阅仍能从内存里
     * 看到 {@code terminal=true}，避免与数据库回放产生时序歧义。</p>
     */
    public void complete(long taskId) {
        Session session = sessions.get(taskId);
        if (session == null) {
            return;
        }
        List<Listener> listeners;
        synchronized (session.lock) {
            session.terminal = true;
            listeners = new ArrayList<>(session.listeners);
            session.listeners.clear();
        }
        for (Listener listener : listeners) {
            try {
                listener.complete();
            } catch (Exception e) {
                log.debug("任务 {} 关闭订阅者失败，已忽略", taskId, e);
            }
        }
    }

    /** 释放任务占用的内存；之后来的订阅走数据库回放 */
    public void close(long taskId) {
        complete(taskId);
        sessions.remove(taskId);
    }

    /** 任务是否仍在本实例内存中（用于诊断与测试） */
    public boolean isActive(long taskId) {
        return sessions.containsKey(taskId);
    }

    /** 从数据库读取指定游标之后的事件 */
    private List<TaskEvent> load(long taskId, int lastEventId) {
        List<TenderTaskEventRecord> rows = eventMapper.findAfter(taskId, lastEventId);
        List<TaskEvent> events = new ArrayList<>(rows.size());
        for (TenderTaskEventRecord row : rows) {
            events.add(new TaskEvent(row.getSeq(), row.getEventType(), row.getPayload()));
        }
        return events;
    }

    /** 订阅者回调；与 {@link reactor.core.publisher.FluxSink} 同构，只是由本类直接持有 */
    private interface Listener {

        void next(TaskEvent event);

        void complete();
    }

    /** 单个任务的运行时状态：监听者列表与事件序号游标 */
    private static final class Session {

        private final long taskId;

        /** 保护 lastSeq / terminal / listeners，同时串行化“写库+通知”与“读库+注册” */
        private final Object lock = new Object();

        private final List<Listener> listeners = new ArrayList<>();

        private int lastSeq;

        private boolean terminal;

        private Session(long taskId, int lastSeq) {
            this.taskId = taskId;
            this.lastSeq = Math.max(lastSeq, 0);
        }

        @Override
        public String toString() {
            return "Session{taskId=" + taskId + ", lastSeq=" + lastSeq + ", terminal=" + terminal + "}";
        }
    }
}
