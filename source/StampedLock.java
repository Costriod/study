package java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.LockSupport;

/**
 * A capability-based lock with three modes for controlling read/write
 * access.  The state of a StampedLock consists of a version and mode.
 * Lock acquisition methods return a stamp that represents and
 * controls access with respect to a lock state; "try" versions of
 * these methods may instead return the special value zero to
 * represent failure to acquire access. Lock release and conversion
 * methods require stamps as arguments, and fail if they do not match
 * the state of the lock. The three modes are:
 *
 * <ul>
 *
 *  <li><b>Writing.</b> Method {@link #writeLock} possibly blocks
 *   waiting for exclusive access, returning a stamp that can be used
 *   in method {@link #unlockWrite} to release the lock. Untimed and
 *   timed versions of {@code tryWriteLock} are also provided. When
 *   the lock is held in write mode, no read locks may be obtained,
 *   and all optimistic read validations will fail.  </li>
 *
 *  <li><b>Reading.</b> Method {@link #readLock} possibly blocks
 *   waiting for non-exclusive access, returning a stamp that can be
 *   used in method {@link #unlockRead} to release the lock. Untimed
 *   and timed versions of {@code tryReadLock} are also provided. </li>
 *
 *  <li><b>Optimistic Reading.</b> Method {@link #tryOptimisticRead}
 *   returns a non-zero stamp only if the lock is not currently held
 *   in write mode. Method {@link #validate} returns true if the lock
 *   has not been acquired in write mode since obtaining a given
 *   stamp.  This mode can be thought of as an extremely weak version
 *   of a read-lock, that can be broken by a writer at any time.  The
 *   use of optimistic mode for short read-only code segments often
 *   reduces contention and improves throughput.  However, its use is
 *   inherently fragile.  Optimistic read sections should only read
 *   fields and hold them in local variables for later use after
 *   validation. Fields read while in optimistic mode may be wildly
 *   inconsistent, so usage applies only when you are familiar enough
 *   with data representations to check consistency and/or repeatedly
 *   invoke method {@code validate()}.  For example, such steps are
 *   typically required when first reading an object or array
 *   reference, and then accessing one of its fields, elements or
 *   methods. </li>
 *
 * </ul>
 *
 * <p>This class also supports methods that conditionally provide
 * conversions across the three modes. For example, method {@link
 * #tryConvertToWriteLock} attempts to "upgrade" a mode, returning
 * a valid write stamp if (1) already in writing mode (2) in reading
 * mode and there are no other readers or (3) in optimistic mode and
 * the lock is available. The forms of these methods are designed to
 * help reduce some of the code bloat that otherwise occurs in
 * retry-based designs.
 *
 * <p>StampedLocks are designed for use as internal utilities in the
 * development of thread-safe components. Their use relies on
 * knowledge of the internal properties of the data, objects, and
 * methods they are protecting.  They are not reentrant, so locked
 * bodies should not call other unknown methods that may try to
 * re-acquire locks (although you may pass a stamp to other methods
 * that can use or convert it).  The use of read lock modes relies on
 * the associated code sections being side-effect-free.  Unvalidated
 * optimistic read sections cannot call methods that are not known to
 * tolerate potential inconsistencies.  Stamps use finite
 * representations, and are not cryptographically secure (i.e., a
 * valid stamp may be guessable). Stamp values may recycle after (no
 * sooner than) one year of continuous operation. A stamp held without
 * use or validation for longer than this period may fail to validate
 * correctly.  StampedLocks are serializable, but always deserialize
 * into initial unlocked state, so they are not useful for remote
 * locking.
 *
 * <p>The scheduling policy of StampedLock does not consistently
 * prefer readers over writers or vice versa.  All "try" methods are
 * best-effort and do not necessarily conform to any scheduling or
 * fairness policy. A zero return from any "try" method for acquiring
 * or converting locks does not carry any information about the state
 * of the lock; a subsequent invocation may succeed.
 *
 * <p>Because it supports coordinated usage across multiple lock
 * modes, this class does not directly implement the {@link Lock} or
 * {@link ReadWriteLock} interfaces. However, a StampedLock may be
 * viewed {@link #asReadLock()}, {@link #asWriteLock()}, or {@link
 * #asReadWriteLock()} in applications requiring only the associated
 * set of functionality.
 *
 * <p><b>Sample Usage.</b> The following illustrates some usage idioms
 * in a class that maintains simple two-dimensional points. The sample
 * code illustrates some try/catch conventions even though they are
 * not strictly needed here because no exceptions can occur in their
 * bodies.<br>
 *
 *  <pre>{@code
 * class Point {
 *   private double x, y;
 *   private final StampedLock sl = new StampedLock();
 *
 *   void move(double deltaX, double deltaY) { // an exclusively locked method
 *     long stamp = sl.writeLock();
 *     try {
 *       x += deltaX;
 *       y += deltaY;
 *     } finally {
 *       sl.unlockWrite(stamp);
 *     }
 *   }
 *
 *   double distanceFromOrigin() { // A read-only method
 *     long stamp = sl.tryOptimisticRead();
 *     double currentX = x, currentY = y;
 *     if (!sl.validate(stamp)) {
 *        stamp = sl.readLock();
 *        try {
 *          currentX = x;
 *          currentY = y;
 *        } finally {
 *           sl.unlockRead(stamp);
 *        }
 *     }
 *     return Math.sqrt(currentX * currentX + currentY * currentY);
 *   }
 *
 *   void moveIfAtOrigin(double newX, double newY) { // upgrade
 *     // Could instead start with optimistic, not read mode
 *     long stamp = sl.readLock();
 *     try {
 *       while (x == 0.0 && y == 0.0) {
 *         long ws = sl.tryConvertToWriteLock(stamp);
 *         if (ws != 0L) {
 *           stamp = ws;
 *           x = newX;
 *           y = newY;
 *           break;
 *         }
 *         else {
 *           sl.unlockRead(stamp);
 *           stamp = sl.writeLock();
 *         }
 *       }
 *     } finally {
 *       sl.unlock(stamp);
 *     }
 *   }
 * }}</pre>
 *
 * @since 1.8
 * @author Doug Lea
 */
