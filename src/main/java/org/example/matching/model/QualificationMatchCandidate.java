package org.example.matching.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 企业资质匹配候选人。
 */
@Data
@Schema(description = "企业资质匹配候选人")
public class QualificationMatchCandidate {

    @Schema(description = "企业资质主键")
    private Long qualificationId;

    @Schema(description = "资质类型", example = "建筑机电安装工程专业承包")
    private String type;

    @Schema(description = "资质名称", example = "建筑机电安装工程专业承包贰级")
    private String name;

    @Schema(description = "资质等级（从名称中识别）", example = "贰级")
    private String level;

    @Schema(description = "证书编号")
    private String certNo;

    @Schema(description = "证书有效期至，长期有效为空")
    private String validUntil;

    @Schema(description = "是否已过期")
    private Boolean expired;

    @Schema(description = "是否满足该条资质要求")
    private Boolean eligible;

    @Schema(description = "匹配判断依据")
    private String reason;
}
