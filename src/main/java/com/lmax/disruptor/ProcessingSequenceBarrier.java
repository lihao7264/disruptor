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


/**
 * {@link SequenceBarrier} handed out for gating {@link EventProcessor}s on a cursor sequence and optional dependent {@link EventProcessor}(s),
 * using the given WaitStrategy.
 *
 *
 * ProcessingSequenceBarrier作用
 * （1）waitFor：获取下一个可用的消费序号
 * （2）getCursor：获取依赖对象（生产者 或 消费者序号）序号的最小序号
 */
final class ProcessingSequenceBarrier implements SequenceBarrier {
    /**
     *  等待策略waitStrategy
     */
    private final WaitStrategy waitStrategy;

    /**
     *  依赖序号（即前面依赖的序号）  不能超过
     *  如果消费者前面依赖的其它消费者链长度为0（即只有一个消费者的情况下），则dependentSequence是生产者的cursor游标
     */
    private final Sequence dependentSequence;
    /**
     * 是否警报
     */
    private volatile boolean alerted = false;
    /**
     *  生产者位cursorSequence
     */
    private final Sequence cursorSequence;
    /**
     *  生产者Sequencer
     */
    private final Sequencer sequencer;

    /**
     *
     * @param sequencer       生产者实例引用
     * @param waitStrategy    等待策略
     * @param cursorSequence  生产者 当前生产的序号 序号
     * @param dependentSequences 依赖的序号
     *                           注意：第一次时，dependentSequences是一个空数组
     */
    ProcessingSequenceBarrier(
            final Sequencer sequencer,
            final WaitStrategy waitStrategy,
            final Sequence cursorSequence,
            final Sequence[] dependentSequences) {
        // 生产者实例
        this.sequencer = sequencer;
        // 等待策略waitStrategy
        this.waitStrategy = waitStrategy;
        // cursorSequence持有的是AbstractSequencer的成员变量cursor实例的引用
        // 当前生产者序号
        this.cursorSequence = cursorSequence;
        /**
         * 如果消费者前面依赖的其它消费者链长度为0（即只有一个消费者的情况下），
         * 则dependentSequence是生产者的cursor游标
         */
        if (0 == dependentSequences.length) {
            // dependentSequences 为0，则依赖 生产者序号
            dependentSequence = cursorSequence;
        }
        /**
         * 如果消费者前面还有被依赖的消费者，则dependentSequence是前面消费者的sequence
         * 如果是指定执行顺序链的会执行到这里，
         * 比如：disruptor.after(eventHandler1).handleEventsWith(eventHandler2);
         */
        else {
            // FixedSequenceGroup 包装了Sequence[] dependentSequences数组，然后提供了获取该数组中最小序号的方法
            dependentSequence = new FixedSequenceGroup(dependentSequences);
        }
    }

    @Override
    public long waitFor(final long sequence)
            throws AlertException, InterruptedException, TimeoutException {
        checkAlert();
        // （1）委托给waitStrategy实现类
        long availableSequence = waitStrategy.waitFor(sequence, cursorSequence, dependentSequence, this);

        if (availableSequence < sequence) {
            return availableSequence;
        }
        // 这个主要是针对多生产者的情形，单生产者返回availableSequence
        return sequencer.getHighestPublishedSequence(sequence, availableSequence);
    }

    @Override
    public long getCursor() {
        return dependentSequence.get();
    }

    @Override
    public boolean isAlerted() {
        return alerted;
    }

    @Override
    public void alert() {
        alerted = true;
        waitStrategy.signalAllWhenBlocking();
    }

    @Override
    public void clearAlert() {
        alerted = false;
    }

    @Override
    public void checkAlert() throws AlertException {
        if (alerted) {
            throw AlertException.INSTANCE;
        }
    }
}