public class StampedLock implements java.io.Serializable {
    /*
     * Algorithmic notes:
     *
     * The design employs elements of Sequence locks
     * (as used in linux kernels; see Lameter's
     * http://www.lameter.com/gelato2005.pdf
     * and elsewhere; see
     * Boehm's http://www.hpl.hp.com/techreports/2012/HPL-2012-68.html)
     * and Ordered RW locks (see Shirako et al
     * http://dl.acm.org/citation.cfm?id=2312015)
     *
     * Conceptually, the primary state of the lock includes a sequence
     * number that is odd when write-locked and even otherwise.
     * However, this is offset by a reader count that is non-zero when
     * read-locked.  The read count is ignored when validating
     * "optimistic" seqlock-reader-style stamps.  Because we must use
     * a small finite number of bits (currently 7) for readers, a
     * supplementary reader overflow word is used when the number of
     * readers exceeds the count field. We do this by treating the max
     * reader count value (RBITS) as a spinlock protecting overflow
     * updates.
     *
     * Waiters use a modified form of CLH lock used in
     * AbstractQueuedSynchronizer (see its internal documentation for
     * a fuller account), where each node is tagged (field mode) as
     * either a reader or writer. Sets of waiting readers are grouped
     * (linked) under a common node (field cowait) so act as a single
     * node with respect to most CLH mechanics.  By virtue of the
     * queue structure, wait nodes need not actually carry sequence
     * numbers; we know each is greater than its predecessor.  This
     * simplifies the scheduling policy to a mainly-FIFO scheme that
     * incorporates elements of Phase-Fair locks (see Brandenburg &
     * Anderson, especially http://www.cs.unc.edu/~bbb/diss/).  In
     * particular, we use the phase-fair anti-barging rule: If an
     * incoming reader arrives while read lock is held but there is a
     * queued writer, this incoming reader is queued.  (This rule is
     * responsible for some of the complexity of method acquireRead,
     * but without it, the lock becomes highly unfair.) Method release
     * does not (and sometimes cannot) itself wake up cowaiters. This
     * is done by the primary thread, but helped by any other threads
     * with nothing better to do in methods acquireRead and
     * acquireWrite.
     *
     * These rules apply to threads actually queued. All tryLock forms
     * opportunistically try to acquire locks regardless of preference
     * rules, and so may "barge" their way in.  Randomized spinning is
     * used in the acquire methods to reduce (increasingly expensive)
     * context switching while also avoiding sustained memory
     * thrashing among many threads.  We limit spins to the head of
     * queue. A thread spin-waits up to SPINS times (where each
     * iteration decreases spin count with 50% probability) before
     * blocking. If, upon wakening it fails to obtain lock, and is
     * still (or becomes) the first waiting thread (which indicates
     * that some other thread barged and obtained lock), it escalates
     * spins (up to MAX_HEAD_SPINS) to reduce the likelihood of
     * continually losing to barging threads.
     *
     * Nearly all of these mechanics are carried out in methods
     * acquireWrite and acquireRead, that, as typical of such code,
     * sprawl out because actions and retries rely on consistent sets
     * of locally cached reads.
     *
     * As noted in Boehm's paper (above), sequence validation (mainly
     * method validate()) requires stricter ordering rules than apply
     * to normal volatile reads (of "state").  To force orderings of
     * reads before a validation and the validation itself in those
     * cases where this is not already forced, we use
     * Unsafe.loadFence.
     *
     * The memory layout keeps lock state and queue pointers together
     * (normally on the same cache line). This usually works well for
     * read-mostly loads. In most other cases, the natural tendency of
     * adaptive-spin CLH locks to reduce memory contention lessens
     * motivation to further spread out contended locations, but might
     * be subject to future improvements.
     */

    private static final long serialVersionUID = -6001602636862214147L;

    /** CPU核心数 */
    private static final int NCPU = Runtime.getRuntime().availableProcessors();

    /** 最大自旋等待时间，如果这个时间内没有获取锁，则进入等待队列 */
    private static final int SPINS = (NCPU > 1) ? 1 << 6 : 0;

    /** 头节点自旋最大等待时间，如果这个时间内没有获取锁，则会阻塞 */
    private static final int HEAD_SPINS = (NCPU > 1) ? 1 << 10 : 0;

    /** 头节点再次进入阻塞的最大自旋时间 */
    private static final int MAX_HEAD_SPINS = (NCPU > 1) ? 1 << 16 : 0;

    /** The period for yielding when waiting for overflow spinlock */
    private static final int OVERFLOW_YIELD_RATE = 7; // must be power 2 - 1

    /** The number of bits to use for reader count before overflowing */
    private static final int LG_READERS = 7;

    // Values for lock state and stamp operations
    /**0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0001 表示一个单位读锁*/
    private static final long RUNIT = 1L;
    /**0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 1000 0000 写锁标记位，即第8位为1*/
    private static final long WBIT  = 1L << LG_READERS;
    /**0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0111 1111 读锁掩码，通过后7位&操作*/
    private static final long RBITS = WBIT - 1L;
    /**0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0111 1110 最大读锁数量，126*/
    private static final long RFULL = RBITS - 1L;
    /**0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 1111 1111 读写锁掩码。通过后8位&操作*/
    private static final long ABITS = RBITS | WBIT;
    /**1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1000 0000 ABITS按位取反，包括符号位*/
    private static final long SBITS = ~RBITS;

    /**0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0001 0000 0000 128左移1位，即256*/
    private static final long ORIGIN = WBIT << 1;      // 

    // Special value from cancelled acquire methods so caller can throw IE
    private static final long INTERRUPTED = 1L;

    // WNode的状态; order matters
    private static final int WAITING   = -1;
    private static final int CANCELLED =  1;

    // WNode类型 (int not boolean to allow arithmetic)
    private static final int RMODE = 0;
    private static final int WMODE = 1;

    /** Wait nodes */
    static final class WNode {
        volatile WNode prev;      //如果是WRITE_MODE，则cowait为null
        volatile WNode next;
        volatile WNode cowait;    // 用于保存读线程，如果是READ_MODE，则新的节点会添加到cowait下面组成一个单向链表
        volatile Thread thread;   // non-null while possibly parked
        volatile int status;      // 0, WAITING, or CANCELLED
        final int mode;           // RMODE or WMODE
        WNode(int m, WNode p) { mode = m; prev = p; }
    }

    /** Head of CLH queue */
    private transient volatile WNode whead;
    /** Tail (last) of CLH queue */
    private transient volatile WNode wtail;

    // views
    transient ReadLockView readLockView;
    transient WriteLockView writeLockView;
    transient ReadWriteLockView readWriteLockView;

    /** Lock sequence/state */
    private transient volatile long state;
    /** 如果读锁数量超出了126则需要用readerOverflow这个字段来存储，初始值0 */
    private transient int readerOverflow;

    /**
     * 构造函数，初始化一个state为256
     */
    public StampedLock() {
        state = ORIGIN;
    }

    /**
     * Exclusively acquires the lock, blocking if necessary
     * until available.
     * 获取独占锁，也就是写锁，会一直阻塞直到获取锁
     *
     * @return a stamp that can be used to unlock or convert mode
     */
    public long writeLock() {
        long s, next;  // bypass acquireWrite in fully unlocked case only
        return ((((s = state) & ABITS) == 0L && //如果state为256,并且通过CAS设置next为128成功则返回next即可，否则通过acquireWrite(false, 0L)获取锁
                 U.compareAndSwapLong(this, STATE, s, next = s + WBIT)) ?
                next : acquireWrite(false, 0L));
    }

