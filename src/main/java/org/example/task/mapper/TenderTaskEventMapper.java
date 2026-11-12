package org.example.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.task.model.TenderTaskEventRecord;

import java.util.List;

/**
 * t_tender_task_event 访问层。
 *
 * <p>订阅时按 {@code seq > lastEventId} 顺序回放，走唯一键
 * {@code uk_tender_task_event_seq(task_id, seq)}，不做全表扫描。</p>
 */
@Mapper
public interface TenderTaskEventMapper extends BaseMapper<TenderTaskEventRecord> {

    /**
     * 读取指定游标之后的事件（含终态事件），按序号升序。
     *
     * @param lastEventId 客户端已收到的最大序号；0 表示从头回放
     */
    @Select("""
            SELECT id, task_id, seq, event_type, payload, created_at
              FROM t_tender_task_event
             WHERE task_id = #{taskId}
               AND seq > #{lastEventId}
             ORDER BY seq ASC
            """)
    List<TenderTaskEventRecord> findAfter(@Param("taskId") long taskId,
                                          @Param("lastEventId") int lastEventId);

    /**
     * 事件表里的真实最大序号。
     *
     * <p>{@code t_tender_task.last_event_seq} 只是缓存列，写入事件与更新该列是两条 SQL，
     * 进程在两者之间崩溃时会滞后。恢复/取消这类“续写终止事件”的场景必须以事件表为准，
     * 否则分配出的 seq 可能撞上唯一键 {@code uk_tender_task_event_seq}。</p>
     *
     * @return 最大序号；没有事件时返回 0
     */
    @Select("""
            SELECT COALESCE(MAX(seq), 0)
              FROM t_tender_task_event
             WHERE task_id = #{taskId}
            """)
    int maxSeq(@Param("taskId") long taskId);
}
