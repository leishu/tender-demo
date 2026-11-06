package org.example.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.knowledge.model.KnowledgeBase;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {
}