    /**
     * Exclusively acquires the lock if it is immediately available.
     * 获取独占锁，也就是写锁，如果获取失败则立即返回0，否则返回128
     * @return a stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     */
    public long tryWriteLock() {
        long s, next;
        return ((((s = state) & ABITS) == 0L &&
                 U.compareAndSwapLong(this, STATE, s, next = s + WBIT)) ?
                next : 0L);
    }

    /**
     * Exclusively acquires the lock if it is available within the
     * given time and the current thread has not been interrupted.
     * Behavior under timeout and interruption matches that specified
     * for method {@link Lock#tryLock(long,TimeUnit)}.
     *
     * @param time the maximum time to wait for the lock
     * @param unit the time unit of the {@code time} argument
     * @return a stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     * @throws InterruptedException if the current thread is interrupted
     * before acquiring the lock
     */
    public long tryWriteLock(long time, TimeUnit unit)
        throws InterruptedException {
        long nanos = unit.toNanos(time);
        if (!Thread.interrupted()) {
            long next, deadline;
            if ((next = tryWriteLock()) != 0L)
                return next;
            if (nanos <= 0L)
                return 0L;
            if ((deadline = System.nanoTime() + nanos) == 0L)
                deadline = 1L;
            if ((next = acquireWrite(true, deadline)) != INTERRUPTED)
                return next;
        }
        throw new InterruptedException();
    }

    /**
     * Exclusively acquires the lock, blocking if necessary
     * until available or the current thread is interrupted.
     * Behavior under interruption matches that specified
     * for method {@link Lock#lockInterruptibly()}.
     *
     * @return a stamp that can be used to unlock or convert mode
     * @throws InterruptedException if the current thread is interrupted
     * before acquiring the lock
     */
    public long writeLockInterruptibly() throws InterruptedException {
        long next;
        if (!Thread.interrupted() &&
            (next = acquireWrite(true, 0L)) != INTERRUPTED)
            return next;
        throw new InterruptedException();
    }

    /**
     * Non-exclusively acquires the lock, blocking if necessary
     * until available.
     * 获取读锁，也就是非独占锁，会阻塞到获取锁为止
     * @return a stamp that can be used to unlock or convert mode
     */
    public long readLock() {
        long s = state, next;  // bypass acquireRead on common uncontended case
        return ((whead == wtail && (s & ABITS) < RFULL && //如果CLH的头节点和尾节点相同并且读锁数量小于126
                 U.compareAndSwapLong(this, STATE, s, next = s + RUNIT)) ?//读锁数量加1成功
                next : acquireRead(false, 0L));
    }

    /**
     * Non-exclusively acquires the lock if it is immediately available.
     * 获取读锁，如果锁数量小于126，则直接读锁加1并返回
     * 如果数量大于126，则使用readerOverflow累加进行计数，并返回state，state后8位bit位值为126
     *
     * @return a stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     */
    public long tryReadLock() {
        for (;;) {//无限循环
            long s, m, next;
            if ((m = (s = state) & ABITS) == WBIT)//如果已经是写锁状态
                return 0L;
            else if (m < RFULL) {//如果读锁数量小于126
                if (U.compareAndSwapLong(this, STATE, s, next = s + RUNIT))//如果读锁数量加1成功
                    return next;
            }
            else if ((next = tryIncReaderOverflow(s)) != 0L)//如果越界了，或者运行过程中其他线程释放读锁导致小于126
                return next;
        }
    }

    /**
     * Non-exclusively acquires the lock if it is available within the
     * given time and the current thread has not been interrupted.
     * Behavior under timeout and interruption matches that specified
     * for method {@link Lock#tryLock(long,TimeUnit)}.
     *
     * @param time the maximum time to wait for the lock
     * @param unit the time unit of the {@code time} argument
     * @return a stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     * @throws InterruptedException if the current thread is interrupted
     * before acquiring the lock
     */
    public long tryReadLock(long time, TimeUnit unit)
        throws InterruptedException {
        long s, m, next, deadline;
        long nanos = unit.toNanos(time);
        if (!Thread.interrupted()) {
            if ((m = (s = state) & ABITS) != WBIT) {
                if (m < RFULL) {
                    if (U.compareAndSwapLong(this, STATE, s, next = s + RUNIT))
                        return next;
                }
                else if ((next = tryIncReaderOverflow(s)) != 0L)
                    return next;
            }
            if (nanos <= 0L)
                return 0L;
            if ((deadline = System.nanoTime() + nanos) == 0L)
                deadline = 1L;
            if ((next = acquireRead(true, deadline)) != INTERRUPTED)
                return next;
        }
        throw new InterruptedException();
    }

    /**
     * Non-exclusively acquires the lock, blocking if necessary
     * until available or the current thread is interrupted.
     * Behavior under interruption matches that specified
     * for method {@link Lock#lockInterruptibly()}.
     *
     * @return a stamp that can be used to unlock or convert mode
     * @throws InterruptedException if the current thread is interrupted
     * before acquiring the lock
     */
    public long readLockInterruptibly() throws InterruptedException {
        long next;
        if (!Thread.interrupted() &&
            (next = acquireRead(true, 0L)) != INTERRUPTED)
            return next;
        throw new InterruptedException();
    }

    /**
     * Returns a stamp that can later be validated, or zero
     * if exclusively locked.
     * 获取乐观读锁，此时如果已经有其他线程获取了写锁则返回0，否则返回s & SBITS
     * @return a stamp, or zero if exclusively locked
     */
    public long tryOptimisticRead() {
        long s;
        return (((s = state) & WBIT) == 0L) ? (s & SBITS) : 0L;
    }

    /**
     * Returns true if the lock has not been exclusively acquired
     * since issuance of the given stamp. Always returns false if the
     * stamp is zero. Always returns true if the stamp represents a
     * currently held lock. Invoking this method with a value not
     * obtained from {@link #tryOptimisticRead} or a locking method
     * for this lock has no defined effect or result.
     * 如果生成了这个stamp之后没有其他线程获取到写锁，那么返回true，否则返回false
     *
     * @param stamp a stamp
     * @return {@code true} if the lock has not been exclusively acquired
     * since issuance of the given stamp; else false
     */
    public boolean validate(long stamp) {
        U.loadFence();//执行这一个步骤的时候强制让CPU的临时缓存数据写入内存
        return (stamp & SBITS) == (state & SBITS);
    }

