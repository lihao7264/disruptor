/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

import com.lmax.disruptor.util.ThreadHints;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Blocking strategy that uses a lock and condition variable for {@link EventProcessor}s waiting on a barrier.
 * <p>
 * This strategy can be used when throughput and low-latency are not as important as CPU resource.
 */
public final class BlockingWaitStrategy implements WaitStrategy {
    private final Lock lock = new ReentrantLock();
    private final Condition processorNotifyCondition = lock.newCondition();

    /**
     * @param sequence          消费者想要消费的下一个序号
     * @param cursorSequence    当前RingBuffer最大的生产者序号
     * @param dependentSequence  依赖序号（即前面依赖的序号）  不能超过
     * @param barrier
     * @return
     * @throws AlertException
     * @throws InterruptedException
     */
    @Override
    public long waitFor(long sequence, Sequence cursorSequence, Sequence dependentSequence, SequenceBarrier barrier) throws AlertException, InterruptedException {
        long availableSequence;
        /**
         * cursorSequence:生产者当前生产的序号
         * 第1重条件判断：如果消费者消费速度 大于 生产者生产速度（即消费者要消费的下一个数据已大于生产者生产的数据时），则消费者阻塞等待
         * 第1重条件判断：
         */
        if (cursorSequence.get() < sequence) {
            // （1）尝试申请消费序号 > 生产者当前生产的序号
            lock.lock();
            try {
                while (cursorSequence.get() < sequence) {
                    barrier.checkAlert();
                    processorNotifyCondition.await();
                }
            } finally {
                lock.unlock();
            }
        }
        // 第2重条件判断：自旋等待
        while ((availableSequence = dependentSequence.get()) < sequence) {
            // (2)依赖前一个消费者的序号  < 尝试申请消费的序号
            barrier.checkAlert();
            //自旋一会
            ThreadHints.onSpinWait();
        }

        return availableSequence;
    }

    /**
     * 如果生产者新生产一个元素，那么唤醒所有消费者
     */
    @Override
    public void signalAllWhenBlocking() {
        lock.lock();
        try {
            processorNotifyCondition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        return "BlockingWaitStrategy{" + "processorNotifyCondition=" + processorNotifyCondition + '}';
    }
}
