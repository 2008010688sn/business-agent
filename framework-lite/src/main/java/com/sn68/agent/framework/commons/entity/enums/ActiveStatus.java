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
public enum ActiveStatus implements DictEnum<String> {

    /**
     * enabled = 启用
     * disabled = 禁用
     */
    ENABLED("enabled", "启用"),

    DISABLED("disabled", "禁用"),

    ;

    @EnumValue
    @JsonValue
    private String value;
    private String label;

    @JsonCreator
    public static ActiveStatus of(String type) {
        if (type == null) {
            return null;
        }
        for (ActiveStatus info : values()) {
            if (info.value.equals(type)) {
                return info;
            }
        }
        return null;
    }

}
