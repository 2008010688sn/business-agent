package com.sn68.agent.framework.commons.entity.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;


/**
 * 库存操作类型
 *
 * @author Levin
 * Keep dictionary values aligned with the inventory operation enum.
 */
@Getter
@JsonFormat
@RequiredArgsConstructor
public enum StockOperationType implements DictEnum<String> {

    /**
     * 资产操作类型
     */
    INIT("init", "期初", "期初"),
    PDA_INVENTORY("pda:inventory", "盘点", "盘点"),
    MINI_INVENTORY("mini:inventory", "扫码团盘点", "扫码团盘点"),

    TMS_IN("pc-tms:in", "物流执行签收", "物流执行签收"),
    TMS_OUT("pc-tms:out", "物流执行出库", "物流执行出库"),
    MIXED_INVENTORY("mixed-process:inventory", "混箱拆分（盘点）", "盘点混箱拆分"),

    WMS_IN("pc-wms:in", "复合仓签收", "复合仓签收"),
    WMS_OUT("pc-wms:out", "复合仓出库", "复合仓出库"),

    PDA_IN("pda:in", "复合仓PDA入库", "复合仓PDA入库"),
    PDA_OUT("pda:out", "复合仓PDA出库", "复合仓PDA出库"),

    PDA_SELF_ORDER_OUT("pda:self-order:out", "建单出库", "建单出库"),
    PDA_SELF_ORDER_IN("pda:self-order:in", "建单入库", "建单入库"),
    MINI_SMT_ORDER("mini:smt-order", "扫码团", "扫码团"),
    MINI_SMT_ORDER_IN("mini:smt-order:in", "扫码团入库", "扫码团入库"),
    MINI_SMT_ORDER_OUT("mini:smt-order:out", "扫码团出库", "扫码团出库"),

    CLIENT_PDA_IN("client-pda:in", "客户端PDA入库", "客户端PDA入库"),
    CLIENT_PDA_OUT("client-pda:out", "客户端PDA出库", "客户端PDA出库"),

    OTHER_OUT("pc:other:out", "其它出库", "其它出库"),
    OTHER_IN("pc:other:in", "其它入库", "其它出库"),


    REPAIR_IN("repair:in", "维修入库", "维修入库"),
    REPAIR_OUT("repair:out", "维修出库", "维修出库"),

    @Deprecated
    SCRAP_OUT("31", "报废出库", "箱号: %s、需求号: %s"),
    CHANGE_NUM("order-num:change", "物流异常改单", "物流异常修改数量"),
    HEAVY_CHANGE_NUM("order-num:change:heavy", "重箱签收调整数量", "重箱签收调整数量"),

    ;

    @EnumValue
    @JsonValue
    private final String value;
    private final String label;
    private final String format;


    @JsonCreator
    public static StockOperationType of(String type) {
        if (type == null) {
            return null;
        }
        for (StockOperationType info : values()) {
            if (info.value.equals(type)) {
                return info;
            }
        }
        return null;
    }

    public String getFormat(String... args) {
        Object[] processedArgs = Arrays.stream(args)
                .map(arg -> arg == null ? "" : arg)
                .toArray();
        return String.format(this.format, processedArgs);
    }
}
