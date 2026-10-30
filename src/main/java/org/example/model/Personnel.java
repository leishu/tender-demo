package org.example.model;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@TableName("t_personnel")
@Schema(description = "企业人员及其资质")
public class Personnel {
    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    /** 企业ID */
    @Schema(description = "企业ID")
    private Long enterpriseId;

    /** 姓名 */
    @Schema(description = "姓名", example = "张三")
    private String name;

    /** 角色，如 项目经理 */
    @Schema(description = "角色，如 项目经理", example = "项目经理")
    private String role;

    /** 职称/资格证书 */
    @Schema(description = "职称/资格证书", example = "一级建造师")
    private String title;

    /** 从业年限 */
    @Schema(description = "从业年限（年）", example = "8")
    private Integer yearsOfExperience;
}
