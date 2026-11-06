package org.example.parsing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.parsing.model.PersonnelRequirement;

@Mapper
public interface PersonnelRequirementMapper extends BaseMapper<PersonnelRequirement> {
}