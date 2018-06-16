/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import io.netty.util.Signal;
import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import javax.naming.Name;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class DefaultPromise<V> extends AbstractFuture<V> implements Promise<V> {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultPromise.class);
    private static final InternalLogger rejectedExecutionLogger =
            InternalLoggerFactory.getInstance(DefaultPromise.class.getName() + ".rejectedExecution");
    private static final int MAX_LISTENER_STACK_DEPTH = Math.min(8,
            SystemPropertyUtil.getInt("io.netty.defaultPromise.maxListenerStackDepth", 8));
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultPromise, Object> SLOT_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(DefaultPromise.class, Object.class, "slot");
    private static final Signal SUCCESS = Signal.valueOf(DefaultPromise.class, "SUCCESS");
    private static final Signal UNCANCELLABLE = Signal.valueOf(DefaultPromise.class, "UNCANCELLABLE");
    private static final CauseHolder CANCELLATION_CAUSE_HOLDER = new CauseHolder(ThrowableUtil.unknownStackTrace(
            new CancellationException(), DefaultPromise.class, "cancel(...)"));

    /**
     * This can be many things:
     * <ol>
     *   <li>{@code null} -  No result, no listeners, cancellable.</li>
     *   <li>{@link SingleCancellableListener} - No result, one listener, cancellable</li>
     *   <li>{@link MultipleListeners} - No result, multiple listeners</li>
     *   <li>{@link CauseHolder} - Failure, no listeners</li>
     *   <li>{@link #NOTHING} - A result which is null</li>
     *   <li>Anything else - the result.</li>
     * </ol>
     */
    private volatile Object slot;
    private final EventExecutor executor;

    private static final Object NOTHING = new Object();
    private static Object nonNullResult(Object res) {
        return res != null ? res : NOTHING;
    }

    private static final class SingleCancellableListener {
        final GenericFutureListener<?> listener;

        SingleCancellableListener(GenericFutureListener<?> listener) {
            this.listener = listener;
        }
    }

    private static final class MultipleListeners {
        final List<? extends GenericFutureListener<?>> listeners;
        final boolean uncancellable;

        MultipleListeners(List<? extends GenericFutureListener<?>> listeners, boolean uncancellable) {
            this.listeners = listeners;
            this.uncancellable = uncancellable;
        }
    }

    /**
     * Creates a new instance.
     *
     * It is preferable to use {@link EventExecutor#newPromise()} to create a new promise
     *
     * @param executor
     *        the {@link EventExecutor} which is used to notify the promise once it is complete.
     *        It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     *        The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     *        depth exceeds a threshold.
     *
     */
    public DefaultPromise(EventExecutor executor) {
        this.executor = checkNotNull(executor, "executor");
    }

    /**
     * See {@link #executor()} for expectations of the executor.
     */
    protected DefaultPromise() {
        // only for subclasses
        executor = null;
    }

    @Override
    public Promise<V> setSuccess(final V result) {
        if (trySuccess(result)) {
            return this;
        }
        throw new IllegalStateException("complete already: " + this);
    }

    @Override
    public boolean trySuccess(V result) {
        final Object res = nonNullResult(result);

        while (true) {
            final Object s = SLOT_UPDATER.get(this);
            if (s == null) {
                if (SLOT_UPDATER.compareAndSet(this, s, res)) {
                    return true;
                }
            } else if (s instanceof SingleCancellableListener) {
                if (SLOT_UPDATER.compareAndSet(this, s, res)) {
                    notifyListener0(this, ((SingleCancellableListener) s).listener);
                    return true;
                }
            } else if (s instanceof MultipleListeners) {
                if (SLOT_UPDATER.compareAndSet(this, s, res)) {
                    for (GenericFutureListener<?> listener  : ((MultipleListeners) s).listeners) {
                        notifyListener0(this, listener);
                    }
                    return true;
                }
            } else {
                // CauseHolder, NOTHING, or an actual result.
                return false;
            }
        }
    }

    @Override
    public Promise<V> setFailure(Throwable cause) {
        if (tryFailure(cause)) {
            return this;
        }
        throw new IllegalStateException("complete already: " + this, cause);
    }

    @Override
    public boolean tryFailure(final Throwable cause) {
        if (cause == null) {
            throw new NullPointerException();
        }
        CauseHolder holder = new CauseHolder(cause);

        while (true) {
            final Object s = SLOT_UPDATER.get(this);
            if (s == null) {
                if (SLOT_UPDATER.compareAndSet(this, s, holder)) {
                    return true;
                }
            } else if (s instanceof SingleCancellableListener) {
                if (SLOT_UPDATER.compareAndSet(this, s, holder)) {
                    notifyListener0(this, ((SingleCancellableListener) s).listener);
                    return true;
                }
            } else if (s instanceof MultipleListeners) {
                 if (SLOT_UPDATER.compareAndSet(this, s, holder)) {
                    for (GenericFutureListener<?> listener  : ((MultipleListeners) s).listeners) {
                        notifyListener0(this, listener);
                    }
                    return true;
                }
            } else {
                return false;
            }
        }
    }

    @Override
    public boolean setUncancellable() {
        while (true) {
            final Object s = SLOT_UPDATER.get(this);
            if (s == null) {
                final MultipleListeners listeners = new MultipleListeners(null, true);
                if (SLOT_UPDATER.compareAndSet(this, s, listeners)) {
                    return true;
                }
            } else if (s instanceof SingleCancellableListener) {
                final MultipleListeners listeners =
                        new MultipleListeners(
                                Collections.singletonList(((SingleCancellableListener) s).listener), true);
                if (SLOT_UPDATER.compareAndSet(this, s, listeners)) {
                    return true;
                }
            } else if (s instanceof MultipleListeners) {
                final MultipleListeners oldListeners = (MultipleListeners) s;
                if (oldListeners.uncancellable) {
                    return true;
                }
                final MultipleListeners newListeners =
                        new MultipleListeners(oldListeners.listeners, oldListeners.uncancellable);
                if (SLOT_UPDATER.compareAndSet(this, s, newListeners)) {
                    return true;
                }
            } else {
                return false;
            }
        }
    }

    @Override
    public boolean isSuccess() {
        final Object s = SLOT_UPDATER.get(this);
        if (s == null) {
            return false;
        }
        if (s instanceof CauseHolder) {
            return false;
        }
        if (s instanceof SingleCancellableListener || s instanceof MultipleListeners) {
            return false;
        }

        return true;
    }

    @Override
    public boolean isCancellable() {
        final Object s = this.slot;
        if (s == null) {
            return true;
        }
        if (s instanceof SingleCancellableListener) {
            return true;
        }
        if (s instanceof MultipleListeners && !((MultipleListeners) s).uncancellable) {
            return true;
        }
        return false;
    }

    @Override
    public Throwable cause() {
        final Object s = SLOT_UPDATER.get(this);
        if (s instanceof CauseHolder) {
            return ((CauseHolder) s).cause;
        }
        return null;
    }

    @Override
    public Promise<V> addListener(GenericFutureListener<? extends Future<? super V>> listener) {
        checkNotNull(listener, "listener");
        return addListenersInternal(Collections.<GenericFutureListener<? extends Future<? super V>>>singletonList(listener));
    }

    private Promise<V> addListenersInternal(final List<GenericFutureListener<? extends Future<? super V>>> listeners) {
        while (true) {
            final Object s = SLOT_UPDATER.get(this);
            if (s == null && listeners.size() == 1) {
                if (SLOT_UPDATER.compareAndSet(this, s, new SingleCancellableListener(listeners.get(0)))) {
                    return this;
                }
            } else if (s instanceof SingleCancellableListener) {
                final SingleCancellableListener oldListener = (SingleCancellableListener) s;
                final List<GenericFutureListener<?>> array = new ArrayList<GenericFutureListener<?>>(1 + listeners.size());
                array.add(oldListener.listener);
                array.addAll(listeners);
                final MultipleListeners newListeners = new MultipleListeners(array, false);
                if (SLOT_UPDATER.compareAndSet(this, s, newListeners)) {
                    return this;
                }
            } else if (s instanceof MultipleListeners) {
                final MultipleListeners oldListeners = (MultipleListeners) s;
                final List<GenericFutureListener<?>> array =
                    new ArrayList<GenericFutureListener<?>>(oldListeners.listeners.size() + listeners.size());
                array.addAll(oldListeners.listeners);
                array.addAll(listeners);
                final MultipleListeners newListeners = new MultipleListeners(array, oldListeners.uncancellable);
                if (SLOT_UPDATER.compareAndSet(this, s, newListeners)) {
                    return this;
                }
            } else {
                notifyListeners(this, listener);
                return this;
            }
        }
    }

    @Override
    public Promise<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners) {
        checkNotNull(listeners, "listeners");
        for (int i = 0; i < listeners.length; i++) {
            checkNotNull(listeners[i], "listener");
        }
        return addListenersInternal(Arrays.asList(listeners));
    }

    @Override
    public Promise<V> removeListener(final GenericFutureListener<? extends Future<? super V>> listener) {
        checkNotNull(listener, "listener");

        synchronized (this) {
            removeListener0(listener);
        }

        return this;
    }

    @Override
    public Promise<V> removeListeners(final GenericFutureListener<? extends Future<? super V>>... listeners) {
        checkNotNull(listeners, "listeners");

        synchronized (this) {
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                if (listener == null) {
                    break;
                }
                removeListener0(listener);
            }
        }

        return this;
    }

    @Override
    public Promise<V> await() throws InterruptedException {
        if (isDone()) {
            return this;
        }

        if (Thread.interrupted()) {
            throw new InterruptedException(toString());
        }
        final class LatchListener extends CountDownLatch implements GenericFutureListener<Future<? super V>> {
            LatchListener() {
                super(1);
            }

            @Override
            public void operationComplete(Future<? super V> future) throws Exception {
                countDown();
            }
        }
        final LatchListener latch = new LatchListener();
        addListener(latch);
        latch.await();

        return this;
    }

    @Override
    public Promise<V> awaitUninterruptibly() {
        boolean interrupted = false;
        while (true) {
            try {
                return await();
            } catch (InterruptedException e) {
                interrupted = true;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Override
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return await0(unit.toNanos(timeout), true);
    }

    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
        return await0(MILLISECONDS.toNanos(timeoutMillis), true);
    }

    @Override
    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        try {
            return await0(unit.toNanos(timeout), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    @Override
    public boolean awaitUninterruptibly(long timeoutMillis) {
        try {
            return await0(MILLISECONDS.toNanos(timeoutMillis), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public V getNow() {
        Object result = this.slot;
        if (result instanceof CauseHolder || result == SUCCESS) {
            return null;
        }
        return (V) result;
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (RESULT_UPDATER.compareAndSet(this, null, CANCELLATION_CAUSE_HOLDER)) {
            checkNotifyWaiters();
            notifyListeners();
            return true;
        }
        return false;
    }

    @Override
    public boolean isCancelled() {
        return isCancelled0(result);
    }

    @Override
    public boolean isDone() {
        return isDone0(result);
    }

    @Override
    public Promise<V> sync() throws InterruptedException {
        await();
        rethrowIfFailed();
        return this;
    }

    @Override
    public Promise<V> syncUninterruptibly() {
        awaitUninterruptibly();
        rethrowIfFailed();
        return this;
    }

    @Override
    public String toString() {
        return toStringBuilder().toString();
    }

    protected StringBuilder toStringBuilder() {
        StringBuilder buf = new StringBuilder(64)
                .append(StringUtil.simpleClassName(this))
                .append('@')
                .append(Integer.toHexString(hashCode()));

        Object result = this.result;
        if (result == SUCCESS) {
            buf.append("(success)");
        } else if (result == UNCANCELLABLE) {
            buf.append("(uncancellable)");
        } else if (result instanceof CauseHolder) {
            buf.append("(failure: ")
                    .append(((CauseHolder) result).cause)
                    .append(')');
        } else if (result != null) {
            buf.append("(success: ")
                    .append(result)
                    .append(')');
        } else {
            buf.append("(incomplete)");
        }

        return buf;
    }

    /**
     * Get the executor used to notify listeners when this promise is complete.
     * <p>
     * It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     * The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     * depth exceeds a threshold.
     * @return The executor used to notify listeners when this promise is complete.
     */
    protected EventExecutor executor() {
        return executor;
    }

    protected void checkDeadLock() {
        EventExecutor e = executor();
        if (e != null && e.inEventLoop()) {
            throw new BlockingOperationException(toString());
        }
    }

    /**
     * Notify a listener that a future has completed.
     * <p>
     * This method has a fixed depth of {@link #MAX_LISTENER_STACK_DEPTH} that will limit recursion to prevent
     * {@link StackOverflowError} and will stop notifying listeners added after this threshold is exceeded.
     * @param eventExecutor the executor to use to notify the listener {@code listener}.
     * @param future the future that is complete.
     * @param listener the listener to notify.
     */
    protected static void notifyListener(
            EventExecutor eventExecutor, final Future<?> future, final GenericFutureListener<?> listener) {
        checkNotNull(eventExecutor, "eventExecutor");
        checkNotNull(future, "future");
        checkNotNull(listener, "listener");
        notifyListenerWithStackOverFlowProtection(eventExecutor, future, listener);
    }

    private void notifyListeners() {
        EventExecutor executor = executor();
        if (executor.inEventLoop()) {
            final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
            final int stackDepth = threadLocals.futureListenerStackDepth();
            if (stackDepth < MAX_LISTENER_STACK_DEPTH) {
                threadLocals.setFutureListenerStackDepth(stackDepth + 1);
                try {
                    notifyListenersNow();
                } finally {
                    threadLocals.setFutureListenerStackDepth(stackDepth);
                }
                return;
            }
        }

        safeExecute(executor, new Runnable() {
            @Override
            public void run() {
                notifyListenersNow();
            }
        });
    }

    /**
     * The logic in this method should be identical to {@link #notifyListeners()} but
     * cannot share code because the listener(s) cannot be cached for an instance of {@link DefaultPromise} since the
     * listener(s) may be changed and is protected by a synchronized operation.
     */
    private static void notifyListenerWithStackOverFlowProtection(final EventExecutor executor,
                                                                  final Future<?> future,
                                                                  final GenericFutureListener<?> listener) {
        if (executor.inEventLoop()) {
            final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
            final int stackDepth = threadLocals.futureListenerStackDepth();
            if (stackDepth < MAX_LISTENER_STACK_DEPTH) {
                threadLocals.setFutureListenerStackDepth(stackDepth + 1);
                try {
                    notifyListener0(future, listener);
                } finally {
                    threadLocals.setFutureListenerStackDepth(stackDepth);
                }
                return;
            }
        }

        safeExecute(executor, new Runnable() {
            @Override
            public void run() {
                notifyListener0(future, listener);
            }
        });
    }

    private void notifyListenersNow() {
        Object listeners;
        synchronized (this) {
            // Only proceed if there are listeners to notify and we are not already notifying listeners.
            if (notifyingListeners || this.listeners == null) {
                return;
            }
            notifyingListeners = true;
            listeners = this.listeners;
            this.listeners = null;
        }
        for (;;) {
            if (listeners instanceof DefaultFutureListeners) {
                notifyListeners0((DefaultFutureListeners) listeners);
            } else {
                notifyListener0(this, (GenericFutureListener<?>) listeners);
            }
            synchronized (this) {
                if (this.listeners == null) {
                    // Nothing can throw from within this method, so setting notifyingListeners back to false does not
                    // need to be in a finally block.
                    notifyingListeners = false;
                    return;
                }
                listeners = this.listeners;
                this.listeners = null;
            }
        }
    }

    private void notifyListeners0(DefaultFutureListeners listeners) {
        GenericFutureListener<?>[] a = listeners.listeners();
        int size = listeners.size();
        for (int i = 0; i < size; i ++) {
            notifyListener0(this, a[i]);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyListener0(Future future, GenericFutureListener l) {
        try {
            l.operationComplete(future);
        } catch (Throwable t) {
            logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationComplete()", t);
        }
    }

    private void addListener0(GenericFutureListener<? extends Future<? super V>> listener) {
        if (listeners == null) {
            listeners = listener;
        } else if (listeners instanceof DefaultFutureListeners) {
            ((DefaultFutureListeners) listeners).add(listener);
        } else {
            listeners = new DefaultFutureListeners((GenericFutureListener<?>) listeners, listener);
        }
    }

    private void removeListener0(GenericFutureListener<? extends Future<? super V>> listener) {
        if (listeners instanceof DefaultFutureListeners) {
            ((DefaultFutureListeners) listeners).remove(listener);
        } else if (listeners == listener) {
            listeners = null;
        }
    }

    private boolean setSuccess0(V result) {
        return setValue0(result == null ? SUCCESS : result);
    }

    private boolean setFailure0(Throwable cause) {
        return setValue0(new CauseHolder(checkNotNull(cause, "cause")));
    }



    private void rethrowIfFailed() {
        Throwable cause = cause();
        if (cause == null) {
            return;
        }

        PlatformDependent.throwException(cause);
    }

    private boolean await0(long timeoutNanos, boolean interruptable) throws InterruptedException {
        if (isDone()) {
            return true;
        }

        if (interruptable && Thread.interrupted()) {
            throw new InterruptedException(toString());
        }
        final class LatchListener extends CountDownLatch implements GenericFutureListener<Future<? super V>> {
            LatchListener() {
                super(1);
            }

            @Override
            public void operationComplete(Future<? super V> future) throws Exception {
                countDown();
            }
        }
        final LatchListener latch = new LatchListener();
        addListener(latch);

        long start = System.nanoTime();
        while (true) {
            try {
                return latch.await(timeoutNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                if (interruptable) {
                    return false;
                }
                long end = System.nanoTime();
                timeoutNanos -= (end - start);
                start = end;
            }
        }
    }

    /**
     * Notify all progressive listeners.
     * <p>
     * No attempt is made to ensure notification order if multiple calls are made to this method before
     * the original invocation completes.
     * <p>
     * This will do an iteration over all listeners to get all of type {@link GenericProgressiveFutureListener}s.
     * @param progress the new progress.
     * @param total the total progress.
     */
    @SuppressWarnings("unchecked")
    void notifyProgressiveListeners(final long progress, final long total) {
        final Object listeners = progressiveListeners();
        if (listeners == null) {
            return;
        }

        final ProgressiveFuture<V> self = (ProgressiveFuture<V>) this;

        EventExecutor executor = executor();
        if (executor.inEventLoop()) {
            if (listeners instanceof GenericProgressiveFutureListener[]) {
                notifyProgressiveListeners0(
                        self, (GenericProgressiveFutureListener<?>[]) listeners, progress, total);
            } else {
                notifyProgressiveListener0(
                        self, (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners, progress, total);
            }
        } else {
            if (listeners instanceof GenericProgressiveFutureListener[]) {
                final GenericProgressiveFutureListener<?>[] array =
                        (GenericProgressiveFutureListener<?>[]) listeners;
                safeExecute(executor, new Runnable() {
                    @Override
                    public void run() {
                        notifyProgressiveListeners0(self, array, progress, total);
                    }
                });
            } else {
                final GenericProgressiveFutureListener<ProgressiveFuture<V>> l =
                        (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners;
                safeExecute(executor, new Runnable() {
                    @Override
                    public void run() {
                        notifyProgressiveListener0(self, l, progress, total);
                    }
                });
            }
        }
    }

    /**
     * Returns a {@link GenericProgressiveFutureListener}, an array of {@link GenericProgressiveFutureListener}, or
     * {@code null}.
     */
    private synchronized Object progressiveListeners() {
        Object listeners = this.listeners;
        if (listeners == null) {
            // No listeners added
            return null;
        }

        if (listeners instanceof DefaultFutureListeners) {
            // Copy DefaultFutureListeners into an array of listeners.
            DefaultFutureListeners dfl = (DefaultFutureListeners) listeners;
            int progressiveSize = dfl.progressiveSize();
            switch (progressiveSize) {
                case 0:
                    return null;
                case 1:
                    for (GenericFutureListener<?> l: dfl.listeners()) {
                        if (l instanceof GenericProgressiveFutureListener) {
                            return l;
                        }
                    }
                    return null;
            }

            GenericFutureListener<?>[] array = dfl.listeners();
            GenericProgressiveFutureListener<?>[] copy = new GenericProgressiveFutureListener[progressiveSize];
            for (int i = 0, j = 0; j < progressiveSize; i ++) {
                GenericFutureListener<?> l = array[i];
                if (l instanceof GenericProgressiveFutureListener) {
                    copy[j ++] = (GenericProgressiveFutureListener<?>) l;
                }
            }

            return copy;
        } else if (listeners instanceof GenericProgressiveFutureListener) {
            return listeners;
        } else {
            // Only one listener was added and it's not a progressive listener.
            return null;
        }
    }

    private static void notifyProgressiveListeners0(
            ProgressiveFuture<?> future, GenericProgressiveFutureListener<?>[] listeners, long progress, long total) {
        for (GenericProgressiveFutureListener<?> l: listeners) {
            if (l == null) {
                break;
            }
            notifyProgressiveListener0(future, l, progress, total);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyProgressiveListener0(
            ProgressiveFuture future, GenericProgressiveFutureListener l, long progress, long total) {
        try {
            l.operationProgressed(future, progress, total);
        } catch (Throwable t) {
            logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationProgressed()", t);
        }
    }

    private static boolean isCancelled0(Object result) {
        return result instanceof CauseHolder && ((CauseHolder) result).cause instanceof CancellationException;
    }

    private static boolean isDone0(Object result) {
        return result != null && result != UNCANCELLABLE;
    }

    private static final class CauseHolder {
        final Throwable cause;
        CauseHolder(Throwable cause) {
            this.cause = cause;
        }
    }

    private static void safeExecute(EventExecutor executor, Runnable task) {
        try {
            executor.execute(task);
        } catch (Throwable t) {
            rejectedExecutionLogger.error("Failed to submit a listener notification task. Event loop shut down?", t);
        }
    }
}
