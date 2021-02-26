
package java.util.concurrent;

import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;

public class ThreadPoolExecutor extends AbstractExecutorService {
    /**
     *  ctl是一个32位integer，高3位用来表示线程池的控制状态（其中包含符号位），低29位表示worker的数量（worker就是工作线程的意思）
     */
    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
    /**
     * Integer.SIZE是32
     */
    private static final int COUNT_BITS = Integer.SIZE - 3;
    /**
     * 线程池线程数的最大为2^29-1，占用int最低29位bit
     * 0001 1111 1111 1111 1111 1111 1111 1111
     */
    private static final int CAPACITY = (1 << COUNT_BITS) - 1;
    /**
     * 这是一个标记，占用int最高3位bit，标记当前线程池处于RUNNING状态（接收新的任务，执行任务队列中的任务）
     * 1110 0000 0000 0000 0000 0000 0000 0000
     */
    private static final int RUNNING = -1 << COUNT_BITS;
    /**
     * 这是一个标记，占用int最高3位bit，标记当前线程池处于SHUTDOWN状态（不接收新的任务，但会执行任务队列中剩余未执行的任务）
     * 0000 0000 0000 0000 0000 0000 0000 0000
     */
    private static final int SHUTDOWN = 0 << COUNT_BITS;
    /**
     * 这是一个标记，占用int最高3位bit，标记当前线程池处于SHUTDOWN状态（不接收新的任务，不执行任务队列队列中的任务，并且中断正在运行的任务）
     * 0010 0000 0000 0000 0000 0000 0000 0000
     */
    private static final int STOP = 1 << COUNT_BITS;
    /**
     * 所有的任务都已经终止，任务队列任务都执行完毕， 线程池转化为TIDYING状态并且调用terminated钩子函数
     * 0100 0000 0000 0000 0000 0000 0000 0000
     */
    private static final int TIDYING = 2 << COUNT_BITS;
    /**
     * terminated钩子函数已经运行完成
     * 0110 0000 0000 0000 0000 0000 0000 0000
     */
    private static final int TERMINATED = 3 << COUNT_BITS;

    /**
     * ~符号表示按位取反（符号位也需要取反），CAPACITY取反之后与c按位与，获取当前线程池的状态，其实就是获取最高3位bit的状态
     * ~CAPACITY的二进制值为 1110 0000 0000 0000 0000 0000 0000 0000
     */
    private static int runStateOf(int c) { return c & ~CAPACITY; }

    /**
     * CAPACITY与c按位与，获取workerCount，其实就是获取线程池当前线程数
     */
    private static int workerCountOf(int c) { return c & CAPACITY; }

    /**
     * ｜符号表示按位或
     * @param rs 代表run state，取首字母
     * @param wc 代表worker count，取首字母
     * @return
     */
    private static int ctlOf(int rs, int wc) { return rs | wc; }

    /*
     * Bit field accessors that don't require unpacking ctl. These depend on the bit
     * layout and on workerCount being never negative.
     */
    private static boolean runStateLessThan(int c, int s) {
        return c < s;
    }

    private static boolean runStateAtLeast(int c, int s) {
        return c >= s;
    }

    private static boolean isRunning(int c) {
        return c < SHUTDOWN;
    }

