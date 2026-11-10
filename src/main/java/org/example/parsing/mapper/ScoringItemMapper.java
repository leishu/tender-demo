package org.example.parsing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.parsing.model.ScoringItem;

import java.util.List;

@Mapper
public interface ScoringItemMapper extends BaseMapper<ScoringItem> {

    /**
     * 批量插入。调用方需保证 {@code list} 非空且已完成字段归一化，
     * 单条 SQL 完成写入，避免逐条往返。
     */
    @Insert("""
            <script>
            INSERT INTO t_scoring_item
                (tender_requirement_id, item_id, `name`, score, criterion, suggested_chapter, source)
            VALUES
            <foreach collection="list" item="item" separator=",">
                (#{item.tenderRequirementId}, #{item.itemId}, #{item.name}, #{item.score},
                 #{item.criterion}, #{item.suggestedChapter}, #{item.source})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("list") List<ScoringItem> list);
}
