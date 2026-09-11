package com.sn68.agent.framework.commons.entity.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

/**
 * 出入库流水表，类型
 *
 * @author horen
 */
@Getter
@JsonFormat
@RequiredArgsConstructor
public enum StockFlowType implements DictEnum<String> {
    /**
     * 出入库流水类型，in=入库，out=出库
     */
    IN("in", "入库"),
    OUT("out", "出库"),
    ;

    @EnumValue
    @JsonValue
    private final String value;
    private final String label;

    @JsonCreator
    public static StockFlowType of(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        for (StockFlowType typeEnum : values()) {
            if (typeEnum.value.equals(value)) {
                return typeEnum;
            }
        }
        return null;
    }
}
