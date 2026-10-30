package org.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.model.require.ScoringItem;

@Mapper
public interface ScoringItemMapper extends BaseMapper<ScoringItem> {
}