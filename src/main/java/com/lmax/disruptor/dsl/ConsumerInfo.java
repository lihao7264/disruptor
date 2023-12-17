package com.lmax.disruptor.dsl;

import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;

import java.util.concurrent.Executor;

interface ConsumerInfo
{
    /**
     * ConsumerInfo 和 Sequence  是 一对多 关系
     * 获取消费者拥有的所有的序号，一个消费者可能有多个Sequence，消费者的消费进度由最小的Sequence决定。
     * 这里返回数组类型
     */
    Sequence[] getSequences();

    /**
     * 获取当前消费者持有的  序号屏障。
     */
    SequenceBarrier getBarrier();

    /**
     * 当前消费者是否是消费链末端, 如果是的话，表示 没有后继消费者。
     * 如果是末端的消费者，那么它就是生产者关注的消费者
     */
    boolean isEndOfChain();

    /**
     * 启动消费者。
     * 主要是为每一个 EventProcessor 创建线程，启动事件监听。
     */
    void start(Executor executor);
    /**
     * 通知消费者处理完当前事件之后，停止下来
     * 类似线程中断或任务的取消操作
     */
    void halt();
    /**
     * 当新增了后继消费者的时候，标记为不是消费者链末端的消费者
     * 生产者就不再需要对 此消费者 保持关注
     */
    void markAsUsedInBarrier();
    /**
     * 消费者当前是否正在运行
     */
    boolean isRunning();
}
