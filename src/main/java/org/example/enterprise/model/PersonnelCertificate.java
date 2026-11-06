package org.example.enterprise.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

@Data
@TableName("t_personnel_certificate")
@Schema(description = "人员证书与职称")
public class PersonnelCertificate {
    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    /** 关联的人员ID */
    @Schema(description = "关联的人员ID")
    private Long personnelId;

    /** 证书类型：职称 / 执业资格 / 岗位证书 等 */
    @Schema(description = "证书类型：职称 / 执业资格 / 岗位证书 等", example = "执业资格")
    private String type;

    /** 名称：证书名，或职称名（如 高级工程师） */
    @Schema(description = "名称：证书名，或职称名（如 高级工程师）", example = "一级建造师")
    private String name;

    /** 专业/等级：证书专业，或职称专业（如 电气） */
    @Schema(description = "专业/等级：证书专业，或职称专业（如 电气）", example = "机电工程")
    private String major;

    /** 证书编号 */
    @Schema(description = "证书编号", example = "2023011234")
    private String certNo;

    /** 有效期至，长期有效留空（职称通常留空） */
    @Schema(description = "有效期至，长期有效留空（职称通常留空）", example = "2028-12-31")
    private LocalDate validUntil;
}
