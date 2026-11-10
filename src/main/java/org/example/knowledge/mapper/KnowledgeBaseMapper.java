package org.example.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.knowledge.model.KnowledgeBase;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    /**
     * 原子累加文档数：{@code document_count = document_count + delta}，
     * 避免“先读后写”在并发写入时丢更新。
     */
    @Update("""
            UPDATE t_knowledge_base
               SET document_count = document_count + #{delta},
                   updated_at = #{updatedAt}
             WHERE id = #{id}
            """)
    int incrementDocumentCount(@Param("id") Long id,
                               @Param("delta") long delta,
                               @Param("updatedAt") long updatedAt);
}