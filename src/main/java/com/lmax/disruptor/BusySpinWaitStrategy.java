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

/**
 * Busy Spin strategy that uses a busy spin loop for {@link com.lmax.disruptor.EventProcessor}s waiting on a barrier.
 * <p>
 * This strategy will use CPU resource to avoid syscalls which can introduce latency jitter（延迟抖动）.  It is best
 * used when threads can be bound to specific CPU cores.
 *
 * 适合于极致低延迟的场景    redis 的服务线程 ， nginx  work线程 ， netty io 线程
 */
public final class BusySpinWaitStrategy implements WaitStrategy
{
    @Override
    public long waitFor(
        final long sequence, Sequence cursor, final Sequence dependentSequence, final SequenceBarrier barrier)
        throws AlertException, InterruptedException
    {
        long availableSequence;


        // Intel P4处理器在循环等待时会执行得非常快，这将导致处理器消耗大量的电力
        // 内存顺序冲突（ Memory order violation）, 退出循环的时候，CPU流水线被清空（CPU pipeline flush）

        while ((availableSequence = dependentSequence.get()) < sequence)
        {
            barrier.checkAlert();
            // 代码层面是一个空方法
            // 代码层面，是一个空自旋
            // jvm 看心情
            ThreadHints.onSpinWait();
        }

        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking()
    {
    }
}
