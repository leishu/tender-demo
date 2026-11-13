package org.example.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.task.model.TenderTaskRecord;

/**
 * t_tender_task 访问层。
 *
 * <p>常规读写走 MyBatis-Plus 的 {@link BaseMapper}；另加几条手写 SQL 承载带条件的
 * 状态流转与并发准入，避免“先查后写”的竞态：</p>
 * <ul>
 *   <li>{@link #advanceEventSeq}：把事件的落库进度往前推，旧序号不会回退；</li>
 *   <li>{@link #finish}：只在任务还未进入终态时落地终态，重复调用无副作用（取消与自然结束会竞争）；</li>
 *   <li>{@link #reopen}：幂等的同步任务失败后复用同一行重跑；</li>
 *   <li>{@link #countRunning} / {@link #countRunningByUser}：并发准入时数“在跑的任务”。</li>
 * </ul>
 */
@Mapper
public interface TenderTaskMapper extends BaseMapper<TenderTaskRecord> {

    /**
     * 推进“已落库的最大事件序号”。
     *
     * <p>带 {@code last_event_seq < #{seq}} 条件：并发或重复写入时只会前进不会后退。
     * 该列只是进度缓存，<b>不作为续传游标</b>：写入事件与更新该列是两条 SQL，
     * 崩溃窗口内会滞后，拿它续传会重放重复事件。续传游标一律取事件表的
     * {@link TenderTaskEventMapper#maxSeq(long)}；该列更新失败也不影响回放正确性。</p>
     */
    @Update("""
            UPDATE t_tender_task
               SET last_event_seq = #{seq},
                   updated_at     = #{now}
             WHERE id = #{taskId}
               AND last_event_seq < #{seq}
            """)
    int advanceEventSeq(@Param("taskId") long taskId,
                        @Param("seq") int seq,
                        @Param("now") long now);

    /**
     * 把任务行的会话 ID 补上（只用于本次改造前创建的历史行）。
     *
     * <p>老任务的会话 ID 没有记录，无法真的续写；这里补发一个新的并落库，
     * 让「返回给客户端的 ID 一定能在任务行里查到」这个约定保持成立。</p>
     */
    @Update("""
            UPDATE t_tender_task
               SET session_id = #{sessionId}
             WHERE id = #{taskId}
            """)
    int updateSessionId(@Param("taskId") long taskId, @Param("sessionId") String sessionId);

    /**
     * 落地终态：只有 {@code pending/running} 的任务会被更新。
     *
     * @return 命中行数；0 表示任务已进入终态（如已被取消），调用方据此忽略本次写入
     */
    @Update("""
            UPDATE t_tender_task
               SET status        = #{status},
                   error_message = #{errorMessage},
                   result_json   = #{resultJson},
                   finished_at   = #{now},
                   updated_at    = #{now}
             WHERE id = #{taskId}
               AND status IN ('pending', 'running')
            """)
    int finish(@Param("taskId") long taskId,
               @Param("status") String status,
               @Param("errorMessage") String errorMessage,
               @Param("resultJson") String resultJson,
               @Param("now") long now);

    /**
     * 把失败的同步任务重置为执行中，复用同一行重跑（幂等键仍然占用，不会新建任务）。
     *
     * @return 命中行数；0 表示任务不是失败/取消状态，不该重跑
     */
    @Update("""
            UPDATE t_tender_task
               SET status        = 'running',
                   error_message = NULL,
                   result_json   = NULL,
                   finished_at   = NULL,
                   updated_at    = #{now}
             WHERE id = #{taskId}
               AND status IN ('failed', 'cancelled')
            """)
    int reopen(@Param("taskId") long taskId, @Param("now") long now);

    /**
     * 刷新任务的“最后活跃时间”，供陈旧回收判断任务是否还活着。
     *
     * <p>同步任务（目录/章节）在模型调用期间不会写事件，必须靠心跳维持租约，
     * 否则长耗时任务会被 {@code recoverStaleTasks} 误判为中断。</p>
     */
    @Update("""
            UPDATE t_tender_task
               SET updated_at = #{now}
             WHERE id = #{taskId}
               AND status = 'running'
            """)
    int touch(@Param("taskId") long taskId, @Param("now") long now);

    /**
     * 复用“长时间无进展”的执行中任务重跑。
     *
     * <p>覆盖客户端断开、进程崩溃等导致任务停在 {@code running} 的场景：
     * 只有超过租约时间没有更新（{@code updated_at < staleBefore}）的任务才允许被接管，
     * 避免把仍在正常执行的任务重复跑一遍。</p>
     *
     * @return 命中行数；0 表示任务仍新鲜或已进入终态
     */
    @Update("""
            UPDATE t_tender_task
               SET status        = 'running',
                   error_message = NULL,
                   result_json   = NULL,
                   finished_at   = NULL,
                   updated_at    = #{now}
             WHERE id = #{taskId}
               AND status IN ('pending', 'running')
               AND updated_at < #{staleBefore}
            """)
    int reopenStale(@Param("taskId") long taskId,
                    @Param("now") long now,
                    @Param("staleBefore") long staleBefore);

    /**
     * 统计仍在执行中的任务数（全实例），用于并发准入。
     *
     * <p>只数 {@code updated_at >= #{freshSince}} 的行：心跳断了超过租约的任务已经算死，
     * 让它继续占名额会让用户被卡到下一轮回收扫描（默认 1 分钟一轮、10 分钟才判死）。
     * 代价是心跳关闭时这个统计会偏小——那种配置下并发保护本来就只能尽力而为。</p>
     */
    @Select("""
            SELECT COUNT(*)
              FROM t_tender_task
             WHERE status IN ('pending', 'running')
               AND updated_at >= #{freshSince}
            """)
    int countRunning(@Param("freshSince") long freshSince);

    /** 统计某个用户仍在执行中的任务数，用于按用户并发准入（条件同上） */
    @Select("""
            SELECT COUNT(*)
              FROM t_tender_task
             WHERE status IN ('pending', 'running')
               AND updated_at >= #{freshSince}
               AND user_id = #{userId}
            """)
    int countRunningByUser(@Param("userId") String userId,
                           @Param("freshSince") long freshSince);
}
