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

import com.lmax.disruptor.util.Util;

import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;

abstract class SingleProducerSequencerPad extends AbstractSequencer
{   // 56 个字节
    protected byte
            p10, p11, p12, p13, p14, p15, p16, p17,
            p20, p21, p22, p23, p24, p25, p26, p27,
            p30, p31, p32, p33, p34, p35, p36, p37,
            p40, p41, p42, p43, p44, p45, p46, p47,
            p50, p51, p52, p53, p54, p55, p56, p57,
            p60, p61, p62, p63, p64, p65, p66, p67,
            p70, p71, p72, p73, p74, p75, p76, p77;

    SingleProducerSequencerPad(final int bufferSize, final WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }
}

abstract class SingleProducerSequencerFields extends SingleProducerSequencerPad
{
    SingleProducerSequencerFields(final int bufferSize, final WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
     * Set to -1 as sequence starting point
     */
    // 生产者当前生产的最新序号
    long nextValue = Sequence.INITIAL_VALUE;

    // 获取到上一次的GatingSequence（cachedValue 存储的是上一次多个消费者消费最小的序号）
    long cachedValue = Sequence.INITIAL_VALUE;
}

/**
 * Coordinator for claiming sequences for access to a data structure while tracking dependent {@link Sequence}s.
 * Not safe for use from multiple threads as it does not implement any barriers.
 *
 * <p>* Note on {@link Sequencer#getCursor()}:  With this sequencer the cursor value is updated after the call
 * to {@link Sequencer#publish(long)} is made.
 */

public final class SingleProducerSequencer extends SingleProducerSequencerFields
{
    protected byte
            p10, p11, p12, p13, p14, p15, p16, p17,
            p20, p21, p22, p23, p24, p25, p26, p27,
            p30, p31, p32, p33, p34, p35, p36, p37,
            p40, p41, p42, p43, p44, p45, p46, p47,
            p50, p51, p52, p53, p54, p55, p56, p57,
            p60, p61, p62, p63, p64, p65, p66, p67,
            p70, p71, p72, p73, p74, p75, p76, p77;

    /**
     * Construct a Sequencer with the selected wait strategy and buffer size.
     *
     * @param bufferSize   the size of the buffer that this will sequence over.
     * @param waitStrategy for those waiting on sequences.
     */
    public SingleProducerSequencer(final int bufferSize, final WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
     * @see Sequencer#hasAvailableCapacity(int)
     */
    @Override
    public boolean hasAvailableCapacity(final int requiredCapacity)
    {
        return hasAvailableCapacity(requiredCapacity, false);
    }

    private boolean hasAvailableCapacity(final int requiredCapacity, final boolean doStore)
    {
        long nextValue = this.nextValue;

        long wrapPoint = (nextValue + requiredCapacity) - bufferSize;
        long cachedGatingSequence = this.cachedValue;

        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
            if (doStore)
            {
                cursor.setVolatile(nextValue);  // StoreLoad fence
            }

            long minSequence = Util.getMinimumSequence(gatingSequences, nextValue);
            this.cachedValue = minSequence;

            if (wrapPoint > minSequence)
            {
                return false;
            }
        }

        return true;
    }

    /**
     * @see Sequencer#next()
     */
    @Override
    public long next()
    {
        return next(1);
    }

