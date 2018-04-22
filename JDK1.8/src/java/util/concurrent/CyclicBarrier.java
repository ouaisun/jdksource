/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A synchronization aid that allows a set of threads to all wait for
 * each other to reach a common barrier point.  CyclicBarriers are
 * useful in programs involving a fixed sized party of threads that
 * must occasionally wait for each other. The barrier is called
 * <em>cyclic</em> because it can be re-used after the waiting threads
 * are released.
 * <p>
 * <p>A {@code CyclicBarrier} supports an optional {@link Runnable} command
 * that is run once per barrier point, after the last thread in the party
 * arrives, but before any threads are released.
 * This <em>barrier action</em> is useful
 * for updating shared-state before any of the parties continue.
 * <p>
 * <p><b>Sample usage:</b> Here is an example of using a barrier in a
 * parallel decomposition design:
 * <p>
 * <pre> {@code
 * class Solver {
 *   final int N;
 *   final float[][] data;
 *   final CyclicBarrier barrier;
 *
 *   class Worker implements Runnable {
 *     int myRow;
 *     Worker(int row) { myRow = row; }
 *     public void run() {
 *       while (!done()) {
 *         processRow(myRow);
 *
 *         try {
 *           barrier.await();
 *         } catch (InterruptedException ex) {
 *           return;
 *         } catch (BrokenBarrierException ex) {
 *           return;
 *         }
 *       }
 *     }
 *   }
 *
 *   public Solver(float[][] matrix) {
 *     data = matrix;
 *     N = matrix.length;
 *     Runnable barrierAction =
 *       new Runnable() { public void run() { mergeRows(...); }};
 *     barrier = new CyclicBarrier(N, barrierAction);
 *
 *     List<Thread> threads = new ArrayList<Thread>(N);
 *     for (int i = 0; i < N; i++) {
 *       Thread thread = new Thread(new Worker(i));
 *       threads.add(thread);
 *       thread.start();
 *     }
 *
 *     // wait until done
 *     for (Thread thread : threads)
 *       thread.join();
 *   }
 * }}</pre>
 * <p>
 * Here, each worker thread processes a row of the matrix then waits at the
 * barrier until all rows have been processed. When all rows are processed
 * the supplied {@link Runnable} barrier action is executed and merges the
 * rows. If the merger
 * determines that a solution has been found then {@code done()} will return
 * {@code true} and each worker will terminate.
 * <p>
 * <p>If the barrier action does not rely on the parties being suspended when
 * it is executed, then any of the threads in the party could execute that
 * action when it is released. To facilitate this, each invocation of
 * {@link #await} returns the arrival index of that thread at the barrier.
 * You can then choose which thread should execute the barrier action, for
 * example:
 * <pre> {@code
 * if (barrier.await() == 0) {
 *   // log the completion of this iteration
 * }}</pre>
 * <p>
 * <p>The {@code CyclicBarrier} uses an all-or-none breakage model
 * for failed synchronization attempts: If a thread leaves a barrier
 * point prematurely because of interruption, failure, or timeout, all
 * other threads waiting at that barrier point will also leave
 * abnormally via {@link BrokenBarrierException} (or
 * {@link InterruptedException} if they too were interrupted at about
 * the same time).
 * <p>
 * <p>Memory consistency effects: Actions in a thread prior to calling
 * {@code await()}
 * <a href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * actions that are part of the barrier action, which in turn
 * <i>happen-before</i> actions following a successful return from the
 * corresponding {@code await()} in other threads.
 *
 * @author Doug Lea
 * @see CountDownLatch
 * @since 1.5
 *
 * http://www.cnblogs.com/leesf456/p/5392816.html
 */
public class CyclicBarrier {
    /**
     * Each use of the barrier is represented as a generation instance.
     * The generation changes whenever the barrier is tripped, or
     * is reset. There can be many generations associated with threads
     * using the barrier - due to the non-deterministic way the lock
     * may be allocated to waiting threads - but only one of these
     * can be active at a time (the one to which {@code count} applies)
     * and all the rest are either broken or tripped.
     * There need not be an active generation if there has been a break
     * but no subsequent reset.
     */
    private static class Generation {
        boolean broken = false;
    }

    /**
     * The lock for guarding barrier entry
     */
    // 可重入锁
    private final ReentrantLock lock = new ReentrantLock();
    /**
     * Condition to wait on until tripped
     */
    // 条件队列
    private final Condition trip = lock.newCondition();
    /**
     * The number of parties
     */
    // 参与的线程数量
    private final int parties;
    /* The command to run when tripped */
    // 由最后一个进入 barrier 的线程执行的操作
    private final Runnable barrierCommand;
    /**
     * The current generation
     */
    // 当前代
    private Generation generation = new Generation();

    /**
     * Number of parties still waiting. Counts down from parties to 0
     * on each generation.  It is reset to parties on each new
     * generation or when broken.
     */
    // 正在等待进入屏障的线程数量
    private int count;

    /**
     * Updates state on barrier trip and wakes up everyone.
     * Called only while holding lock.
     */
    private void nextGeneration() {
        // signal completion of last generation
        // 唤醒所有线程
        trip.signalAll();
        // set up next generation
        // 恢复正在等待进入屏障的线程数量
        count = parties;
        // 新生一代
        generation = new Generation();
    }

    /**
     * Sets current barrier generation as broken and wakes up everyone.
     * Called only while holding lock.
     */
    private void breakBarrier() {
        // 设置状态
        generation.broken = true;
        // 恢复正在等待进入屏障的线程数量
        count = parties;
        // 唤醒所有线程
        trip.signalAll();
    }

    /**
     * Main barrier code, covering the various policies.
     */
    private int dowait(boolean timed, long nanos) throws InterruptedException, BrokenBarrierException, TimeoutException {
        // 保存当前锁
        final ReentrantLock lock = this.lock;
        // 锁定
        lock.lock();
        try {
            // 保存当前代
            final Generation g = generation;

            // 屏障被破坏，抛出异常
            if (g.broken)
                throw new BrokenBarrierException();

            // 线程被中断
            if (Thread.interrupted()) {
                // 损坏当前屏障，并且唤醒所有的线程，只有拥有锁的时候才会调用
                breakBarrier();
                // 抛出异常
                throw new InterruptedException();
            }
            // 减少正在等待进入屏障的线程数量
            int index = --count;
            if (index == 0) {  // tripped // 正在等待进入屏障的线程数量为0，所有线程都已经进入
                // 运行的动作标识
                boolean ranAction = false;
                try {
                    // 保存运行动作
                    final Runnable command = barrierCommand;
                    if (command != null) // 动作不为空
                        // 运行
                        command.run();
                    // 设置ranAction状态
                    ranAction = true;
                    // 进入下一代
                    nextGeneration();
                    return 0;
                } finally {
                    if (!ranAction)// 没有运行的动作
                        // 损坏当前屏障
                        breakBarrier();
                }
            }

            // loop until tripped, broken, interrupted, or timed out
            // 无限循环
            for (; ; ) {
                try {
                    if (!timed)// 没有设置等待时间
                        // 等待
                        trip.await();
                    else if (nanos > 0L) // 设置了等待时间，并且等待时间大于0
                        // 等待指定时长
                        nanos = trip.awaitNanos(nanos);
                } catch (InterruptedException ie) {
                    if (g == generation && !g.broken) { // 等于当前代并且屏障没有被损坏
                        // 损坏当前屏障
                        breakBarrier();
                        // 抛出异常
                        throw ie;
                    } else {// 不等于当前带后者是屏障被损坏
                        // We're about to finish waiting even if we had not
                        // been interrupted, so this interrupt is deemed to
                        // "belong" to subsequent execution.
                        // 中断当前线程
                        Thread.currentThread().interrupt();
                    }
                }

                if (g.broken)// 屏障被损坏，抛出异常
                    throw new BrokenBarrierException();

                if (g != generation)// 不等于当前代
                    // 返回索引
                    return index;

                if (timed && nanos <= 0L) { // 设置了等待时间，并且等待时间小于0
                    // 损坏屏障
                    breakBarrier();
                    throw new TimeoutException();
                }
            }
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    /**
     * Creates a new {@code CyclicBarrier} that will trip when the
     * given number of parties (threads) are waiting upon it, and which
     * will execute the given barrier action when the barrier is tripped,
     * performed by the last thread entering the barrier.
     *
     * @param parties       the number of threads that must invoke {@link #await}
     *                      before the barrier is tripped
     * @param barrierAction the command to execute when the barrier is
     *                      tripped, or {@code null} if there is no action
     * @throws IllegalArgumentException if {@code parties} is less than 1
     */
    public CyclicBarrier(int parties, Runnable barrierAction) {
        // 参与的线程数量小于等于0，抛出异常
        if (parties <= 0) throw new IllegalArgumentException();
        // 设置parties
        this.parties = parties;
        // 设置count
        this.count = parties;
        // 设置barrierCommand
        this.barrierCommand = barrierAction;
    }

    /**
     * Creates a new {@code CyclicBarrier} that will trip when the
     * given number of parties (threads) are waiting upon it, and
     * does not perform a predefined action when the barrier is tripped.
     *
     * @param parties the number of threads that must invoke {@link #await}
     *                before the barrier is tripped
     * @throws IllegalArgumentException if {@code parties} is less than 1
     */
    public CyclicBarrier(int parties) {
        this(parties, null);
    }

    /**
     * Returns the number of parties required to trip this barrier.
     *
     * @return the number of parties required to trip this barrier
     */
    public int getParties() {
        return parties;
    }

    /**
     * Waits until all {@linkplain #getParties parties} have invoked
     * {@code await} on this barrier.
     * <p>
     * <p>If the current thread is not the last to arrive then it is
     * disabled for thread scheduling purposes and lies dormant until
     * one of the following things happens:
     * <ul>
     * <li>The last thread arrives; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * one of the other waiting threads; or
     * <li>Some other thread times out while waiting for barrier; or
     * <li>Some other thread invokes {@link #reset} on this barrier.
     * </ul>
     * <p>
     * <p>If the current thread:
     * <ul>
     * <li>has its interrupted status set on entry to this method; or
     * <li>is {@linkplain Thread#interrupt interrupted} while waiting
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     * <p>
     * <p>If the barrier is {@link #reset} while any thread is waiting,
     * or if the barrier {@linkplain #isBroken is broken} when
     * {@code await} is invoked, or while any thread is waiting, then
     * {@link BrokenBarrierException} is thrown.
     * <p>
     * <p>If any thread is {@linkplain Thread#interrupt interrupted} while waiting,
     * then all other waiting threads will throw
     * {@link BrokenBarrierException} and the barrier is placed in the broken
     * state.
     * <p>
     * <p>If the current thread is the last thread to arrive, and a
     * non-null barrier action was supplied in the constructor, then the
     * current thread runs the action before allowing the other threads to
     * continue.
     * If an exception occurs during the barrier action then that exception
     * will be propagated in the current thread and the barrier is placed in
     * the broken state.
     *
     * @return the arrival index of the current thread, where index
     * {@code getParties() - 1} indicates the first
     * to arrive and zero indicates the last to arrive
     * @throws InterruptedException   if the current thread was interrupted
     *                                while waiting
     * @throws BrokenBarrierException if <em>another</em> thread was
     *                                interrupted or timed out while the current thread was
     *                                waiting, or the barrier was reset, or the barrier was
     *                                broken when {@code await} was called, or the barrier
     *                                action (if present) failed due to an exception
     */
    public int await() throws InterruptedException, BrokenBarrierException {
        try {
            return dowait(false, 0L);
        } catch (TimeoutException toe) {
            throw new Error(toe); // cannot happen
        }
    }

    /**
     * Waits until all {@linkplain #getParties parties} have invoked
     * {@code await} on this barrier, or the specified waiting time elapses.
     * <p>
     * <p>If the current thread is not the last to arrive then it is
     * disabled for thread scheduling purposes and lies dormant until
     * one of the following things happens:
     * <ul>
     * <li>The last thread arrives; or
     * <li>The specified timeout elapses; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * one of the other waiting threads; or
     * <li>Some other thread times out while waiting for barrier; or
     * <li>Some other thread invokes {@link #reset} on this barrier.
     * </ul>
     * <p>
     * <p>If the current thread:
     * <ul>
     * <li>has its interrupted status set on entry to this method; or
     * <li>is {@linkplain Thread#interrupt interrupted} while waiting
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     * <p>
     * <p>If the specified waiting time elapses then {@link TimeoutException}
     * is thrown. If the time is less than or equal to zero, the
     * method will not wait at all.
     * <p>
     * <p>If the barrier is {@link #reset} while any thread is waiting,
     * or if the barrier {@linkplain #isBroken is broken} when
     * {@code await} is invoked, or while any thread is waiting, then
     * {@link BrokenBarrierException} is thrown.
     * <p>
     * <p>If any thread is {@linkplain Thread#interrupt interrupted} while
     * waiting, then all other waiting threads will throw {@link
     * BrokenBarrierException} and the barrier is placed in the broken
     * state.
     * <p>
     * <p>If the current thread is the last thread to arrive, and a
     * non-null barrier action was supplied in the constructor, then the
     * current thread runs the action before allowing the other threads to
     * continue.
     * If an exception occurs during the barrier action then that exception
     * will be propagated in the current thread and the barrier is placed in
     * the broken state.
     *
     * @param timeout the time to wait for the barrier
     * @param unit    the time unit of the timeout parameter
     * @return the arrival index of the current thread, where index
     * {@code getParties() - 1} indicates the first
     * to arrive and zero indicates the last to arrive
     * @throws InterruptedException   if the current thread was interrupted
     *                                while waiting
     * @throws TimeoutException       if the specified timeout elapses.
     *                                In this case the barrier will be broken.
     * @throws BrokenBarrierException if <em>another</em> thread was
     *                                interrupted or timed out while the current thread was
     *                                waiting, or the barrier was reset, or the barrier was broken
     *                                when {@code await} was called, or the barrier action (if
     *                                present) failed due to an exception
     */
    public int await(long timeout, TimeUnit unit)
            throws InterruptedException,
            BrokenBarrierException,
            TimeoutException {
        return dowait(true, unit.toNanos(timeout));
    }

    /**
     * Queries if this barrier is in a broken state.
     *
     * @return {@code true} if one or more parties broke out of this
     * barrier due to interruption or timeout since
     * construction or the last reset, or a barrier action
     * failed due to an exception; {@code false} otherwise.
     */
    public boolean isBroken() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return generation.broken;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resets the barrier to its initial state.  If any parties are
     * currently waiting at the barrier, they will return with a
     * {@link BrokenBarrierException}. Note that resets <em>after</em>
     * a breakage has occurred for other reasons can be complicated to
     * carry out; threads need to re-synchronize in some other way,
     * and choose one to perform the reset.  It may be preferable to
     * instead create a new barrier for subsequent use.
     */
    public void reset() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            breakBarrier();   // break the current generation
            nextGeneration(); // start a new generation
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of parties currently waiting at the barrier.
     * This method is primarily useful for debugging and assertions.
     *
     * @return the number of parties currently blocked in {@link #await}
     */
    public int getNumberWaiting() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return parties - count;
        } finally {
            lock.unlock();
        }
    }
}
