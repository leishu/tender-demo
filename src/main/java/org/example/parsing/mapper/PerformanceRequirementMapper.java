package org.example.parsing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.parsing.model.PerformanceRequirement;

import java.util.List;

@Mapper
public interface PerformanceRequirementMapper extends BaseMapper<PerformanceRequirement> {

    /**
     * 批量插入。调用方需保证 {@code list} 非空且已完成字段归一化，
     * 单条 SQL 完成写入，避免逐条往返。
     */
    @Insert("""
            <script>
            INSERT INTO t_performance_requirement
                (tender_requirement_id, project_type, quantity, amount, time_range, source)
            VALUES
            <foreach collection="list" item="item" separator=",">
                (#{item.tenderRequirementId}, #{item.projectType}, #{item.quantity},
                 #{item.amount}, #{item.timeRange}, #{item.source})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("list") List<PerformanceRequirement> list);
}
