package org.example.parsing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.parsing.model.ScoringItem;

@Mapper
public interface ScoringItemMapper extends BaseMapper<ScoringItem> {
}