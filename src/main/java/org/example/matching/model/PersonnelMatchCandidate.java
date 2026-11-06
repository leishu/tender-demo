package org.example.matching.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 人员匹配候选人。
 */
@Data
@Schema(description = "人员匹配候选人")
public class PersonnelMatchCandidate {

    @Schema(description = "企业人员主键")
    private Long personnelId;

    @Schema(description = "姓名", example = "张建国")
    private String name;

    @Schema(description = "性别", example = "男")
    private String gender;

    @Schema(description = "从业年限", example = "24")
    private Integer yearsOfExperience;

    @Schema(description = "可担任岗位", example = "[\"项目经理\",\"技术负责人\"]")
    private List<String> roles;

    @Schema(description = "命中的岗位要求")
    private List<String> matchedRoles;

    @Schema(description = "命中的证书/职称要求")
    private List<String> matchedCertificates;

    @Schema(description = "关键证书有效期至（无特定证书要求时为空）")
    private String certificateValidUntil;

    @Schema(description = "是否满足该条人员要求")
    private Boolean eligible;

    @Schema(description = "匹配判断依据")
    private String reason;
}