    /**
     * 通过cas修改worker线程数量，线程数加1
     */
    private boolean compareAndIncrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect + 1);
    }

    /**
     * 通过cas修改worker线程数量，线程数减1
     */
    private boolean compareAndDecrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect - 1);
    }

    /**
     * 其实就是减少一个worker线程数量
     */
    private void decrementWorkerCount() {
        do {
        } while (!compareAndDecrementWorkerCount(ctl.get()));
    }
    /**
     * 任务队列
     */
    private final BlockingQueue<Runnable> workQueue;
    private final ReentrantLock mainLock = new ReentrantLock();
    /**
     * 内部存放线程池所有的线程，和workQueue区别就是：workQueue是用来存放需要执行的任务
     */
    private final HashSet<Worker> workers = new HashSet<Worker>();
    private final Condition termination = mainLock.newCondition();
    private int largestPoolSize;
    private long completedTaskCount;
    /**
     * ThreadFactory线程工厂，只有一个Thread newThread(Runnable r)方法 该方法返回一个线程
     */
    private volatile ThreadFactory threadFactory;
    /**
     * 拒绝一个任务之后的处理器 Handler
     */
    private volatile RejectedExecutionHandler handler;
    /**
     * 线程空闲等待新的任务，如果超过这个时间还没有新任务，那么该线程进入死亡
     */
    private volatile long keepAliveTime;
    /**
     * 默认为false,表示核心线程在空闲时是否自动过期死亡,如果为true,则核心线程空闲keepAliveTime时间后死亡
     */
    private volatile boolean allowCoreThreadTimeOut;
    /**
     * 核心线程数,corePoolSize<=maximumPoolSize,如果线程数到达corePoolSize此刻还有多余任务没处理，则会启动非核心线程，
     * 如果线程数超出corePoolSize,并且从workQueue获取任务超过keepAliveTime时间，就会销毁一个多余的线程
     */
    private volatile int corePoolSize;
    /**
     * 最大线程数，线程池线程数不能超出该界限
     */
    private volatile int maximumPoolSize;
    /**
     * 当任务队列已经满了的情况下，如果有新的任务到来，那么就要用到RejectedExecutionHandler
     */
    private static final RejectedExecutionHandler defaultHandler = new AbortPolicy();

    /**
     * Permission required for callers of shutdown and shutdownNow. We additionally
     * require (see checkShutdownAccess) that callers have permission to actually
     * interrupt threads in the worker set (as governed by Thread.interrupt, which
     * relies on ThreadGroup.checkAccess, which in turn relies on
     * SecurityManager.checkAccess). Shutdowns are attempted only if these checks
     * pass.
     *
     * All actual invocations of Thread.interrupt (see interruptIdleWorkers and
     * interruptWorkers) ignore SecurityExceptions, meaning that the attempted
     * interrupts silently fail. In the case of shutdown, they should not fail
     * unless the SecurityManager has inconsistent policies, sometimes allowing
     * access to a thread and sometimes not. In such cases, failure to actually
     * interrupt threads may disable or delay full termination. Other uses of
     * interruptIdleWorkers are advisory, and failure to actually interrupt will
     * merely delay response to configuration changes so is not handled
     * exceptionally.
     */
    private static final RuntimePermission shutdownPerm = new RuntimePermission("modifyThread");

    /* The context to be used when executing the finalizer, or null. */
    private final AccessControlContext acc;

    /**
     * Worker继承了AQS抽象类，其重写了AQS的一些方法，并且其也可作为一个Runnable对象加入到workQueue中
     */
    private final class Worker extends AbstractQueuedSynchronizer implements Runnable {
        private static final long serialVersionUID = 6138294804551838833L;
        final Thread thread;
        Runnable firstTask;
        /** 当前线程已完成的任务数*/ 
        volatile long completedTasks;
        Worker(Runnable firstTask) {
            setState(-1); // inhibit interrupts until runWorker
            this.firstTask = firstTask;
            //请注意此处getThreadFactory().newThread(this)传入的是this引用，这里会将worker封装为一个Thread
            this.thread = getThreadFactory().newThread(this);
        }
        /**
         * addWorker成功之后会启动新线程，然后线程被CPU调度后会执行run方法
         */
        public void run() {
            //本质上是执行一个while循环不断从任务队列获取任务并执行
            runWorker(this);
        }
        protected boolean isHeldExclusively() {
            return getState() != 0;
        }
        protected boolean tryAcquire(int unused) {
            if (compareAndSetState(0, 1)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }
        protected boolean tryRelease(int unused) {
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }
        public void lock() {
            acquire(1);
        }
        public boolean tryLock() {
            return tryAcquire(1);
        }
        public void unlock() {
            release(1);
        }
        public boolean isLocked() {
            return isHeldExclusively();
        }
        void interruptIfStarted() {
            Thread t;
            if (getState() >= 0 && (t = thread) != null && !t.isInterrupted()) {
                try {
                    t.interrupt();
                } catch (SecurityException ignore) {
                }
            }
        }
    }

    /*
     * Methods for setting control state
     */

    /**
     * 变更状态，如果targetState <= c，则c不变；如果targetState > c，则c替换值为targetState
     *
     * @param targetState the desired state, either SHUTDOWN or STOP (but not
     *                    TIDYING or TERMINATED -- use tryTerminate for that)
     */
    private void advanceRunState(int targetState) {
        for (;;) {
            int c = ctl.get();
            if (runStateAtLeast(c, targetState) || ctl.compareAndSet(c, ctlOf(targetState, workerCountOf(c))))
                break;
        }
    }

    /**
     * Transitions to TERMINATED state if either (SHUTDOWN and pool and queue empty)
     * or (STOP and pool empty). If otherwise eligible to terminate but workerCount
     * is nonzero, interrupts an idle worker to ensure that shutdown signals
     * propagate. This method must be called following any action that might make
     * termination possible -- reducing worker count or removing tasks from the
     * queue during shutdown. The method is non-private to allow access from
     * ScheduledThreadPoolExecutor.
     */
    final void tryTerminate() {
        for (;;) {
            int c = ctl.get();
            if (isRunning(c) || // 如果状态为Running
                    runStateAtLeast(c, TIDYING) || // 或者状态大于等于TIDYING
                    (runStateOf(c) == SHUTDOWN && !workQueue.isEmpty()))// 或者状态为SHUTDOWN并且阻塞队列不为空
                return;
            //执行到这一步说明仅有可能是一种情况：状态为SHUTDOWN并且workQueue.isEmpty()
            if (workerCountOf(c) != 0) { // Eligible to terminate
                interruptIdleWorkers(ONLY_ONE);// 中断最多一个线程
                return;
            }

            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                if (ctl.compareAndSet(c, ctlOf(TIDYING, 0))) {
                    try {
                        terminated();// 由子类实现，钩子函数
                    } finally {
                        ctl.set(ctlOf(TERMINATED, 0));
                        termination.signalAll();
                    }
                    return;
                }
            } finally {
                mainLock.unlock();
            }
            // else retry on failed CAS
        }
    }

    /*
     * Methods for controlling interrupts to worker threads.
     */

    /**
     * If there is a security manager, makes sure caller has permission to shut down
     * threads in general (see shutdownPerm). If this passes, additionally makes
     * sure the caller is allowed to interrupt each worker thread. This might not be
     * true even if first check passed, if the SecurityManager treats some threads
     * specially.
     */
    private void checkShutdownAccess() {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkPermission(shutdownPerm);
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                for (Worker w : workers)
                    security.checkAccess(w.thread);
            } finally {
                mainLock.unlock();
            }
        }
    }

    /**
     * 中断所有线程，即使该线程正在运行，但有可能某些线程不会被中断 Interrupts all threads, even
     * if active. Ignores SecurityExceptions (in which case some threads may remain
     * uninterrupted).
     */
    private void interruptWorkers() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (Worker w : workers)
                w.interruptIfStarted();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 中断空闲线程，如果传入参数onlyOne为true，则最多中断一个，如果为false则最多全部中断
     * 该方法调用后，在所有线程等待的过程中，最多中断一个线程，然后该中断信号能够传播给其他线程，
     * 中断任意一个线程能够确保新到达的任务线程会退出，为了保证最终退出，仅中断一个空闲的任务即可，
     * shutdown()会快速中断多余的任务，不会等到任务执行完成再退出 Interrupts threads that might
     * be waiting for tasks (as indicated by not being locked) so they can check for
     * termination or configuration changes. Ignores SecurityExceptions (in which
     * case some threads may remain uninterrupted).
     *
     * @param onlyOne If true, interrupt at most one worker. This is called only
     *                from tryTerminate when termination is otherwise enabled but
     *                there are still other workers. In this case, at most one
     *                waiting worker is interrupted to propagate shutdown signals in
     *                case all threads are currently waiting. Interrupting any
     *                arbitrary thread ensures that newly arriving workers since
     *                shutdown began will also eventually exit. To guarantee
     *                eventual termination, it suffices to always interrupt only one
     *                idle worker, but shutdown() interrupts all idle workers so
     *                that redundant workers exit promptly, not waiting for a
     *                straggler task to finish.
     */
    private void interruptIdleWorkers(boolean onlyOne) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (Worker w : workers) {
                Thread t = w.thread;
                if (!t.isInterrupted() && w.tryLock()) {
                    try {
                        t.interrupt();
                    } catch (SecurityException ignore) {
                    } finally {
                        w.unlock();
                    }
                }
                if (onlyOne)
                    break;
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 中断阻塞队列里面所有的线程 Common form of interruptIdleWorkers, to avoid having to
     * remember what the boolean argument means.
     */
    private void interruptIdleWorkers() {
        interruptIdleWorkers(false);
    }

    private static final boolean ONLY_ONE = true;

    /*
     * Misc utilities, most of which are also exported to
     * ScheduledThreadPoolExecutor
     */

    /**
     * 拒绝某个任务之后的handler处理器执行 Invokes the rejected execution handler for the
     * given command. Package-protected for use by ScheduledThreadPoolExecutor.
     */
    final void reject(Runnable command) {
        handler.rejectedExecution(command, this);
    }

    /**
     * 做shutdown后的收尾工作，用于ScheduledThreadPoolExecutor取消一些延迟的任务 Performs any
     * further cleanup following run state transition on invocation of shutdown. A
     * no-op here, but used by ScheduledThreadPoolExecutor to cancel delayed tasks.
     */
    void onShutdown() {
    }

    /**
     * 如果为Running则返回true，或者状态为SHUTDOWN并且传入参数为true State check needed by
     * ScheduledThreadPoolExecutor to enable running tasks during shutdown.
     *
     * @param shutdownOK true if should return true if SHUTDOWN
     */
    final boolean isRunningOrShutdown(boolean shutdownOK) {
        int rs = runStateOf(ctl.get());
        return rs == RUNNING || (rs == SHUTDOWN && shutdownOK);
    }

    /**
     * 清空阻塞队列，并将阻塞队列所有任务移到一个新的数组里面 Drains the task queue into a new
     * list, normally using drainTo. But if the queue is a DelayQueue or any other
     * kind of queue for which poll or drainTo may fail to remove some elements, it
     * deletes them one by one.
     */
    private List<Runnable> drainQueue() {
        BlockingQueue<Runnable> q = workQueue;
        ArrayList<Runnable> taskList = new ArrayList<Runnable>();
        q.drainTo(taskList);
        if (!q.isEmpty()) {
            for (Runnable r : q.toArray(new Runnable[0])) {
                if (q.remove(r))
                    taskList.add(r);
            }
        }
        return taskList;
    }

    /*
     * Methods for creating, running and cleaning up after workers
     */

    /**
     * Checks if a new worker can be added with respect to current pool state and
     * the given bound (either core or maximum). If so, the worker count is adjusted
     * accordingly, and, if possible, a new worker is created and started, running
     * firstTask as its first task. This method returns false if the pool is stopped
     * or eligible to shut down. It also returns false if the thread factory fails
     * to create a thread when asked. If the thread creation fails, either due to
     * the thread factory returning null, or due to an exception (typically
     * OutOfMemoryError in Thread.start()), we roll back cleanly.
     *
     * @param firstTask the task the new thread should run first (or null if none).
     *                  Workers are created with an initial first task (in method
     *                  execute()) to bypass queuing when there are fewer than
     *                  corePoolSize threads (in which case we always start one), or
     *                  when the queue is full (in which case we must bypass queue).
     *                  Initially idle threads are usually created via
     *                  prestartCoreThread or to replace other dying workers.
     *
     * @param core      if true use corePoolSize as bound, else maximumPoolSize. (A
     *                  boolean indicator is used here rather than a value to ensure
     *                  reads of fresh values after checking other pool state).
     * @return true if successful
     * 该方法创建并启动一个Worker线程，如果成功则返回true，失败返回false，其实本质上是运行Worker的run方法
     * firstTask表示新的任务，该任务表示新的线程第一个执行的任务；
     * core代表是否是创建核心线程，true代表是核心线程，false代表不是核心线程；
     * 1.先检测线程池状态是否是RUNNING，不是RUNNING则返回false；
     * 
     * 2.如果core为true，比较当前线程数wc与corePoolSize大小，如果wc>=corePoolSize说明线程池满了，此时返回false，
     * 如果wc < corePoolSize，此时需要新创建一个线程并启动，如果firstTask不为null，那么新创建线程就直接执行该任务
     * 如果firstTask为null，那么新线程就从workerQueue取出一个任务执行，取出任务操作是阻塞的；
     * 
     * 3.如果core为false，此时需要比较当前线程数wc与maximumPoolSize大小，如果wc>=maximumPoolSize
     * 说明线程池已经是最大容量了，此时返回false，如果wc < maximumPoolSize，此时需要新创建一个线程并启动，此时就有可能
     * corePoolSize<=wc<=maximumPoolSize，如果firstTask不为null，那么新创建线程就直接执行该任务，
     * 如果firstTask为null，那么新线程就从workerQueue取出一个任务执行
     */
    private boolean addWorker(Runnable firstTask, boolean core) {
        retry: for (;;) {
            int c = ctl.get();
            int rs = runStateOf(c);

            // Check if queue empty only if necessary.
            if (rs >= SHUTDOWN && // 如果状态不是RUNNING并且（任务不为空或者workerQueue为空）则返回false
                    !(rs == SHUTDOWN && firstTask == null && !workQueue.isEmpty()))
                return false;

            for (;;) {
                int wc = workerCountOf(c);
                // 线程数超出最大容量2^29-1
                if (wc >= CAPACITY || 
                		//线程数超出corePoolSize||maximumPoolSize，core为true则比较corePoolSize
                        wc >= (core ? corePoolSize : maximumPoolSize))
                    /*
                    * 一般走到这一步有几种情况
                    * 1.如果core是true，这说明工作线程数>=corePoolSize
                    * 2.如果core是false，这说明线程池到了最大容量
                    */
                    return false;
                if (compareAndIncrementWorkerCount(c))// 如果CAS增加线程数成功则认为添加任务成功，之后终止retry语句块
                    break retry;
                c = ctl.get(); // 如果前面CAS失败，则有可能代码执行到这里的时候线程数有变动，所以重新获取一次
                if (runStateOf(c) != rs)// 如果CAS失败并且队列任务数变动了则重新执行retry语句块
                    continue retry;
                // else CAS failed due to workerCount change; retry inner loop
            }
        }

        boolean workerStarted = false;
        boolean workerAdded = false;
        Worker w = null;
        try {
            w = new Worker(firstTask);// 创建新worker
            final Thread t = w.thread;
            if (t != null) {// worker的线程不为null
                final ReentrantLock mainLock = this.mainLock;
                mainLock.lock();
                try {
                    // Recheck while holding lock.
                    // Back out on ThreadFactory failure or if
                    // shut down before lock acquired.
                    int rs = runStateOf(ctl.get());// 重新检查线程池状态，以防在这段期间主线程执行了shutdown

                    // 如果状态为RUNNING或者（状态为SHUTDOWN并且firstTask是null）
                    if (rs < SHUTDOWN || (rs == SHUTDOWN && firstTask == null)) {
                        if (t.isAlive()) // 检查该线程是否已经start执行过
                            throw new IllegalThreadStateException();
                        workers.add(w);// workers是一个Set，workers添加任务到里面
                        int s = workers.size();
                        if (s > largestPoolSize)
                            largestPoolSize = s;
                        workerAdded = true;
                    }
                } finally {
                    mainLock.unlock();
                }
                if (workerAdded) {// 成功添加后直接启动线程，本质上是启动worker，因为Worker构造函数传给ThreadFactory的是this
                    t.start();
                    workerStarted = true;
                }
            }
        } finally {
            if (!workerStarted)// 添加失败后会移除该worker
                addWorkerFailed(w);
        }
        return workerStarted;
    }

    /**
     * Rolls back the worker thread creation. - removes worker from workers, if
     * present - decrements worker count - rechecks for termination, in case the
     * existence of this worker was holding up termination
     */
    private void addWorkerFailed(Worker w) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            if (w != null)
                workers.remove(w);
            decrementWorkerCount();
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Performs cleanup and bookkeeping for a dying worker. Called only from worker
     * threads. Unless completedAbruptly is set, assumes that workerCount has
     * already been adjusted to account for exit. This method removes thread from
     * worker set, and possibly terminates the pool or replaces the worker if either
     * it exited due to user task exception or if fewer than corePoolSize workers
     * are running or queue is non-empty but there are no workers.
     *
     * @param w                 the worker
     * @param completedAbruptly if the worker died due to user exception
     */
    private void processWorkerExit(Worker w, boolean completedAbruptly) {
        if (completedAbruptly) // 如果是中途抛异常被中断，此时移除这个异常的线程
            decrementWorkerCount();

        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            completedTaskCount += w.completedTasks;
            workers.remove(w);
        } finally {
            mainLock.unlock();
        }

        tryTerminate();//最多停止一个线程

        int c = ctl.get();
        if (runStateLessThan(c, STOP)) {//如果还是RUNNING或者是SHUTDOWN但是还有任务没执行完
            if (!completedAbruptly) {//如果是正常线程退出
                int min = allowCoreThreadTimeOut ? 0 : corePoolSize;
                if (min == 0 && !workQueue.isEmpty())
                    min = 1;
                if (workerCountOf(c) >= min)
                    return; // replacement not needed
            }
            //如果是中途抛异常被中断，此时重新创建一个新的线程替换掉抛异常的那个线程
            addWorker(null, false);
        }
    }

    /**
     * Performs blocking or timed wait for a task, depending on current
     * configuration settings, or returns null if this worker must exit because of
     * any of: 1. There are more than maximumPoolSize workers (due to a call to
     * setMaximumPoolSize). 2. The pool is stopped. 3. The pool is shutdown and the
     * queue is empty. 4. This worker timed out waiting for a task, and timed-out
     * workers are subject to termination (that is,
     * {@code allowCoreThreadTimeOut || workerCount > corePoolSize}) both before and
     * after the timed wait, and if the queue is non-empty, this worker is not the
     * last thread in the pool.
     *
     * @return task, or null if the worker must exit, in which case workerCount is
     *         decremented
     */
    private Runnable getTask() {
        boolean timedOut = false; // Did the last poll() time out?

        for (;;) {
            int c = ctl.get();
            int rs = runStateOf(c);

            // 如果>=SHUTDOWN状态，那么有可能此时刚刚是执行shutdown方法，此时有可能任务队列还有任务，
            // 所以还需要判断任务是否是已经完全STOP，如果不是完全STOP，那么需要判断任务队列是否为空
            if (rs >= SHUTDOWN && (rs >= STOP || workQueue.isEmpty())) {
                decrementWorkerCount();//到这里需要减少一个线程
                return null;
            }

            int wc = workerCountOf(c);

            // allowCoreThreadTimeOut在ThreadPoolExcutor默认是false，也没有任何地方有调用过SetAllowCoreThreadTimeOut
            // ScheduledThreadPoolExecutor也没有改动过allowCoreThreadTimeOut的值，一般来讲只有workerQueue满了的情况下才有可能出现wc > corePoolSize
            boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;

            //一般来讲wc > maximumPoolSize永远不会为true，除非用户自己重写了excute和addWorker方法，有可能执行一次循环过后还没
            //获取到一个任务，到这里timeout为true并且timed为true，这时候就返回null，告诉线程池没有任务返回，多余的线程可以销毁了
            if ((wc > maximumPoolSize || (timed && timedOut)) && (wc > 1 || workQueue.isEmpty())) {
                if (compareAndDecrementWorkerCount(c))
                    return null;
                continue;
            }

            try {
                //走到这一步，timed为true的条件就是wc > corePoolSize，注意keepAliveTime，如果在keepAliveTime时间
                //内线程还没获取到任务则会进入下一个循环，下一个循环就会将该线程移除出线程池，这也是keepAliveTime这个参数生效的地方
                Runnable r = timed ? workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) : workQueue.take();
                if (r != null)
                    return r;
                timedOut = true;
            } catch (InterruptedException retry) {
                timedOut = false;
            }
        }
    }

    /**
     * Main worker run loop. Repeatedly gets tasks from queue and executes them,
     * while coping with a number of issues:
     *
     * 1. We may start out with an initial task, in which case we don't need to get
     * the first one. Otherwise, as long as pool is running, we get tasks from
     * getTask. If it returns null then the worker exits due to changed pool state
     * or configuration parameters. Other exits result from exception throws in
     * external code, in which case completedAbruptly holds, which usually leads
     * processWorkerExit to replace this thread.
     *
     * 2. Before running any task, the lock is acquired to prevent other pool
     * interrupts while the task is executing, and then we ensure that unless pool
     * is stopping, this thread does not have its interrupt set.
     *
     * 3. Each task run is preceded by a call to beforeExecute, which might throw an
     * exception, in which case we cause thread to die (breaking loop with
     * completedAbruptly true) without processing the task.
     *
     * 4. Assuming beforeExecute completes normally, we run the task, gathering any
     * of its thrown exceptions to send to afterExecute. We separately handle
     * RuntimeException, Error (both of which the specs guarantee that we trap) and
     * arbitrary Throwables. Because we cannot rethrow Throwables within
     * Runnable.run, we wrap them within Errors on the way out (to the thread's
     * UncaughtExceptionHandler). Any thrown exception also conservatively causes
     * thread to die.
     *
     * 5. After task.run completes, we call afterExecute, which may also throw an
     * exception, which will also cause thread to die. According to JLS Sec 14.20,
     * this exception is the one that will be in effect even if task.run throws.
     *
     * The net effect of the exception mechanics is that afterExecute and the
     * thread's UncaughtExceptionHandler have as accurate information as we can
     * provide about any problems encountered by user code.
     *
     * @param w the worker
     */
    final void runWorker(Worker w) {
        Thread wt = Thread.currentThread();
        Runnable task = w.firstTask;
        w.firstTask = null;
        w.unlock(); // allow interrupts
        boolean completedAbruptly = true;
        try {
            //如果worker的firstTask为null，则需要从任务队列里面获取一个任务
            while (task != null || (task = getTask()) != null) {
                w.lock();
                // If pool is stopping, ensure thread is interrupted;
                // if not, ensure thread is not interrupted. This
                // requires a recheck in second case to deal with
                // shutdownNow race while clearing interrupt
                if ((runStateAtLeast(ctl.get(), STOP) || (Thread.interrupted() && runStateAtLeast(ctl.get(), STOP)))
                        && !wt.isInterrupted())
                    wt.interrupt();
                try {
                    beforeExecute(wt, task);//钩子函数，由子类去实现
                    Throwable thrown = null;
                    try {
                        task.run();
                    } catch (RuntimeException x) {
                        thrown = x;
                        throw x;
                    } catch (Error x) {
                        thrown = x;
                        throw x;
                    } catch (Throwable x) {
                        thrown = x;
                        throw new Error(x);
                    } finally {
                        afterExecute(task, thrown);//钩子函数，由子类去实现
                    }
                } finally {
                    task = null;
                    w.completedTasks++;
                    w.unlock();
                }
            }
            //如果task为null，要么是执行shutdown()导致的，要么是超时还没获取到workerQueue的任务
            completedAbruptly = false;
        } finally {//到这一步就有可能是中途抛异常，或者是正常执行shutdown()，或者是超出的线程正常销毁
            processWorkerExit(w, completedAbruptly);
        }
    }

    // Public constructors and methods

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial parameters
     * and default thread factory and rejected execution handler. It may be more
     * convenient to use one of the {@link Executors} factory methods instead of
     * this general purpose constructor.
     *
     * @param corePoolSize    the number of threads to keep in the pool, even if
     *                        they are idle, unless {@code allowCoreThreadTimeOut}
     *                        is set
     * @param maximumPoolSize the maximum number of threads to allow in the pool
     * @param keepAliveTime   when the number of threads is greater than the core,
     *                        this is the maximum time that excess idle threads will
     *                        wait for new tasks before terminating.
     * @param unit            the time unit for the {@code keepAliveTime} argument
     * @param workQueue       the queue to use for holding tasks before they are
     *                        executed. This queue will hold only the
     *                        {@code Runnable} tasks submitted by the
     *                        {@code execute} method.
     * @throws IllegalArgumentException if one of the following holds:<br>
     *                                  {@code corePoolSize < 0}<br>
     *                                  {@code keepAliveTime < 0}<br>
     *                                  {@code maximumPoolSize <= 0}<br>
     *                                  {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException     if {@code workQueue} is null
     */
    public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            BlockingQueue<Runnable> workQueue) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, Executors.defaultThreadFactory(),
                defaultHandler);
    }

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial parameters
     * and default rejected execution handler.
     *
     * @param corePoolSize    the number of threads to keep in the pool, even if
     *                        they are idle, unless {@code allowCoreThreadTimeOut}
     *                        is set
     * @param maximumPoolSize the maximum number of threads to allow in the pool
     * @param keepAliveTime   when the number of threads is greater than the core,
     *                        this is the maximum time that excess idle threads will
     *                        wait for new tasks before terminating.
     * @param unit            the time unit for the {@code keepAliveTime} argument
     * @param workQueue       the queue to use for holding tasks before they are
     *                        executed. This queue will hold only the
     *                        {@code Runnable} tasks submitted by the
     *                        {@code execute} method.
     * @param threadFactory   the factory to use when the executor creates a new
     *                        thread
     * @throws IllegalArgumentException if one of the following holds:<br>
     *                                  {@code corePoolSize < 0}<br>
     *                                  {@code keepAliveTime < 0}<br>
     *                                  {@code maximumPoolSize <= 0}<br>
     *                                  {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException     if {@code workQueue} or
     *                                  {@code threadFactory} is null
     */
    public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, defaultHandler);
    }

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial parameters
     * and default thread factory.
     *
     * @param corePoolSize    the number of threads to keep in the pool, even if
     *                        they are idle, unless {@code allowCoreThreadTimeOut}
     *                        is set
     * @param maximumPoolSize the maximum number of threads to allow in the pool
     * @param keepAliveTime   when the number of threads is greater than the core,
     *                        this is the maximum time that excess idle threads will
     *                        wait for new tasks before terminating.
     * @param unit            the time unit for the {@code keepAliveTime} argument
     * @param workQueue       the queue to use for holding tasks before they are
     *                        executed. This queue will hold only the
     *                        {@code Runnable} tasks submitted by the
     *                        {@code execute} method.
     * @param handler         the handler to use when execution is blocked because
     *                        the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if one of the following holds:<br>
     *                                  {@code corePoolSize < 0}<br>
     *                                  {@code keepAliveTime < 0}<br>
     *                                  {@code maximumPoolSize <= 0}<br>
     *                                  {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException     if {@code workQueue} or {@code handler} is
     *                                  null
     */
    public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            BlockingQueue<Runnable> workQueue, RejectedExecutionHandler handler) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, Executors.defaultThreadFactory(), handler);
    }

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial parameters.
     *
     * @param corePoolSize    the number of threads to keep in the pool, even if
     *                        they are idle, unless {@code allowCoreThreadTimeOut}
     *                        is set
     * @param maximumPoolSize the maximum number of threads to allow in the pool
     * @param keepAliveTime   when the number of threads is greater than the core,
     *                        this is the maximum time that excess idle threads will
     *                        wait for new tasks before terminating.
     * @param unit            the time unit for the {@code keepAliveTime} argument
     * @param workQueue       the queue to use for holding tasks before they are
     *                        executed. This queue will hold only the
     *                        {@code Runnable} tasks submitted by the
     *                        {@code execute} method.
     * @param threadFactory   the factory to use when the executor creates a new
     *                        thread
     * @param handler         the handler to use when execution is blocked because
     *                        the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if one of the following holds:<br>
     *                                  {@code corePoolSize < 0}<br>
     *                                  {@code keepAliveTime < 0}<br>
     *                                  {@code maximumPoolSize <= 0}<br>
     *                                  {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException     if {@code workQueue} or
     *                                  {@code threadFactory} or {@code handler} is
     *                                  null
     */
    public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory, RejectedExecutionHandler handler) {
        if (corePoolSize < 0 || maximumPoolSize <= 0 || maximumPoolSize < corePoolSize || keepAliveTime < 0)
            throw new IllegalArgumentException();
        if (workQueue == null || threadFactory == null || handler == null)
            throw new NullPointerException();
        this.acc = System.getSecurityManager() == null ? null : AccessController.getContext();
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.workQueue = workQueue;
        this.keepAliveTime = unit.toNanos(keepAliveTime);
        this.threadFactory = threadFactory;
        this.handler = handler;
    }

    /**
     * Executes the given task sometime in the future. The task may execute in a new
     * thread or in an existing pooled thread.
     *
     * If the task cannot be submitted for execution, either because this executor
     * has been shutdown or because its capacity has been reached, the task is
     * handled by the current {@code RejectedExecutionHandler}.
     *
     * @param command the task to execute
     * @throws RejectedExecutionException at discretion of
     *                                    {@code RejectedExecutionHandler}, if the
     *                                    task cannot be accepted for execution
     * @throws NullPointerException       if {@code command} is null
     */
    public void execute(Runnable command) {
        if (command == null)
            throw new NullPointerException();
        //需要注意此处的command，command代表一个任务，而addWorker才是启动一个线程，
        //一个线程会循环从任务队列里面取出任务并执行，任务和线程不是一个概念
        int c = ctl.get();
        if (workerCountOf(c) < corePoolSize) {// 如果当前已经运行的线程数<corePoolSize
        	//直接创建新的线程执行该任务，addWorker方法传入true，则代表是core核心线程，此时如果线程池满了会返回false
            if (addWorker(command, true))
                return;
            c = ctl.get();// 如果前面addWorker语句返回false，就说明线程池满了，那么此时要重新获取ctl
        }
        //走到这一步，说明线程池线程到达corePoolSize，此时需要将任务添加到队列里面
        if (isRunning(c) && workQueue.offer(command)) {
            int recheck = ctl.get();//重新检查ctl，有可能这段时间线程数又有变化或者状态变化
            if (!isRunning(recheck) && remove(command))//如果不是RUNNING，并且任务移除出队列成功
                reject(command);//拒绝该任务
            else if (workerCountOf(recheck) == 0)//如果此时工作线程数为0，那么必须保证要有一个线程执行任务
            	//此时创建一个非核心线程立即执行，由于任务在前面已经添加到队列了，此时firstTask就必须是null
                addWorker(null, false);
        }
        //走到else if，则是调用了shutdown或者队列满了，此刻需要启动非核心线程执行任务
        else if (!addWorker(command, false))//如果启动非核心线程失败，说明线程池到达最大容量，并且任务队列已满此刻需要执行reject
            /*
            * 1.如果此时不是RUNNING状态(有可能这段期间有人执行了shutdown操作)或者添加任务到队列失败(说明队列已经满了)
            * 2.这时候不管是shutdown执行过还是队列满了，都需要创建非核心线程执行该任务，如果创建还是失败，则需要拒绝该任务
            */
            reject(command);
    }

    /**
     * Initiates an orderly shutdown in which previously submitted tasks are
     * executed, but no new tasks will be accepted. Invocation has no additional
     * effect if already shut down.
     *
     * <p>
     * This method does not wait for previously submitted tasks to complete
     * execution. Use {@link #awaitTermination awaitTermination} to do that.
     *
     * @throws SecurityException {@inheritDoc}
     */
    public void shutdown() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            checkShutdownAccess();
            advanceRunState(SHUTDOWN);
            interruptIdleWorkers();
            onShutdown(); // hook for ScheduledThreadPoolExecutor
        } finally {
            mainLock.unlock();
        }
        tryTerminate();
    }

    /**
     * Attempts to stop all actively executing tasks, halts the processing of
     * waiting tasks, and returns a list of the tasks that were awaiting execution.
     * These tasks are drained (removed) from the task queue upon return from this
     * method.
     *
     * <p>
     * This method does not wait for actively executing tasks to terminate. Use
     * {@link #awaitTermination awaitTermination} to do that.
     *
     * <p>
     * There are no guarantees beyond best-effort attempts to stop processing
     * actively executing tasks. This implementation cancels tasks via
     * {@link Thread#interrupt}, so any task that fails to respond to interrupts may
     * never terminate.
     *
     * @throws SecurityException {@inheritDoc}
     */
    public List<Runnable> shutdownNow() {
        List<Runnable> tasks;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            checkShutdownAccess();
            advanceRunState(STOP);
            interruptWorkers();
            tasks = drainQueue();
        } finally {
            mainLock.unlock();
        }
        tryTerminate();
        return tasks;
    }

    public boolean isShutdown() {
        return !isRunning(ctl.get());
    }

    /**
     * Returns true if this executor is in the process of terminating after
     * {@link #shutdown} or {@link #shutdownNow} but has not completely terminated.
     * This method may be useful for debugging. A return of {@code true} reported a
     * sufficient period after shutdown may indicate that submitted tasks have
     * ignored or suppressed interruption, causing this executor not to properly
     * terminate.
     *
     * @return {@code true} if terminating but not yet terminated
     */
    public boolean isTerminating() {
        int c = ctl.get();
        return !isRunning(c) && runStateLessThan(c, TERMINATED);
    }

    public boolean isTerminated() {
        return runStateAtLeast(ctl.get(), TERMINATED);
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (;;) {
                if (runStateAtLeast(ctl.get(), TERMINATED))
                    return true;
                if (nanos <= 0)
                    return false;
                nanos = termination.awaitNanos(nanos);
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Invokes {@code shutdown} when this executor is no longer referenced and it
     * has no threads.
     */
    protected void finalize() {
        SecurityManager sm = System.getSecurityManager();
        if (sm == null || acc == null) {
            shutdown();
        } else {
            PrivilegedAction<Void> pa = () -> {
                shutdown();
                return null;
            };
            AccessController.doPrivileged(pa, acc);
        }
    }

    /**
     * Sets the thread factory used to create new threads.
     *
     * @param threadFactory the new thread factory
     * @throws NullPointerException if threadFactory is null
     * @see #getThreadFactory
     */
    public void setThreadFactory(ThreadFactory threadFactory) {
        if (threadFactory == null)
            throw new NullPointerException();
        this.threadFactory = threadFactory;
    }

    /**
     * Returns the thread factory used to create new threads.
     *
     * @return the current thread factory
     * @see #setThreadFactory(ThreadFactory)
     */
    public ThreadFactory getThreadFactory() {
        return threadFactory;
    }

    /**
     * Sets a new handler for unexecutable tasks.
     *
     * @param handler the new handler
     * @throws NullPointerException if handler is null
     * @see #getRejectedExecutionHandler
     */
    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        if (handler == null)
            throw new NullPointerException();
        this.handler = handler;
    }

    /**
     * Returns the current handler for unexecutable tasks.
     *
     * @return the current handler
     * @see #setRejectedExecutionHandler(RejectedExecutionHandler)
     */
    public RejectedExecutionHandler getRejectedExecutionHandler() {
        return handler;
    }

    /**
     * Sets the core number of threads. This overrides any value set in the
     * constructor. If the new value is smaller than the current value, excess
     * existing threads will be terminated when they next become idle. If larger,
     * new threads will, if needed, be started to execute any queued tasks.
     *
     * @param corePoolSize the new core size
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     * @see #getCorePoolSize
     */
    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize < 0)
            throw new IllegalArgumentException();
        int delta = corePoolSize - this.corePoolSize;
        this.corePoolSize = corePoolSize;
        if (workerCountOf(ctl.get()) > corePoolSize)
            interruptIdleWorkers();
        else if (delta > 0) {
            // We don't really know how many new threads are "needed".
            // As a heuristic, prestart enough new workers (up to new
            // core size) to handle the current number of tasks in
            // queue, but stop if queue becomes empty while doing so.
            int k = Math.min(delta, workQueue.size());
            while (k-- > 0 && addWorker(null, true)) {
                if (workQueue.isEmpty())
                    break;
            }
        }
    }

    /**
     * Returns the core number of threads.
     *
     * @return the core number of threads
     * @see #setCorePoolSize
     */
    public int getCorePoolSize() {
        return corePoolSize;
    }

    /**
     * Starts a core thread, causing it to idly wait for work. This overrides the
     * default policy of starting core threads only when new tasks are executed.
     * This method will return {@code false} if all core threads have already been
     * started.
     *
     * @return {@code true} if a thread was started
     */
    public boolean prestartCoreThread() {
        return workerCountOf(ctl.get()) < corePoolSize && addWorker(null, true);
    }

    /**
     * Same as prestartCoreThread except arranges that at least one thread is
     * started even if corePoolSize is 0.
     */
    void ensurePrestart() {
        int wc = workerCountOf(ctl.get());
        if (wc < corePoolSize)
            addWorker(null, true);
        else if (wc == 0)
            addWorker(null, false);
    }

    /**
     * Starts all core threads, causing them to idly wait for work. This overrides
     * the default policy of starting core threads only when new tasks are executed.
     *
     * @return the number of threads started
     */
    public int prestartAllCoreThreads() {
        int n = 0;
        while (addWorker(null, true))
            ++n;
        return n;
    }

    /**
     * Returns true if this pool allows core threads to time out and terminate if no
     * tasks arrive within the keepAlive time, being replaced if needed when new
     * tasks arrive. When true, the same keep-alive policy applying to non-core
     * threads applies also to core threads. When false (the default), core threads
     * are never terminated due to lack of incoming tasks.
     *
     * @return {@code true} if core threads are allowed to time out, else
     *         {@code false}
     *
     * @since 1.6
     */
    public boolean allowsCoreThreadTimeOut() {
        return allowCoreThreadTimeOut;
    }

    /**
     * Sets the policy governing whether core threads may time out and terminate if
     * no tasks arrive within the keep-alive time, being replaced if needed when new
     * tasks arrive. When false, core threads are never terminated due to lack of
     * incoming tasks. When true, the same keep-alive policy applying to non-core
     * threads applies also to core threads. To avoid continual thread replacement,
     * the keep-alive time must be greater than zero when setting {@code true}. This
     * method should in general be called before the pool is actively used.
     *
     * @param value {@code true} if should time out, else {@code false}
     * @throws IllegalArgumentException if value is {@code true} and the current
     *                                  keep-alive time is not greater than zero
     *
     * @since 1.6
     */
    public void allowCoreThreadTimeOut(boolean value) {
        if (value && keepAliveTime <= 0)
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        if (value != allowCoreThreadTimeOut) {
            allowCoreThreadTimeOut = value;
            if (value)
                interruptIdleWorkers();
        }
    }

    /**
     * Sets the maximum allowed number of threads. This overrides any value set in
     * the constructor. If the new value is smaller than the current value, excess
     * existing threads will be terminated when they next become idle.
     *
     * @param maximumPoolSize the new maximum
     * @throws IllegalArgumentException if the new maximum is less than or equal to
     *                                  zero, or less than the
     *                                  {@linkplain #getCorePoolSize core pool size}
     * @see #getMaximumPoolSize
     */
    public void setMaximumPoolSize(int maximumPoolSize) {
        if (maximumPoolSize <= 0 || maximumPoolSize < corePoolSize)
            throw new IllegalArgumentException();
        this.maximumPoolSize = maximumPoolSize;
        if (workerCountOf(ctl.get()) > maximumPoolSize)
            interruptIdleWorkers();
    }

    /**
     * Returns the maximum allowed number of threads.
     *
     * @return the maximum allowed number of threads
     * @see #setMaximumPoolSize
     */
    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    /**
     * Sets the time limit for which threads may remain idle before being
     * terminated. If there are more than the core number of threads currently in
     * the pool, after waiting this amount of time without processing a task, excess
     * threads will be terminated. This overrides any value set in the constructor.
     *
     * @param time the time to wait. A time value of zero will cause excess threads
     *             to terminate immediately after executing tasks.
     * @param unit the time unit of the {@code time} argument
     * @throws IllegalArgumentException if {@code time} less than zero or if
     *                                  {@code time} is zero and
     *                                  {@code allowsCoreThreadTimeOut}
     * @see #getKeepAliveTime(TimeUnit)
     */
    public void setKeepAliveTime(long time, TimeUnit unit) {
        if (time < 0)
            throw new IllegalArgumentException();
        if (time == 0 && allowsCoreThreadTimeOut())
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        long keepAliveTime = unit.toNanos(time);
        long delta = keepAliveTime - this.keepAliveTime;
        this.keepAliveTime = keepAliveTime;
        if (delta < 0)
            interruptIdleWorkers();
    }

    /**
     * Returns the thread keep-alive time, which is the amount of time that threads
     * in excess of the core pool size may remain idle before being terminated.
     *
     * @param unit the desired time unit of the result
     * @return the time limit
     * @see #setKeepAliveTime(long, TimeUnit)
     */
    public long getKeepAliveTime(TimeUnit unit) {
        return unit.convert(keepAliveTime, TimeUnit.NANOSECONDS);
    }

    /* User-level queue utilities */

    /**
     * Returns the task queue used by this executor. Access to the task queue is
     * intended primarily for debugging and monitoring. This queue may be in active
     * use. Retrieving the task queue does not prevent queued tasks from executing.
     *
     * @return the task queue
     */
    public BlockingQueue<Runnable> getQueue() {
        return workQueue;
    }

    /**
     * Removes this task from the executor's internal queue if it is present, thus
     * causing it not to be run if it has not already started.
     *
     * <p>
     * This method may be useful as one part of a cancellation scheme. It may fail
     * to remove tasks that have been converted into other forms before being placed
     * on the internal queue. For example, a task entered using {@code submit} might
     * be converted into a form that maintains {@code Future} status. However, in
     * such cases, method {@link #purge} may be used to remove those Futures that
     * have been cancelled.
     *
     * @param task the task to remove
     * @return {@code true} if the task was removed
     */
    public boolean remove(Runnable task) {
        boolean removed = workQueue.remove(task);
        tryTerminate(); // In case SHUTDOWN and now empty
        return removed;
    }

    /**
     * Tries to remove from the work queue all {@link Future} tasks that have been
     * cancelled. This method can be useful as a storage reclamation operation, that
     * has no other impact on functionality. Cancelled tasks are never executed, but
     * may accumulate in work queues until worker threads can actively remove them.
     * Invoking this method instead tries to remove them now. However, this method
     * may fail to remove tasks in the presence of interference by other threads.
     */
    public void purge() {
        final BlockingQueue<Runnable> q = workQueue;
        try {
            Iterator<Runnable> it = q.iterator();
            while (it.hasNext()) {
                Runnable r = it.next();
                if (r instanceof Future<?> && ((Future<?>) r).isCancelled())
                    it.remove();
            }
        } catch (ConcurrentModificationException fallThrough) {
            // Take slow path if we encounter interference during traversal.
            // Make copy for traversal and call remove for cancelled entries.
            // The slow path is more likely to be O(N*N).
            for (Object r : q.toArray())
                if (r instanceof Future<?> && ((Future<?>) r).isCancelled())
                    q.remove(r);
        }

        tryTerminate(); // In case SHUTDOWN and now empty
    }

    /* Statistics */

    /**
     * Returns the current number of threads in the pool.
     *
     * @return the number of threads
     */
    public int getPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // Remove rare and surprising possibility of
            // isTerminated() && getPoolSize() > 0
            return runStateAtLeast(ctl.get(), TIDYING) ? 0 : workers.size();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns the approximate number of threads that are actively executing tasks.
     *
     * @return the number of threads
     */
    public int getActiveCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            int n = 0;
            for (Worker w : workers)
                if (w.isLocked())
                    ++n;
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns the largest number of threads that have ever simultaneously been in
     * the pool.
     *
     * @return the number of threads
     */
    public int getLargestPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            return largestPoolSize;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns the approximate total number of tasks that have ever been scheduled
     * for execution. Because the states of tasks and threads may change dynamically
     * during computation, the returned value is only an approximation.
     *
     * @return the number of tasks
     */
    public long getTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for (Worker w : workers) {
                n += w.completedTasks;
                if (w.isLocked())
                    ++n;
            }
            return n + workQueue.size();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns the approximate total number of tasks that have completed execution.
     * Because the states of tasks and threads may change dynamically during
     * computation, the returned value is only an approximation, but one that does
     * not ever decrease across successive calls.
     *
     * @return the number of tasks
     */
    public long getCompletedTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for (Worker w : workers)
                n += w.completedTasks;
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns a string identifying this pool, as well as its state, including
     * indications of run state and estimated worker and task counts.
     *
     * @return a string identifying this pool, as well as its state
     */
    public String toString() {
        long ncompleted;
        int nworkers, nactive;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            ncompleted = completedTaskCount;
            nactive = 0;
            nworkers = workers.size();
            for (Worker w : workers) {
                ncompleted += w.completedTasks;
                if (w.isLocked())
                    ++nactive;
            }
        } finally {
            mainLock.unlock();
        }
        int c = ctl.get();
        String rs = (runStateLessThan(c, SHUTDOWN) ? "Running"
                : (runStateAtLeast(c, TERMINATED) ? "Terminated" : "Shutting down"));
        return super.toString() + "[" + rs + ", pool size = " + nworkers + ", active threads = " + nactive
                + ", queued tasks = " + workQueue.size() + ", completed tasks = " + ncompleted + "]";
    }

    /* Extension hooks */

    /**
     * Method invoked prior to executing the given Runnable in the given thread.
     * This method is invoked by thread {@code t} that will execute task {@code r},
     * and may be used to re-initialize ThreadLocals, or to perform logging.
     *
     * <p>
     * This implementation does nothing, but may be customized in subclasses. Note:
     * To properly nest multiple overridings, subclasses should generally invoke
     * {@code super.beforeExecute} at the end of this method.
     *
     * @param t the thread that will run task {@code r}
     * @param r the task that will be executed
     */
    protected void beforeExecute(Thread t, Runnable r) {
    }

    /**
     * Method invoked upon completion of execution of the given Runnable. This
     * method is invoked by the thread that executed the task. If non-null, the
     * Throwable is the uncaught {@code RuntimeException} or {@code Error} that
     * caused execution to terminate abruptly.
     *
     * <p>
     * This implementation does nothing, but may be customized in subclasses. Note:
     * To properly nest multiple overridings, subclasses should generally invoke
     * {@code super.afterExecute} at the beginning of this method.
     *
     * <p>
     * <b>Note:</b> When actions are enclosed in tasks (such as {@link FutureTask})
     * either explicitly or via methods such as {@code submit}, these task objects
     * catch and maintain computational exceptions, and so they do not cause abrupt
     * termination, and the internal exceptions are <em>not</em> passed to this
     * method. If you would like to trap both kinds of failures in this method, you
     * can further probe for such cases, as in this sample subclass that prints
     * either the direct cause or the underlying exception if a task has been
     * aborted:
     *
     * <pre>
     * {
     *     &#64;code
     *     class ExtendedExecutor extends ThreadPoolExecutor {
     *         // ...
     *         protected void afterExecute(Runnable r, Throwable t) {
     *             super.afterExecute(r, t);
     *             if (t == null && r instanceof Future<?>) {
     *                 try {
     *                     Object result = ((Future<?>) r).get();
     *                 } catch (CancellationException ce) {
     *                     t = ce;
     *                 } catch (ExecutionException ee) {
     *                     t = ee.getCause();
     *                 } catch (InterruptedException ie) {
     *                     Thread.currentThread().interrupt(); // ignore/reset
     *                 }
     *             }
     *             if (t != null)
     *                 System.out.println(t);
     *         }
     *     }
     * }
     * </pre>
     *
     * @param r the runnable that has completed
     * @param t the exception that caused termination, or null if execution
     *          completed normally
     */
    protected void afterExecute(Runnable r, Throwable t) {
    }

    /**
     * 这是一个钩子函数，只有线程池变成了TIDYING状态之后才会执行 Method invoked when the
     * Executor has terminated. Default implementation does nothing. Note: To
     * properly nest multiple overridings, subclasses should generally invoke
     * {@code super.terminated} within this method.
     */
    protected void terminated() {
    }

    /* Predefined RejectedExecutionHandlers */

    /**
     * 如果线程池拒绝该任务，那么让调用线程池的线程立即执行该任务，也就是不管你拒绝不拒绝，该任务必定会执行 
     * A handler for rejected tasks that runs the rejected task directly in the
     * calling thread of the {@code execute} method, unless the executor has been
     * shut down, in which case the task is discarded.
     */
    public static class CallerRunsPolicy implements RejectedExecutionHandler {
        public CallerRunsPolicy() {}
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                r.run();
            }
        }
    }
    /**
     * 默认的拒绝策略，任务被拒绝后抛RejectedExecutionException异常 
     * A handler for rejected tasks that throws a
     * {@code RejectedExecutionException}.
     */
    public static class AbortPolicy implements RejectedExecutionHandler {
        public AbortPolicy() {}
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            throw new RejectedExecutionException("Task " + r.toString() + " rejected from " + e.toString());
        }
    }
    /**
     * 任务被拒绝后啥事不干
     */
    public static class DiscardPolicy implements RejectedExecutionHandler {
        public DiscardPolicy() {}
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {}
    }
    /**
     * 任务被拒绝后，将任务队列等待最久的一个任务移除，并把当前被拒绝的任务添加到队列里面
     */
    public static class DiscardOldestPolicy implements RejectedExecutionHandler {
        public DiscardOldestPolicy() {}
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                e.getQueue().poll();
                e.execute(r);
            }
        }
    }
}