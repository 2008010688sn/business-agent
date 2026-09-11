package com.sn68.agent.framework.commons.entity.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * @author Levin
 */
@Getter
@JsonFormat
@NoArgsConstructor
@AllArgsConstructor
public enum ProcessApprovalStatus implements DictEnum<String> {

    /**
     * 流程审批状态
     * pending=待审批;reject=审批拒绝;pass=审批通过
     */
    PENDING("pending", "待审批"),
    PROCESSING("processing", "审批中"),
    REJECT("reject", "审批拒绝"),
    PASS("pass", "审批通过");
//    REFUSAL("refusal", "驳回"),
//    CANCEL("cancel", "取消"),
//    TURN("turn", "转办"),

    ;

    @EnumValue
    @JsonValue
    private String value;
    private String label;

    @JsonCreator
    public static ProcessApprovalStatus of(String type) {
        if (type == null) {
            return null;
        }
        for (ProcessApprovalStatus info : values()) {
            if (info.value.equals(type)) {
                return info;
            }
        }
        return null;
    }

}
