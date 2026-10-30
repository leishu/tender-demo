package org.example.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

@Data
@TableName("t_qualification")
@Schema(description = "企业资质")
public class Qualification {
    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    /** 企业ID */
    @Schema(description = "企业ID")
    private Long enterpriseId;

    /** 资质类型，如 ISO9001 */
    @Schema(description = "资质类型，如 ISO9001", example = "ISO9001")
    private String type;

    /** 资质名称 */
    @Schema(description = "资质名称", example = "质量管理体系认证证书")
    private String name;

    /** 证书编号 */
    @Schema(description = "证书编号", example = "00124Q31281R0M")
    private String certNo;

    /** 证书有效期至 */
    @Schema(description = "证书有效期至", example = "2027-05-31")
    private LocalDate validUntil;
}
