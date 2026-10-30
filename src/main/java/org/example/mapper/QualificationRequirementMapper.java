package org.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.model.require.QualificationRequirement;

@Mapper
public interface QualificationRequirementMapper extends BaseMapper<QualificationRequirement> {
}