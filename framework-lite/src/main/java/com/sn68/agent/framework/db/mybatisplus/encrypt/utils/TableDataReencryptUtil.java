package com.sn68.agent.framework.db.mybatisplus.encrypt.utils;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 *
 * @author 钱丁君-chandler 2026/1/15
 */
@Slf4j
public class TableDataReencryptUtil {
    private static int historicalDataPageSize = 1000;

    public TableDataReencryptUtil(int historicalDataPageSize) {
        TableDataReencryptUtil.historicalDataPageSize = historicalDataPageSize;
    }

    /**
     * 通用的表数据加密处理逻辑
     */
    public static <T> int processEncryption(String tableName,
                                            BiFunction<Integer, Integer, List<T>> selectPage,
                                            Consumer<List<T>> batchUpdate,
                                            Function<T, String> idCardExtractor) {
        int offset = 0;
        int count = 0;
        List<T> records;

        do {
            records = selectPage.apply(offset, historicalDataPageSize);
            // 使用 Hutool 的 CollUtil 和 StrUtil 简化过滤逻辑
            List<T> needUpdate = CollUtil.filter(records,
                    item -> StrUtil.isNotBlank(idCardExtractor.apply(item)));

            if (CollUtil.isNotEmpty(needUpdate)) {
                batchUpdate.accept(needUpdate);
                count += needUpdate.size();
                log.info("{} 表第 {} 页加密完成，处理了 {} 条记录",
                        tableName, (offset / historicalDataPageSize) + 1, needUpdate.size());
            }
            offset += historicalDataPageSize;
        } while (CollUtil.size(records) == historicalDataPageSize);

        log.info("{} 表加密完成，共计 {} 条", tableName, count);
        return count;
    }
}
