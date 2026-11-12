package org.example.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.task.model.TenderTaskRecord;

/**
 * t_tender_task 访问层。
 *
 * <p>常规读写走 MyBatis-Plus 的 {@link BaseMapper}；另加三条手写 SQL 承载带条件的
 * 状态流转，避免“先查后写”的竞态：</p>
 * <ul>
 *   <li>{@link #advanceEventSeq}：把事件的落库进度往前推，旧序号不会回退；</li>
 *   <li>{@link #finish}：只在任务还未进入终态时落地终态，重复调用无副作用（取消与自然结束会竞争）；</li>
 *   <li>{@link #reopen}：幂等的同步任务失败后复用同一行重跑。</li>
 * </ul>
 */
@Mapper
public interface TenderTaskMapper extends BaseMapper<TenderTaskRecord> {

    /**
     * 推进“已落库的最大事件序号”。
     *
     * <p>带 {@code last_event_seq < #{seq}} 条件：并发或重复写入时只会前进不会后退。
     * 该列只用于展示进度与续传游标，即使某次更新失败，事件本身已经落库，
     * 回放仍按 {@code t_tender_task_event.seq} 进行，不影响正确性。</p>
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
}
