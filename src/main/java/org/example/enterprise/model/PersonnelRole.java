package org.example.enterprise.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@TableName("t_personnel_role")
@Schema(description = "人员可担任岗位")
public class PersonnelRole {
    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    /** 关联的人员ID */
    @Schema(description = "关联的人员ID")
    private Long personnelId;

    /** 可担任的岗位，如 项目经理 */
    @Schema(description = "可担任的岗位，如 项目经理", example = "项目经理")
    private String role;
}
