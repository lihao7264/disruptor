/*
 * Copyright 2012 LMAX Ltd.
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

import static java.util.Arrays.copyOf;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Provides static methods for managing a {@link SequenceGroup} object.
 */
class SequenceGroups {
    /**
     * 线程安全的 sequencesToAdd 加到门禁集合
     *
     * @param holder         生产者引用
     * @param updater        存放生产者引用的门禁集合（生产者序号肯定不能超过该门禁集合的最小值）
     * @param cursor         生产者生产的序号
     * @param sequencesToAdd
     * @param <T>
     */
    static <T> void addSequences(final T holder, final AtomicReferenceFieldUpdater<T, Sequence[]> updater, final Cursored cursor, final Sequence... sequencesToAdd) {
        long cursorSequence;
        Sequence[] updatedSequences;
        Sequence[] currentSequences;
        // cas 自旋
        do {
            currentSequences = updater.get(holder);
            /**
             *  copyOf(...) 方法：java.util.Arrays.copyOf(...) 方法，用于将 currentSequences 复制一份
             *  使用了 写时复制 思想：读写分离，真正写时用自旋
             */
            updatedSequences = copyOf(currentSequences, currentSequences.length + sequencesToAdd.length);
            /**
             * 此处的 cursor 即为生产者 ProducerSequencer.getCursor() 的 游标
             * 通过 游标，获取 当前生产者生产的序号
             */
            cursorSequence = cursor.getCursor();
            int index = currentSequences.length;
            for (Sequence sequence : sequencesToAdd) {

                sequence.set(cursorSequence);
                updatedSequences[index++] = sequence;
            }
        } while (!updater.compareAndSet(holder, currentSequences, updatedSequences));
        // 此处的 while 会死循环 CAS 操做直到更新成功
        cursorSequence = cursor.getCursor();
        // 初始化消费起点位生产点位
        for (Sequence sequence : sequencesToAdd) {
            sequence.set(cursorSequence);
        }
    }

    /**
     * 线程安全的 把sequence从门禁集合中移除
     * @param holder           生产者引用
     * @param sequenceUpdater  存放生产者引用的门禁集合（生产者序号肯定不能超过该门禁集合的最小值）
     * @param sequence
     */
    static <T> boolean removeSequence(final T holder, final AtomicReferenceFieldUpdater<T, Sequence[]> sequenceUpdater, final Sequence sequence) {
        int numToRemove;
        Sequence[] oldSequences;
        Sequence[] newSequences;

        do {
            // 生产者门禁集合
            oldSequences = sequenceUpdater.get(holder);
            // countMatching 找到 sequence序号匹配的个数
            numToRemove = countMatching(oldSequences, sequence);
            // 不存在，则返回
            if (0 == numToRemove) {
                break;
            }
            // 否则移除
            final int oldSize = oldSequences.length;
            newSequences = new Sequence[oldSize - numToRemove];

            for (int i = 0, pos = 0; i < oldSize; i++) {
                final Sequence testSequence = oldSequences[i];
                if (sequence != testSequence) {
                    newSequences[pos++] = testSequence;
                }
            }
        } while (!sequenceUpdater.compareAndSet(holder, oldSequences, newSequences));

        return numToRemove != 0;
    }

    private static <T> int countMatching(T[] values, final T toMatch) {
        int numToRemove = 0;
        for (T value : values) {
            if (value == toMatch) // Specifically uses identity
            {
                numToRemove++;
            }
        }
        return numToRemove;
    }
}
