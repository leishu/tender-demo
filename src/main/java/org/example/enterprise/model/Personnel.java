package org.example.enterprise.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@TableName("t_personnel")
@Schema(description = "企业人员")
public class Personnel {
    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    /** 姓名 */
    @Schema(description = "姓名", example = "张三")
    private String name;

    /** 性别：男 / 女，未知留空 */
    @Schema(description = "性别：男 / 女，未知留空", example = "男")
    private String gender;

    /** 出生年月，日期部分固定为 01 */
    @Schema(description = "出生年月，日期部分固定为 01", example = "1985-06-01")
    private LocalDate birthDate;

    /** 从业年限 */
    @Schema(description = "从业年限（年）", example = "8")
    private Integer yearsOfExperience;

    /** 可担任的岗位，一人多岗；不落库，由仓储层按 personnel_id 装配 */
    @TableField(exist = false)
    @Schema(description = "可担任的岗位，一人多岗", example = "[\"项目经理\", \"技术负责人\"]")
    private List<String> roles;

    /** 证书与职称，一人多张；职称也在此表中（type = 职称）；不落库，由仓储层装配 */
    @TableField(exist = false)
    @Schema(description = "证书与职称，一人多张（职称的 type 为「职称」）")
    private List<PersonnelCertificate> certificates;
}
