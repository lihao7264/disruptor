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

import java.util.concurrent.atomic.AtomicInteger;


/**
 * Convenience class for handling the batching semantics of consuming entries from a {@link RingBuffer}
 * and delegating the available events to an {@link EventHandler}.
 * <p>
 * If the {@link EventHandler} also implements {@link LifecycleAware} it will be notified just after the thread
 * is started and just before the thread is shutdown.
 *
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public final class BatchEventProcessor<T> implements EventProcessor {
    private static final int IDLE = 0;
    private static final int HALTED = IDLE + 1;
    private static final int RUNNING = HALTED + 1;

    private final AtomicInteger running = new AtomicInteger(IDLE);
    private ExceptionHandler<? super T> exceptionHandler;

    // 环形队列
    private final DataProvider<T> dataProvider;
    private final SequenceBarrier sequenceBarrier;
    // 业务处理器
    private final EventHandler<? super T> eventHandler;
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    private final TimeoutHandler timeoutHandler;
    private final BatchStartAware batchStartAware;

    /**
     * Construct a {@link EventProcessor} that will automatically track the progress by updating its sequence when
     * the {@link EventHandler#onEvent(Object, long, boolean)} method returns.
     *
     * @param dataProvider    to which events are published.
     * @param sequenceBarrier on which it is waiting.
     * @param eventHandler    is the delegate to which events are dispatched.
     */
    public BatchEventProcessor(final DataProvider<T> dataProvider, final SequenceBarrier sequenceBarrier, final EventHandler<? super T> eventHandler) {
        this.dataProvider = dataProvider;
        this.sequenceBarrier = sequenceBarrier;
        this.eventHandler = eventHandler;

        if (eventHandler instanceof SequenceReportingEventHandler) {
            ((SequenceReportingEventHandler<?>) eventHandler).setSequenceCallback(sequence);
        }

        batchStartAware = (eventHandler instanceof BatchStartAware) ? (BatchStartAware) eventHandler : null;
        timeoutHandler = (eventHandler instanceof TimeoutHandler) ? (TimeoutHandler) eventHandler : null;
    }

    @Override
    public Sequence getSequence() {
        return sequence;
    }

    @Override
    public void halt() {
        running.set(HALTED);
        sequenceBarrier.alert();
    }

    @Override
    public boolean isRunning() {
        return running.get() != IDLE;
    }

    /**
     * Set a new {@link ExceptionHandler} for handling exceptions propagated out of the {@link BatchEventProcessor}
     *
     * @param exceptionHandler to replace the existing exceptionHandler.
     */
    public void setExceptionHandler(final ExceptionHandler<? super T> exceptionHandler) {
        if (null == exceptionHandler) {
            throw new NullPointerException();
        }

        this.exceptionHandler = exceptionHandler;
    }

    /**
     * It is ok to have another thread rerun this method after a halt().
     *
     * @throws IllegalStateException if this object instance is already running in a thread
     */
    @Override
    public void run() {
        if (running.compareAndSet(IDLE, RUNNING)) {
            sequenceBarrier.clearAlert();

            notifyStart();
            try {
                if (running.get() == RUNNING) {
                    // 核心方法
                    processEvents();
                }
            } finally {
                notifyShutdown();
                running.set(IDLE);
            }
        } else {
            // This is a little bit of guess work.  The running state could of changed to HALTED by
            // this point.  However, Java does not have compareAndExchange which is the only way
            // to get it exactly correct.
            if (running.get() == RUNNING) {
                throw new IllegalStateException("Thread is already running");
            } else {
                earlyExit();
            }
        }
    }

    /**
     * 处理事件
     */
    private void processEvents() {
        T event = null;
        /**
         * nextSequence：消费者要消费的下一个序号
         * 【重要】每一个消费者都从0开始消费，各个消费者维护各自的sequence
         */
        long nextSequence = sequence.get() + 1L;
        while (true) {
            try {
                // 获取当前生产者的生产序号 或 可消费的序号
                final long availableSequence = sequenceBarrier.waitFor(nextSequence);
                if (batchStartAware != null) {
                    batchStartAware.onBatchStart(availableSequence - nextSequence + 1);
                }
                /**
                 * 如果消费者要消费的下一个序号小于生产者的当前生产序号，那消费者则进行消费
                 *  这里有一个亮点：消费者会一直循环消费直至到达当前生产者生产的序号
                 */
                while (nextSequence <= availableSequence) {
                    event = dataProvider.get(nextSequence);
                    eventHandler.onEvent(event, nextSequence, nextSequence == availableSequence);
                    nextSequence++;
                }
                /**
                 * 8: 消费完后设置当前消费者的消费进度
                 * 【1】如果当前消费者是执行链的最后一个消费者，其sequence则是生产者的gatingSequence，因为生产者是拿要生产的下一个sequence跟gatingSequence做比较的
                 * 【2】如果当前消费者不是执行器链的最后一个消费者，则其sequence作为后面消费者的dependentSequence
                 */
                sequence.set(availableSequence);
            } catch (final TimeoutException e) {
                notifyTimeout(sequence.get());
            } catch (final AlertException ex) {
                if (running.get() != RUNNING) {
                    break;
                }
            } catch (final Throwable ex) {
                handleEventException(ex, nextSequence, event);
                sequence.set(nextSequence);
                nextSequence++;
            }
        }
    }

    private void earlyExit() {
        notifyStart();
        notifyShutdown();
    }

    private void notifyTimeout(final long availableSequence) {
        try {
            if (timeoutHandler != null) {
                timeoutHandler.onTimeout(availableSequence);
            }
        } catch (Throwable e) {
            handleEventException(e, availableSequence, null);
        }
    }

    /**
     * Notifies the EventHandler when this processor is starting up
     */
    private void notifyStart() {
        if (eventHandler instanceof LifecycleAware) {
            try {
                ((LifecycleAware) eventHandler).onStart();
            } catch (final Throwable ex) {
                handleOnStartException(ex);
            }
        }
    }

    /**
     * Notifies the EventHandler immediately prior to this processor shutting down
     */
    private void notifyShutdown() {
        if (eventHandler instanceof LifecycleAware) {
            try {
                ((LifecycleAware) eventHandler).onShutdown();
            } catch (final Throwable ex) {
                handleOnShutdownException(ex);
            }
        }
    }

    /**
     * Delegate to {@link ExceptionHandler#handleEventException(Throwable, long, Object)} on the delegate or
     * the default {@link ExceptionHandler} if one has not been configured.
     */
    private void handleEventException(final Throwable ex, final long sequence, final T event) {
        getExceptionHandler().handleEventException(ex, sequence, event);
    }

    /**
     * Delegate to {@link ExceptionHandler#handleOnStartException(Throwable)} on the delegate or
     * the default {@link ExceptionHandler} if one has not been configured.
     */
    private void handleOnStartException(final Throwable ex) {
        getExceptionHandler().handleOnStartException(ex);
    }

    /**
     * Delegate to {@link ExceptionHandler#handleOnShutdownException(Throwable)} on the delegate or
     * the default {@link ExceptionHandler} if one has not been configured.
     */
    private void handleOnShutdownException(final Throwable ex) {
        getExceptionHandler().handleOnShutdownException(ex);
    }

    private ExceptionHandler<? super T> getExceptionHandler() {
        ExceptionHandler<? super T> handler = exceptionHandler;
        if (handler == null) {
            return ExceptionHandlers.defaultHandler();
        }
        return handler;
    }
}