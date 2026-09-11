package com.sn68.agent.framework.commons.entity.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * @author Levin
 *
 * Keep dictionary values aligned with the related-type enum used by inventory.
 */
@Getter
@JsonFormat
@RequiredArgsConstructor
public enum StockRelatedType implements DictEnum<String> {

    RECEIVER_BOX("0", "收箱"),
    ALLOT("1", "调拨"),
    HEAVY_BOX("2", "重箱"),
    SEND_BOX("3", "送箱"),
    RETURN_BOX("4", "退箱"),

    CUSTOMER_SENDER_SCAN("11", "客户发货"),
    CUSTOMER_RECEIVER_SCAN("12", "客户收货"),
    INVENTORY("31", "盘点"),
    RELEASE("21", "投箱"),
    SCRAP("22", "报废"),


//    OTHER_OUT("pc:other:out", "其它出库"),
//    OTHER_IN("pc:other:in", "其它入库"),


    ;

    @EnumValue
    @JsonValue
    private final String value;
    private final String label;

    @JsonCreator
    public static StockRelatedType of(String type) {
        if (type == null) {
            return null;
        }
        for (StockRelatedType info : values()) {
            if (type.equals(info.value)) {
                return info;
            }
        }
        return null;
    }
}