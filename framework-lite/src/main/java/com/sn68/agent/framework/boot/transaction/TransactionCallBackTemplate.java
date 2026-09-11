package com.sn68.agent.framework.boot.transaction;


import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * @author Levin
 */
@Configuration
public class TransactionCallBackTemplate {

    public void execute(Runnable runnable) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 事务提交后执行回调
                    runnable.run();
                }
            });
        } else {
            // 事务提交后执行回调
            runnable.run();
        }
    }
}