    /**
     * If the lock state matches the given stamp, releases the
     * exclusive lock.
     * 释放写锁，然后唤醒head.next，如果传进来的stamp不等于state或者stamp & WBIT为0，则抛出异常
     * 因为写锁是独占的，在获取写锁之后其他线程不允许获取锁，所以获取写锁后返回的stamp就是state字段或者(stamp & WBIT) == 0
     * @param stamp a stamp returned by a write-lock operation
     * @throws IllegalMonitorStateException if the stamp does
     * not match the current state of this lock
     */
    public void unlockWrite(long stamp) {
        WNode h;
        if (state != stamp || (stamp & WBIT) == 0L)//如果传进来的stamp不等于state或者stamp & WBIT为0，则抛出异常
            throw new IllegalMonitorStateException();
        state = (stamp += WBIT) == 0L ? ORIGIN : stamp;
        if ((h = whead) != null && h.status != 0)
            release(h);
    }

    /**
     * If the lock state matches the given stamp, releases the
     * non-exclusive lock.
     *
     * @param stamp a stamp returned by a read-lock operation
     * @throws IllegalMonitorStateException if the stamp does
     * not match the current state of this lock
     */
    public void unlockRead(long stamp) {
        long s, m; WNode h;
        for (;;) {
            if (((s = state) & SBITS) != (stamp & SBITS) ||
                (stamp & ABITS) == 0L || (m = s & ABITS) == 0L || m == WBIT)
                throw new IllegalMonitorStateException();
            if (m < RFULL) {//前面一个if语句所有条件都会执行到，到这里m等于state
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)//读锁只有一个的情况，此刻释放之后就需要唤醒下一个节点
                        release(h);
                    break;
                }
            }
            else if (tryDecReaderOverflow(s) != 0L)//如果是那种读锁超过126个的情况
                break;
        }
    }

    /**
     * If the lock state matches the given stamp, releases the
     * corresponding mode of the lock.
     * 这个方法就是unlockWrite和unlockRead的组合
     *
     * @param stamp a stamp returned by a lock operation
     * @throws IllegalMonitorStateException if the stamp does
     * not match the current state of this lock
     */
    public void unlock(long stamp) {
        long a = stamp & ABITS, m, s; WNode h;
        //因为SBITS后8位是 1000 0000，其中最高位表示写锁掩码，低7位表示读锁掩码，写锁情况下stamp等于state，但是读锁情况下stamp不一定等于state，但一定满足(state & SBITS) == (stamp & SBITS)
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            if ((m = s & ABITS) == 0L)//没有任何线程获取到锁
                break;
            else if (m == WBIT) {//刚好获取到写锁
                if (a != m)
                    break;
                state = (s += WBIT) == 0L ? ORIGIN : s;//如果s为负数，则重置state，否则返回累加后的s
                if ((h = whead) != null && h.status != 0)
                    release(h);
                return;
            }
            else if (a == 0L || a >= WBIT)
                break;
            else if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    return;
                }
            }
            else if (tryDecReaderOverflow(s) != 0L)
                return;
        }
        throw new IllegalMonitorStateException();
    }

    /**
     * If the lock state matches the given stamp, performs one of
     * the following actions. If the stamp represents holding a write
     * lock, returns it.  Or, if a read lock, if the write lock is
     * available, releases the read lock and returns a write stamp.
     * Or, if an optimistic read, returns a write stamp only if
     * immediately available. This method returns zero in all other
     * cases.
     *
     * @param stamp a stamp
     * @return a valid write stamp, or zero on failure
     */
    public long tryConvertToWriteLock(long stamp) {
        long a = stamp & ABITS, m, s, next;
        //state匹配stamp
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            if ((m = s & ABITS) == 0L) {//没有锁
                if (a != 0L)
                    break;
                if (U.compareAndSwapLong(this, STATE, s, next = s + WBIT))//CAS修改状态为持有写锁，并返回
                    return next;
            }
            else if (m == WBIT) {//持有写锁
                if (a != m)//其他线程持有写锁
                    break;
                return stamp;//当前线程已经持有写锁
            }
            else if (m == RUNIT && a != 0L) {//有一个读锁
                if (U.compareAndSwapLong(this, STATE, s,
                                         next = s - RUNIT + WBIT))//释放读锁，并尝试持有写锁
                    return next;
            }
            else
                break;
        }
        return 0L;
    }

    /**
     * If the lock state matches the given stamp, performs one of
     * the following actions. If the stamp represents holding a write
     * lock, releases it and obtains a read lock.  Or, if a read lock,
     * returns it. Or, if an optimistic read, acquires a read lock and
     * returns a read stamp only if immediately available. This method
     * returns zero in all other cases.
     *
     * @param stamp a stamp
     * @return a valid read stamp, or zero on failure
     */
    public long tryConvertToReadLock(long stamp) {
        long a = stamp & ABITS, m, s, next; WNode h;
        while (((s = state) & SBITS) == (stamp & SBITS)) {//state匹配stamp
            if ((m = s & ABITS) == 0L) {//没有锁
                if (a != 0L)
                    break;
                else if (m < RFULL) {
                    if (U.compareAndSwapLong(this, STATE, s, next = s + RUNIT))
                        return next;
                }
                else if ((next = tryIncReaderOverflow(s)) != 0L)
                    return next;
            }
            else if (m == WBIT) {//有写锁
                if (a != m)//其他线程持有写锁
                    break;
                state = next = s + (WBIT + RUNIT);//释放写锁持有读锁
                if ((h = whead) != null && h.status != 0)
                    release(h);
                return next;
            }
            else if (a != 0L && a < WBIT)//本身持有读锁
                return stamp;
            else
                break;
        }
        return 0L;
    }

    /**
     * If the lock state matches the given stamp then, if the stamp
     * represents holding a lock, releases it and returns an
     * observation stamp.  Or, if an optimistic read, returns it if
     * validated. This method returns zero in all other cases, and so
     * may be useful as a form of "tryUnlock".
     *
     * @param stamp a stamp
     * @return a valid optimistic read stamp, or zero on failure
     */
    public long tryConvertToOptimisticRead(long stamp) {
        long a = stamp & ABITS, m, s, next; WNode h;
        U.loadFence();
        for (;;) {
            if (((s = state) & SBITS) != (stamp & SBITS))//中途有其他线程获取到了写锁直接退出循环
                break;
            if ((m = s & ABITS) == 0L) {//当前没有写锁，直接返回s，这里的s = state
                if (a != 0L)//到这里有其他线程获取到了写锁直接退出循环
                    break;
                return s;
            }
            else if (m == WBIT) {//有写锁
                if (a != m)//其他线程有写锁
                    break;
                state = next = (s += WBIT) == 0L ? ORIGIN : s;//释放写锁
                if ((h = whead) != null && h.status != 0)//唤醒后续节点
                    release(h);
                return next;
            }
            else if (a == 0L || a >= WBIT)//当前没有任何锁或者有写锁
                break;
            else if (m < RFULL) {//读锁未超出126个
                if (U.compareAndSwapLong(this, STATE, s, next = s - RUNIT)) {//释放读锁
                    if (m == RUNIT && (h = whead) != null && h.status != 0)//唤醒下一个节点
                        release(h);
                    return next & SBITS;//到这里返回的就是0了，看不懂
                }
            }
            else if ((next = tryDecReaderOverflow(s)) != 0L)
                return next & SBITS;
        }
        return 0L;
    }

    /**
     * Releases the write lock if it is held, without requiring a
     * stamp value. This method may be useful for recovery after
     * errors.
     *
     * @return {@code true} if the lock was held, else false
     */
    public boolean tryUnlockWrite() {
        long s; WNode h;
        if (((s = state) & WBIT) != 0L) {
            state = (s += WBIT) == 0L ? ORIGIN : s;
            if ((h = whead) != null && h.status != 0)
                release(h);
            return true;
        }
        return false;
    }

    /**
     * Releases one hold of the read lock if it is held, without
     * requiring a stamp value. This method may be useful for recovery
     * after errors.
     *
     * @return {@code true} if the read lock was held, else false
     */
    public boolean tryUnlockRead() {
        long s, m; WNode h;
        while ((m = (s = state) & ABITS) != 0L && m < WBIT) {
            if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    return true;
                }
            }
            else if (tryDecReaderOverflow(s) != 0L)
                return true;
        }
        return false;
    }

    // status monitoring methods

    /**
     * Returns combined state-held and overflow read count for given
     * state s.
     */
    private int getReadLockCount(long s) {
        long readers;
        if ((readers = s & RBITS) >= RFULL)
            readers = RFULL + readerOverflow;
        return (int) readers;
    }

    /**
     * Returns {@code true} if the lock is currently held exclusively.
     *
     * @return {@code true} if the lock is currently held exclusively
     */
    public boolean isWriteLocked() {
        return (state & WBIT) != 0L;
    }

    /**
     * Returns {@code true} if the lock is currently held non-exclusively.
     *
     * @return {@code true} if the lock is currently held non-exclusively
     */
    public boolean isReadLocked() {
        return (state & RBITS) != 0L;
    }

    /**
     * Queries the number of read locks held for this lock. This
     * method is designed for use in monitoring system state, not for
     * synchronization control.
     * @return the number of read locks held
     */
    public int getReadLockCount() {
        return getReadLockCount(state);
    }

    /**
     * Returns a string identifying this lock, as well as its lock
     * state.  The state, in brackets, includes the String {@code
     * "Unlocked"} or the String {@code "Write-locked"} or the String
     * {@code "Read-locks:"} followed by the current number of
     * read-locks held.
     *
     * @return a string identifying this lock, as well as its lock state
     */
    public String toString() {
        long s = state;
        return super.toString() +
            ((s & ABITS) == 0L ? "[Unlocked]" :
             (s & WBIT) != 0L ? "[Write-locked]" :
             "[Read-locks:" + getReadLockCount(s) + "]");
    }

    // views

    /**
     * Returns a plain {@link Lock} view of this StampedLock in which
     * the {@link Lock#lock} method is mapped to {@link #readLock},
     * and similarly for other methods. The returned Lock does not
     * support a {@link Condition}; method {@link
     * Lock#newCondition()} throws {@code
     * UnsupportedOperationException}.
     *
     * @return the lock
     */
    public Lock asReadLock() {
        ReadLockView v;
        return ((v = readLockView) != null ? v :
                (readLockView = new ReadLockView()));
    }

    /**
     * Returns a plain {@link Lock} view of this StampedLock in which
     * the {@link Lock#lock} method is mapped to {@link #writeLock},
     * and similarly for other methods. The returned Lock does not
     * support a {@link Condition}; method {@link
     * Lock#newCondition()} throws {@code
     * UnsupportedOperationException}.
     *
     * @return the lock
     */
    public Lock asWriteLock() {
        WriteLockView v;
        return ((v = writeLockView) != null ? v :
                (writeLockView = new WriteLockView()));
    }

    /**
     * Returns a {@link ReadWriteLock} view of this StampedLock in
     * which the {@link ReadWriteLock#readLock()} method is mapped to
     * {@link #asReadLock()}, and {@link ReadWriteLock#writeLock()} to
     * {@link #asWriteLock()}.
     *
     * @return the lock
     */
    public ReadWriteLock asReadWriteLock() {
        ReadWriteLockView v;
        return ((v = readWriteLockView) != null ? v :
                (readWriteLockView = new ReadWriteLockView()));
    }

    // view classes

    final class ReadLockView implements Lock {
        public void lock() { readLock(); }
        public void lockInterruptibly() throws InterruptedException {
            readLockInterruptibly();
        }
        public boolean tryLock() { return tryReadLock() != 0L; }
        public boolean tryLock(long time, TimeUnit unit)
            throws InterruptedException {
            return tryReadLock(time, unit) != 0L;
        }
        public void unlock() { unstampedUnlockRead(); }
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    final class WriteLockView implements Lock {
        public void lock() { writeLock(); }
        public void lockInterruptibly() throws InterruptedException {
            writeLockInterruptibly();
        }
        public boolean tryLock() { return tryWriteLock() != 0L; }
        public boolean tryLock(long time, TimeUnit unit)
            throws InterruptedException {
            return tryWriteLock(time, unit) != 0L;
        }
        public void unlock() { unstampedUnlockWrite(); }
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    final class ReadWriteLockView implements ReadWriteLock {
        public Lock readLock() { return asReadLock(); }
        public Lock writeLock() { return asWriteLock(); }
    }

    // Unlock methods without stamp argument checks for view classes.
    // Needed because view-class lock methods throw away stamps.

    final void unstampedUnlockWrite() {
        WNode h; long s;
        if (((s = state) & WBIT) == 0L)
            throw new IllegalMonitorStateException();
        state = (s += WBIT) == 0L ? ORIGIN : s;
        if ((h = whead) != null && h.status != 0)
            release(h);
    }

    final void unstampedUnlockRead() {
        for (;;) {
            long s, m; WNode h;
            if ((m = (s = state) & ABITS) == 0L || m >= WBIT)
                throw new IllegalMonitorStateException();
            else if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    break;
                }
            }
            else if (tryDecReaderOverflow(s) != 0L)
                break;
        }
    }

    private void readObject(java.io.ObjectInputStream s)
        throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();
        state = ORIGIN; // reset to unlocked state
    }

    // internals

    /**
     * Tries to increment readerOverflow by first setting state
     * access bits value to RBITS, indicating hold of spinlock,
     * then updating, then releasing.
     * 返回一个新的stamp，否则0
     * 在读锁数量超过126的时候，使用一个readerOverflow来计数，并且返回state本身，但是如果运行期间其他线程释放读锁导致数量小于126，则返回0
     *
     * @param s a reader overflow stamp: (s & ABITS) >= RFULL
     * @return new stamp on success, else zero
     */
    private long tryIncReaderOverflow(long s) {
        // assert (s & ABITS) >= RFULL;
        if ((s & ABITS) == RFULL) {//如果此刻(s & ABITS) == RFULL表示读锁数量达到126，这里就需要用一个readerOverflow累加计数
            if (U.compareAndSwapLong(this, STATE, s, s | RBITS)) {//这里CAS操作成功修改了state的值
                ++readerOverflow;//readerOverflow累加
                state = s;//再重新把原来的值赋给state
                return s;
            }
        }
        else if ((LockSupport.nextSecondarySeed() &
                  OVERFLOW_YIELD_RATE) == 0)
            Thread.yield();
        return 0L;//到这一步就有可能是运行期间其他线程读锁释放，然后数量小于126的情况，所以这里要返回0
    }

    /**
     * Tries to decrement readerOverflow.
     * 返回一个新的stamp，否则0
     * 在读锁数量超过126的时候，readerOverflow减1，并且返回传入的stamp本身
     * 读锁数量恰好126的时候，读锁减1，并返回新的stamp
     * 但是如果运行期间其他线程释放了读锁导致数量小于126，则返回0
     * @param s a reader overflow stamp: (s & ABITS) >= RFULL
     * @return new stamp on success, else zero
     */
    private long tryDecReaderOverflow(long s) {
        // assert (s & ABITS) >= RFULL;
        if ((s & ABITS) == RFULL) {//如果此刻(s & ABITS) == RFULL表示读锁数量达到126
            if (U.compareAndSwapLong(this, STATE, s, s | RBITS)) {//这里CAS操作成功修改了state的值
                int r; long next;
                if ((r = readerOverflow) > 0) {//如果readerOverflow>0说明越界了
                    readerOverflow = r - 1;//此时readerOverflow减1
                    next = s;
                }
                else //如果恰好126个，则只需要s减1即可
                    next = s - RUNIT;
                 state = next;
                 return next;//返回新的stamp
            }
        }
        else if ((LockSupport.nextSecondarySeed() &
                  OVERFLOW_YIELD_RATE) == 0)
            Thread.yield();
        return 0L;
    }

    /**
     * 唤醒head之后的一个线程，如果head后一个node是CANCELLED状态或者为null，则从尾部往前找，直到找到第一个为WAITING的节点为止
     * Wakes up the successor of h (normally whead). This is normally
     * just h.next, but may require traversal from wtail if next
     * pointers are lagging. This may fail to wake up an acquiring
     * thread when one or more have been cancelled, but the cancel
     * methods themselves provide extra safeguards to ensure liveness.
     */
    private void release(WNode h) {
        if (h != null) {//如果head不为null
            WNode q; Thread w;
            U.compareAndSwapInt(h, WSTATUS, WAITING, 0);//尝试head.status设为WAITING
            if ((q = h.next) == null || q.status == CANCELLED) {//如果head.next为CANCELLED状态或者为null
                for (WNode t = wtail; t != null && t != h; t = t.prev)//从尾部往前找，直到找到第一个为WAITING的节点为止
                    if (t.status <= 0)
                        q = t;
            }
            if (q != null && (w = q.thread) != null)
                U.unpark(w);//唤醒这个线程
        }
    }

    /**
     * 尝试自旋的获取写锁, 获取不到则阻塞线程
     *
     * @param interruptible true 表示检测中断, 如果线程被中断过, 则最终返回INTERRUPTED
     * 
     * @param deadline 如果非0，则表示限时获取（单位纳秒）
     * @return 非0表示获取成功, INTERRUPTED表示中途被中断过
     */
    private long acquireWrite(boolean interruptible, long deadline) {
        WNode node = null, p;
        /*
         * 自旋入队操作
         * 如果没有任何锁被占用, 则立即尝试获取写锁, 获取成功则返回.
         * 如果存在锁被使用, 则将当前线程包装成独占结点, 并插入等待队列尾部
         */
        for (int spins = -1;;) { // spin while enqueuing
            long m, s, ns;
            if ((m = (s = state) & ABITS) == 0L) {//没有任何线程获取到锁的情况
                if (U.compareAndSwapLong(this, STATE, s, ns = s + WBIT))//CAS操作获取写锁
                    return ns;
            }
            else if (spins < 0)
                spins = (m == WBIT && wtail == whead) ? SPINS : 0;//如果其他线程获取了写锁，并且CLH队列头尾指向同一个地址
            else if (spins > 0) {// 写锁被其他线程占用,以随机方式探测是否要退出自旋
                if (LockSupport.nextSecondarySeed() >= 0)
                    --spins;
            }
            else if ((p = wtail) == null) { //初始化等待队列，如果wtail为null表明队列一定为空
                WNode hd = new WNode(WMODE, null);//构造一个WRITE_MODE对象
                if (U.compareAndSwapObject(this, WHEAD, null, hd))//该对象设为whead对象
                    wtail = hd;//wtail也指向这个对象
            }
            else if (node == null)//如果前面条件不满足，即wtail!=null，则此刻创建新的WNode，并且新节点prev指向wtail
                node = new WNode(WMODE, p);
            else if (node.prev != p)
                node.prev = p;
            else if (U.compareAndSwapObject(this, WTAIL, p, node)) {//设置wtail指向新的node
                p.next = node;//设置原来的wtail.next指向新的node
                break;
            }
        }

        for (int spins = -1;;) {
            WNode h, np, pp; int ps;
            if ((h = whead) == p) {// 如果当前p结点是队首结点, 则立即尝试获取写锁
                if (spins < 0)
                    spins = HEAD_SPINS;
                else if (spins < MAX_HEAD_SPINS)
                    spins <<= 1;
                for (int k = spins;;) { // spin at head
                    long s, ns;
                    if (((s = state) & ABITS) == 0L) {//没有任何线程获取到写锁
                        if (U.compareAndSwapLong(this, STATE, s,
                                                 ns = s + WBIT)) {
                            whead = node;// whead指向新的node，原来的head结点从队列移除
                            node.prev = null;//node.prev指向null
                            return ns;
                        }
                    }
                    else if (LockSupport.nextSecondarySeed() >= 0 &&
                             --k <= 0)
                        break;
                }
            }
            else if (h != null) { // 如果head节点不为空
                WNode c; Thread w;
                while ((c = h.cowait) != null) {//如果head节点的cowait属性不为空
                    if (U.compareAndSwapObject(h, WCOWAIT, c, c.cowait) &&//利用CAS重置head.cowait = head.cowait.cowait
                        (w = c.thread) != null)
                        U.unpark(w);//挨个唤醒读线程
                }
            }
            if (whead == h) {//如果此刻head节点没变化
                if ((np = node.prev) != p) {
                    if (np != null)//node.prev不是p对象的情况，只有可能是原来的p对象cancel了，所以node.prev指向了p.prev
                        (p = np).next = node;   // stale
                }
                else if ((ps = p.status) == 0)//如果status为初始状态0
                    U.compareAndSwapInt(p, WSTATUS, 0, WAITING);//设置为WAITING
                else if (ps == CANCELLED) {//如果status是CANCELLED状态
                    if ((pp = p.prev) != null) {//剔除掉当前队列的p节点，也就是node.prev指向了p.prev
                        node.prev = pp;
                        pp.next = node;
                    }
                }
                else {
                    long time; // 0 argument to park means no timeout
                    if (deadline == 0L)//如果deadline为0表示一直阻塞直到获取锁为止
                        time = 0L;
                    else if ((time = deadline - System.nanoTime()) <= 0L)//如果等待时间超时，则取消该等待节点
                        return cancelWaiter(node, node, false);
                    Thread wt = Thread.currentThread();
                    U.putObject(wt, PARKBLOCKER, this);
                    node.thread = wt;
                    if (p.status < 0 && (p != h || (state & ABITS) != 0L) &&
                        whead == h && node.prev == p)
                        U.park(false, time);  // 阻塞当前线程一段时间，等待后面其他线程唤醒
                    node.thread = null;
                    U.putObject(wt, PARKBLOCKER, null);
                    if (interruptible && Thread.interrupted())
                        return cancelWaiter(node, node, true);
                }
            }
        }
    }

    /**
         * 自旋入队操作
         * 如果写锁未被占用, 则立即尝试获取读锁, 获取成功则返回.
         * 如果写锁被占用, 则将当前读线程包装成结点, 并插入等待队列（如果队尾是WMODE结点,直接链接到队尾;如果队尾是RMODE结点,则连接到RMODE节点的cowait里面）
     *
     * @param interruptible true 表示检测中断, 如果线程被中断过, 则最终返回INTERRUPTED
     * 
     * @param deadline 如果非0，则表示限时获取（单位纳秒）
     * @return 非0表示获取成功, INTERRUPTED表示中途被中断过
     */
    private long acquireRead(boolean interruptible, long deadline) {
        WNode node = null, p;
        /**
         * 自旋入队操作
         * 如果写锁未被占用, 则立即尝试获取读锁, 获取成功则返回.
         * 如果写锁被占用, 则将当前读线程包装成结点, 并插入等待队列（如果队尾是WMODE结点,直接链接到队尾;如果队尾是RMODE结点,则连接到RMODE节点的cowait里面）
         */
        for (int spins = -1;;) {
            WNode h;
            if ((h = whead) == (p = wtail)) {// 如果队列为空或只有头结点, 则会立即尝试获取读锁
                for (long m, s, ns;;) {
                    if ((m = (s = state) & ABITS) < RFULL ?// 判断写锁是否被占用
                        U.compareAndSwapLong(this, STATE, s, ns = s + RUNIT) ://写锁未占用,且读锁数量未超限, 则更新同步状态
                        (m < WBIT && (ns = tryIncReaderOverflow(s)) != 0L))//写锁未占用,但读锁数量超限, 超出部分放到readerOverflow字段中
                        return ns;// 获取成功后, 直接返回
                    else if (m >= WBIT) { // 写锁被占用,以随机方式探测是否要退出自旋
                        if (spins > 0) {
                            if (LockSupport.nextSecondarySeed() >= 0)
                                --spins;
                        }
                        else {
                            if (spins == 0) {
                                WNode nh = whead, np = wtail;
                                if ((nh == h && np == p) || (h = nh) != (p = np))
                                    break;
                            }
                            spins = SPINS;
                        }
                    }
                }
            }
            //走到下面则说明上面那个if不成立，也就意味着whead != wtail
            if (p == null) { // p == null表示队列为空, 则初始化队列(构造头结点)
                WNode hd = new WNode(WMODE, null);//构造头节点为WMODE，执行这个if之后进入下一次循环
                if (U.compareAndSwapObject(this, WHEAD, null, hd))
                    wtail = hd;
            }
            else if (node == null)
                node = new WNode(RMODE, p);
            else if (h == p || p.mode != RMODE) {//如果head == p并且p是WMODE
                if (node.prev != p)//新node加入到p后面
                    node.prev = p;
                else if (U.compareAndSwapObject(this, WTAIL, p, node)) {//新node加入到p后面
                    p.next = node;
                    break;
                }
            }
            //队列不为空并且是RMODE模式， 添加该节点到尾节点的cowait链（实际上构成一个读线程stack）中
            else if (!U.compareAndSwapObject(p, WCOWAIT,
                                             node.cowait = p.cowait, node))//注意这里有可能造成一个环路node.cowait=p.cowait=node
                node.cowait = null;//失败处理
            else {
                for (;;) {
                    WNode pp, c; Thread w;
                    if ((h = whead) != null && (c = h.cowait) != null &&
                    	//尝试unpark头元素（whead）的cowait中的第一个元素,假如是读锁会通过循环释放cowait链
                        U.compareAndSwapObject(h, WCOWAIT, c, c.cowait) &&
                        (w = c.thread) != null) // help release
                        U.unpark(w);
                    //node所在的根节点p的前驱就是whead或者p已经是whead或者p的前驱为null
                    if (h == (pp = p.prev) || h == p || pp == null) {
                        long m, s, ns;
                        do {
                            if ((m = (s = state) & ABITS) < RFULL ?
                                U.compareAndSwapLong(this, STATE, s,
                                                     ns = s + RUNIT) :
                                (m < WBIT &&
                                 (ns = tryIncReaderOverflow(s)) != 0L))
                                return ns;
                        } while (m < WBIT);
                    }
                    if (whead == h && p.prev == pp) {
                        long time;
                        if (pp == null || h == p || p.status > 0) {//这一步没看懂
                            node = null; // throw away
                            break;
                        }
                        if (deadline == 0L)
                            time = 0L;
                        else if ((time = deadline - System.nanoTime()) <= 0L)
                            return cancelWaiter(node, p, false);
                        Thread wt = Thread.currentThread();
                        U.putObject(wt, PARKBLOCKER, this);
                        node.thread = wt;
                        if ((h != pp || (state & ABITS) == WBIT) &&
                            whead == h && p.prev == pp)
                            U.park(false, time);
                        node.thread = null;
                        U.putObject(wt, PARKBLOCKER, null);
                        if (interruptible && Thread.interrupted())
                            return cancelWaiter(node, p, true);
                    }
                }
            }
        }

        for (int spins = -1;;) {
            WNode h, np, pp; int ps;
            if ((h = whead) == p) {
                if (spins < 0)
                    spins = HEAD_SPINS;
                else if (spins < MAX_HEAD_SPINS)
                    spins <<= 1;
                for (int k = spins;;) { // spin at head
                    long m, s, ns;
                    if ((m = (s = state) & ABITS) < RFULL ?//没有任何线程获取写锁的情况下尝试CAS
                        U.compareAndSwapLong(this, STATE, s, ns = s + RUNIT) :
                        (m < WBIT && (ns = tryIncReaderOverflow(s)) != 0L)) {
                        WNode c; Thread w;
                        whead = node;
                        node.prev = null;
                        while ((c = node.cowait) != null) {//获取读锁成功之后唤醒整个cowait链
                            if (U.compareAndSwapObject(node, WCOWAIT,
                                                       c, c.cowait) &&
                                (w = c.thread) != null)
                                U.unpark(w);
                        }
                        return ns;
                    }
                    else if (m >= WBIT &&
                             LockSupport.nextSecondarySeed() >= 0 && --k <= 0)
                        break;
                }
            }
            else if (h != null) {
                WNode c; Thread w;
                while ((c = h.cowait) != null) {
                    if (U.compareAndSwapObject(h, WCOWAIT, c, c.cowait) &&
                        (w = c.thread) != null)
                        U.unpark(w);
                }
            }
            if (whead == h) {
                if ((np = node.prev) != p) {
                    if (np != null)
                        (p = np).next = node;   // stale
                }
                else if ((ps = p.status) == 0)//设置node.prev的状态为WAITING
                    U.compareAndSwapInt(p, WSTATUS, 0, WAITING);
                else if (ps == CANCELLED) {//node.prev的状态为CANCELLED，node.prev节点剔除出这个CLH队列
                    if ((pp = p.prev) != null) {
                        node.prev = pp;
                        pp.next = node;
                    }
                }
                else {//这里就阻塞线程，等待被其他线程唤醒
                    long time;
                    if (deadline == 0L)
                        time = 0L;
                    else if ((time = deadline - System.nanoTime()) <= 0L)
                        return cancelWaiter(node, node, false);
                    Thread wt = Thread.currentThread();
                    U.putObject(wt, PARKBLOCKER, this);
                    node.thread = wt;
                    if (p.status < 0 &&
                        (p != h || (state & ABITS) == WBIT) &&
                        whead == h && node.prev == p)
                        U.park(false, time);
                    node.thread = null;
                    U.putObject(wt, PARKBLOCKER, null);
                    if (interruptible && Thread.interrupted())
                        return cancelWaiter(node, node, true);
                }
            }
        }
    }

    /**
     * If node non-null, forces cancel status and unsplices it from
     * queue if possible and wakes up any cowaiters (of the node, or
     * group, as applicable), and in any case helps release current
     * first waiter if lock is free. (Calling with null arguments
     * serves as a conditional form of release, which is not currently
     * needed but may be needed under possible future cancellation
     * policies). This is a variant of cancellation methods in
     * AbstractQueuedSynchronizer (see its detailed explanation in AQS
     * internal documentation).
     *
     * @param node if nonnull, the waiter
     * @param group either node or the group node is cowaiting with
     * @param interrupted if already interrupted
     * @return INTERRUPTED if interrupted or Thread.interrupted, else zero
     */
    private long cancelWaiter(WNode node, WNode group, boolean interrupted) {
        if (node != null && group != null) {
            Thread w;
            node.status = CANCELLED;
            // unsplice cancelled nodes from group
            for (WNode p = group, q; (q = p.cowait) != null;) {
                if (q.status == CANCELLED) {
                    U.compareAndSwapObject(p, WCOWAIT, q, q.cowait);
                    p = group; // restart
                }
                else
                    p = q;
            }
            if (group == node) {
                for (WNode r = group.cowait; r != null; r = r.cowait) {
                    if ((w = r.thread) != null)
                        U.unpark(w);       // wake up uncancelled co-waiters
                }
                for (WNode pred = node.prev; pred != null; ) { // unsplice
                    WNode succ, pp;        // find valid successor
                    while ((succ = node.next) == null ||
                           succ.status == CANCELLED) {
                        WNode q = null;    // find successor the slow way
                        for (WNode t = wtail; t != null && t != node; t = t.prev)
                            if (t.status != CANCELLED)
                                q = t;     // don't link if succ cancelled
                        if (succ == q ||   // ensure accurate successor
                            U.compareAndSwapObject(node, WNEXT,
                                                   succ, succ = q)) {
                            if (succ == null && node == wtail)
                                U.compareAndSwapObject(this, WTAIL, node, pred);
                            break;
                        }
                    }
                    if (pred.next == node) // unsplice pred link
                        U.compareAndSwapObject(pred, WNEXT, node, succ);
                    if (succ != null && (w = succ.thread) != null) {
                        succ.thread = null;
                        U.unpark(w);       // wake up succ to observe new pred
                    }
                    if (pred.status != CANCELLED || (pp = pred.prev) == null)
                        break;
                    node.prev = pp;        // repeat if new pred wrong/cancelled
                    U.compareAndSwapObject(pp, WNEXT, pred, succ);
                    pred = pp;
                }
            }
        }
        WNode h; // Possibly release first waiter
        while ((h = whead) != null) {
            long s; WNode q; // similar to release() but check eligibility
            if ((q = h.next) == null || q.status == CANCELLED) {
                for (WNode t = wtail; t != null && t != h; t = t.prev)
                    if (t.status <= 0)
                        q = t;
            }
            if (h == whead) {
                if (q != null && h.status == 0 &&
                    ((s = state) & ABITS) != WBIT && // waiter is eligible
                    (s == 0L || q.mode == RMODE))
                    release(h);
                break;
            }
        }
        return (interrupted || Thread.interrupted()) ? INTERRUPTED : 0L;
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe U;
    private static final long STATE;
    private static final long WHEAD;
    private static final long WTAIL;
    private static final long WNEXT;
    private static final long WSTATUS;
    private static final long WCOWAIT;
    private static final long PARKBLOCKER;

    static {
        try {
            U = sun.misc.Unsafe.getUnsafe();
            Class<?> k = StampedLock.class;
            Class<?> wk = WNode.class;
            STATE = U.objectFieldOffset
                (k.getDeclaredField("state"));
            WHEAD = U.objectFieldOffset
                (k.getDeclaredField("whead"));
            WTAIL = U.objectFieldOffset
                (k.getDeclaredField("wtail"));
            WSTATUS = U.objectFieldOffset
                (wk.getDeclaredField("status"));
            WNEXT = U.objectFieldOffset
                (wk.getDeclaredField("next"));
            WCOWAIT = U.objectFieldOffset
                (wk.getDeclaredField("cowait"));
            Class<?> tk = Thread.class;
            PARKBLOCKER = U.objectFieldOffset
                (tk.getDeclaredField("parkBlocker"));

        } catch (Exception e) {
            throw new Error(e);
        }
    }
}
