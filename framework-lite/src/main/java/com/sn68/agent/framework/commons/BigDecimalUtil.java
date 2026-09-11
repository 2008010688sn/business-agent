package com.sn68.agent.framework.commons;

import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;

/**
 * @author AFZ
 * @since 2021/6/18 13:05
 **/
public class BigDecimalUtil {

    /**
     * 计算两个数的占比, 结果返回百分比, 无论是除数还是被除数为0都是0%
     *
     * @param number1 被除数
     * @param number2 除数
     * @param <R>
     * @param <T>
     * @return
     */
    public static <R extends Number, T extends Number> String getRatio(R number1, T number2) {
        if (number1 == null || number1.intValue() == 0 || number2 == null || number2.intValue() == 0) {
            return "0.00%";
        }
        BigDecimal ratio = BigDecimal.valueOf(number1.doubleValue())
                .divide(BigDecimal.valueOf(number2.doubleValue() * 100), 4, RoundingMode.HALF_UP);
        return String.format("%.2f", ratio.doubleValue()) + "%";
    }

    /**
     * 两个浮点数相加
     *
     * @param f1
     * @param f2
     * @return
     */
    public static Float floatAdd(Float f1, Float f2) {
        Float add = (f1 != null ? f1 : 0) + (f2 != null ? f2 : 0);
        BigDecimal big = new BigDecimal(add);
        big.setScale(2, RoundingMode.HALF_UP);
        return big.floatValue();
    }

    /**
     * 数字缩小指定倍数并舍去小数位保留整数
     *
     * @param number 数字
     * @param i      倍数
     * @return
     */
    public static <R extends Number> Long float2String(R number, Integer i) {
        if (number == null) {
            return null;
        }
        if (i == null || i == 0) {
            i = 1;
        }
        BigDecimal bd = BigDecimal.valueOf(number.doubleValue())
                .divide(BigDecimal.valueOf(i.doubleValue()), 0, RoundingMode.DOWN);
        return bd.longValue();
    }


    /**
     * 百分比字符串转Double
     *
     * @param number
     * @return
     */
    @SneakyThrows
    public static Double percentage2Double(String number) {
        if (StringUtils.isBlank(number)) {
            return null;
        }
        NumberFormat nf = NumberFormat.getPercentInstance();
        //将百分数转换成Number类型
        Number m = nf.parse(number);
        return m.doubleValue();
    }

    /***
     * 费用转换
     * @param fareStr
     * @return
     */
    public static double formatPrice(String fareStr) {
        return StringUtils.isNotEmpty(fareStr) ? Double.valueOf(fareStr) : 0.0;
    }
}