    /**
     * @see Sequencer#next(int)
     */
    @Override
    public long next(final int n)
    {
        if (n < 1 || n > bufferSize) {
            throw new IllegalArgumentException("n must be > 0 and < bufferSize");
        }
        /**
         * 总是获取到生产者已生产的当前序号
         */
        long nextValue = this.nextValue;
        // 序号值=生产者当前序号值+期望获取的序号数
        long nextSequence = nextValue + n;
        /**
         * 减掉RingBuffer的总 bufferSize 值，用于判断是否出现‘覆盖’
         * wrapPoint（缠绕点）：nextSequence 减去一个环的大小
         */
        long wrapPoint = nextSequence - bufferSize;
        // 获取到上一次的GatingSequence，因为是缓存，这里不是最新的
        long cachedGatingSequence = this.cachedValue;
        /**
         * cachedValue：缓存的消费者中最小序号值
         *             是cache的原因：仅是缓存，并非当前最新的‘消费者中最小序号值’。
         *             cachedValue是上次程序进入到下面的if判定代码段时，被赋值的当时的‘消费者中最小序号值’
         * 该做法的好处：在判定是否出现覆盖时，无需每次都调用 计算‘消费者中的最小序号值’，从而节约开销。
         *             只需确保当生产者的 序号 大于了 缓存的 cachedGatingSequence 一个bufferSize时，重新获取下 getMinimumSequence()即可。
         *  需重新获取cachedGatingSequence 的两个条件：
         *    条件一：(wrapPoint > cachedGatingSequence) ： 当生产者已超过上一次缓存的‘消费者中最小序号值’（cachedGatingSequence）一个‘Ring’大小（bufferSize）时
         *         避免当生产者一直在生产，但消费者不再消费的情况下出现‘覆盖’
         *    条件二：(cachedGatingSequence > nextValue) ：消费者seq> 生产者seq , 已消费完
         *          理论上：生产者和消费者均为顺序递增的 且 生产者的seq“先于”消费者的seq。
         */
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
            cursor.setVolatile(nextValue);  // StoreLoad fence

            long minSequence;
            /**
             * 自旋等待
             * gatingSequences 是‘消费者中最小序号值’
             *  等待消费者，消费一些事件，RingBuffer 有空闲空间
             */
            while (wrapPoint > (minSequence = Util.getMinimumSequence(gatingSequences, nextValue)))
            {
                LockSupport.parkNanos(1L); // TODO: Use waitStrategy to spin?
            }
            // 更新消费者中最小序号值
            this.cachedValue = minSequence;
        }
        // 将获取的nextSequence赋值给生产者当前值nextValue
        this.nextValue = nextSequence;
        return nextSequence;
    }
    /**
     * @see Sequencer#tryNext()
     */
    @Override
    public long tryNext() throws InsufficientCapacityException
    {
        return tryNext(1);
    }

    /**
     * @see Sequencer#tryNext(int)
     */
    @Override
    public long tryNext(final int n) throws InsufficientCapacityException
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        if (!hasAvailableCapacity(n, true))
        {
            throw InsufficientCapacityException.INSTANCE;
        }

        long nextSequence = this.nextValue += n;

        return nextSequence;
    }

    /**
     * @see Sequencer#remainingCapacity()
     */
    @Override
    public long remainingCapacity()
    {
        long nextValue = this.nextValue;

        long consumed = Util.getMinimumSequence(gatingSequences, nextValue);
        long produced = nextValue;
        return getBufferSize() - (produced - consumed);
    }

    /**
     * @see Sequencer#claim(long)
     */
    @Override
    public void claim(final long sequence)
    {
        this.nextValue = sequence;
    }

    /**
     * 4：发布事件、唤醒消费者
     * @see Sequencer#publish(long)
     */
    @Override
    public void publish(final long sequence)
    {
        // 【1】给生产者cursor游标赋值新的sequence，说明该sequenc对应的对象数据已经填充（生产）完毕
        cursor.set(sequence);// 这个cursor即生产者生产时移动的游标，是AbstractSequencer的成员变量
        // 【2】根据阻塞策略将所有消费者唤醒
        // 注意：这个waitStrategy实例是所有消费者和生产者共同引用的
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * @see Sequencer#publish(long, long)
     */
    @Override
    public void publish(final long lo, final long hi)
    {
        publish(hi);
    }

    /**
     * @see Sequencer#isAvailable(long)
     */
    @Override
    public boolean isAvailable(final long sequence)
    {
        final long currentSequence = cursor.get();
        return sequence <= currentSequence && sequence > currentSequence - bufferSize;
    }

    @Override
    public long getHighestPublishedSequence(final long lowerBound, final long availableSequence)
    {
        return availableSequence;
    }

    @Override
    public String toString()
    {
        return "SingleProducerSequencer{" +
                "bufferSize=" + bufferSize +
                ", waitStrategy=" + waitStrategy +
                ", cursor=" + cursor +
                ", gatingSequences=" + Arrays.toString(gatingSequences) +
                '}';
    }
}
