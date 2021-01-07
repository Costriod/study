package java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.LockSupport;

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

    /**
     * 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 1000 0000 写锁标记位，即第8位为1，值为128
     *
     * 其实很好理解，因为写锁是独占锁，所以同一时刻最多有一个线程获取到写锁，所以用第8位bit来标记写锁状态
     */
    private static final long WBIT  = 1L << LG_READERS;

    /**
     * 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0111 1111 读锁掩码，通过后7位&操作，值为127
     *
     * 上面提到了写锁占用第8位bit来标记写锁状态，那么剩下的最低位的7个bit用来标记读锁状态，这是一个7位掩码
     */
    private static final long RBITS = WBIT - 1L;

    /**
     * 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0111 1110 最大读锁数量，值为126
     *
     * 最低的7个bit标记读锁数量，最大读锁数量代表126，如果读锁超过126个，则state的最低7位还是126，但是需要用一个额外的readerOverflow来存储超出的读锁
     *
     * 需要注意一点：state的最低7位bit如果为126，则有两种可能，第一种是恰好126个读锁，第二种是读锁超出126个，需要用一个额外的readerOverflow来存储超出的读锁
     */
    private static final long RFULL = RBITS - 1L;

    /**0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 1111 1111 读写锁掩码。通过后8位&操作，值为255*/
    private static final long ABITS = RBITS | WBIT;

    /**1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1000 0000 RBITS按位取反，包括符号位，值为-128*/
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
        volatile WNode next;      //WMODE代表WRITE_MODE，也就是独占锁模式，独占锁模式一般用到prev和next组成双向链表结构，和AbstractQueuedSynchronizer的Node类似


        volatile WNode cowait;    // 用于保存读线程，如果是READ_MODE，则新的节点会添加到cowait下面组成一个单向链表，类似于AbstractQueuedSynchronizer的Condition模式
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

    /** Lock sequence/state
     * state设计的很精妙，一开始state的值是256，对应二进制值如下：
     * 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0001 0000 0000
     * 最高位（最高位就是第一位bit）是0，代表符号位，0代表正数
     *
     * 前56位bit用来标记写锁的获取与释放状态，为什么这么说呢，在有写锁情况下stamp == state，获取写锁与释放写锁的时候，每次都是state = state + WBIT
     * 第一次获取写锁：0001 0000 0000 + 0000 1000 0000 = 0001 1000 0000
     * 第一次释放写锁：0001 1000 0000 + 0000 1000 0000 = 0010 0000 0000 （触发了进位）
     *
     * 第二次获取写锁：0010 0000 0000 + 0000 1000 0000 = 0010 1000 0000
     * 第二次释放写锁：0010 1000 0000 + 0000 1000 0000 = 0011 0000 0000 （触发了进位）
     *
     * 第三次获取写锁：0011 0000 0000 + 0000 1000 0000 = 0011 1000 0000
     * 第三次释放写锁：0011 1000 0000 + 0000 1000 0000 = 0100 0000 0000 （触发了进位）
     *
     * 从上面的规律得知，获取写锁不会造成进位，只有释放写锁才会造成进位，所以释放写锁过程中可能造成state变成0（因为不断进位肯定会超出long最大值）
     * 所以在释放写锁过程中，有这么一行代码 state = (stamp += WBIT) == 0L ? ORIGIN : stamp，如果state变成0了（多次释放写锁导致进位变成0），
     * 则立即将state初始化为256（ORIGIN的值就是256）
     *
     * state的最低8位bit则用来存储读锁写锁的状态，0000 0000前面一位代表写锁状态，剩下7位代表读锁状态
     * 1000 0000代表写锁
     * 0111 1110代表读锁
     * 在这里有一点需要注意，0111 1110的值就是126，如果读锁数量达到126，直接使用0111 1110即可，如果超出126，则需要额外的readerOverflow存储，
     * 所以如果是127个读锁，state的最低7位也不会是0111 1111，而是到达126之后，开始用额外的readerOverflow存储，也就是说，最低7位永远不可能是0111 1111
     * 所以如果state的低7位是0111 1110则说明读锁数量>=126（因为超出部分存在于readerOverflow）
     *
     */
    private transient volatile long state;

    /** 如果读锁数量超出了126则需要用readerOverflow这个字段来存储，初始值0 */
    private transient int readerOverflow;

    /**
     * 构造函数，初始化state值为256
     */
    public StampedLock() {
        state = ORIGIN;
    }

    /**
     * 获取独占锁，也就是写锁，会一直阻塞直到获取锁
     *
     * @return 返回一个stamp票据（票据用于后续释放锁或者mode转换）
     */
    public long writeLock() {
        long s, next;  // bypass acquireWrite in fully unlocked case only

        /*
        ((s = state) & ABITS) == 0L 的意思：state和255进行&运算，如果值为0则代表state的二进制值里面，最后8位为0，说明此时没有读锁也没有写锁，可以尝试申请锁
        U.compareAndSwapLong(this, STATE, s, next = s + WBIT) 的意思：把state设置新的值，值为next，next = 原state + 128
        如果这两个boolean都为true，则直接返回next的值即可，否则需要阻塞获取锁
        */
        return ((((s = state) & ABITS) == 0L && U.compareAndSwapLong(this, STATE, s, next = s + WBIT)) ? next : acquireWrite(false, 0L));
    }

    /**
     * 获取独占锁，也就是写锁，如果获取失败则立即返回0，获取锁成功则返回state + 128
     * @return 返回一个stamp票据（票据用于后续释放锁或者mode转换）, 返回0的意思就是获取锁失败
     */
    public long tryWriteLock() {
        long s, next;
        /*
        ((s = state) & ABITS) == 0L 的意思：state和255进行&运算，如果值为0则代表state的二进制值里面，最后8位为0，说明此时没有读锁也没有写锁，可以尝试申请锁
        U.compareAndSwapLong(this, STATE, s, next = s + WBIT) 的意思：把state设置新的值，值为next，next = 原state + 128
        如果这两个boolean都为true，则直接返回next的值即可，否则返回0
        */
        return ((((s = state) & ABITS) == 0L && U.compareAndSwapLong(this, STATE, s, next = s + WBIT)) ? next : 0L);
    }

    /**
     * Exclusively acquires the lock if it is available within the
     * given time and the current thread has not been interrupted.
     * Behavior under timeout and interruption matches that specified
     * for method {@link Lock#tryLock(long,TimeUnit)}.
     *
     * @param 获取锁最长超时时间，超过这个时间获取失败则返回0
     * @param 时间单位
     * @return 返回一个stamp票据（票据用于后续释放锁或者mode转换）, 返回0的意思就是获取锁失败
     * @throws 如果线程在获取锁之前被中断，则抛出InterruptedException
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
     * @return 返回一个stamp票据（票据用于后续释放锁或者mode转换）
     * @throws 如果线程在获取锁之前被中断，则抛出InterruptedException
     */
    public long writeLockInterruptibly() throws InterruptedException {
        long next;
        if (!Thread.interrupted() && (next = acquireWrite(true, 0L)) != INTERRUPTED)
            return next;
        throw new InterruptedException();
    }

    /**
     * 获取读锁，也就是非独占锁，会阻塞到获取锁为止
     * @return 返回一个stamp票据（票据用于后续释放锁或者mode转换）
     */
    public long readLock() {
        long s = state, next;  // bypass acquireRead on common uncontended case
        /*
        whead == wtail && (s & ABITS) < RFULL 的意思：CLH的头节点和尾节点是同一个，并且读锁数量小于126
        U.compareAndSwapLong(this, STATE, s, next = s + RUNIT) 的意思：把state设置新的值，值为next，next = 原state + 1，其实就是获取读锁的线程数加1
        如果这两个boolean都为true，则直接返回next的值即可，否则需要阻塞获取读锁
         */
        return ((whead == wtail && (s & ABITS) < RFULL && U.compareAndSwapLong(this, STATE, s, next = s + RUNIT)) ? next : acquireRead(false, 0L));
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
            if ((m = (s = state) & ABITS) == WBIT)//如果已经是写锁状态，写锁状态不允许获取读锁，读写互斥
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
        /*
        ((s = state) & WBIT) == 0L 的意思：WBIT是写锁掩码，如果state & WBIT为0说明没有写锁，不为0则代表有写锁
         */
        return (((s = state) & WBIT) == 0L) ? (s & SBITS) : 0L;
    }

    /**
     * Returns true if the lock has not been exclusively acquired
     * since issuance of the given stamp. Always returns false if the
     * stamp is zero. Always returns true if the stamp represents a
     * currently held lock. Invoking this method with a value not
     * obtained from {@link #tryOptimisticRead} or a locking method
     * for this lock has no defined effect or result.
     *
     * 如果签发这个stamp之后没有线程获取到写锁，则返回true；
     * 如果stamp为0则直接返回false（因为state初始化时是256，state不会为0）
     * 如果stamp代表当前持有锁（可以是读锁或写锁），则返回true
     *
     * @param stamp a stamp
     * @return {@code true} if the lock has not been exclusively acquired
     * since issuance of the given stamp; else false
     */
    public boolean validate(long stamp) {
        //unsafe.loadFence()其实是加入一个内存屏障，防止指令重排序，在这里是防止指令重排序后下面的数据校验不准确
        U.loadFence();
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

        //如果传进来的stamp不等于state或者stamp & WBIT为0，则抛出异常
        //因为写锁与读锁互斥，已经有写锁的情况下，state值不会变化（因为获取写锁之后其他线程都不能获取锁），state == stamp，但是如果state != stamp，说明中间有其他线程获取读锁（这里就造成了矛盾）
        //(stamp & WBIT) == 0L说明写锁标记位为0，代表没有任何线程拥有写锁
        if (state != stamp || (stamp & WBIT) == 0L)
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
     * 转换为写锁
     * 1.如果已经是获取到写锁，直接返回
     * 2.如果只有一个读锁，并且没有其他线程获取到写锁，则释放读锁，并且申请写锁
     * 3.如果是乐观读，那么只有在没有写锁的情况下获取写锁
     * 4.其他状态都返回0，代表转换写锁失败
     *
     * @param stamp a stamp
     * @return a valid write stamp, or zero on failure
     */
    public long tryConvertToWriteLock(long stamp) {
        long a = stamp & ABITS, m, s, next;
        //state匹配stamp，也是前面validate方法同样效果，代表这个stamp是有效的，也可能这个stamp是乐观读（因为乐观读的代码也是 state & SBITS == stamp & SBITS）
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
     * 转换为读锁
     * 1.如果已经获取到写锁，则立即释放写锁，然后申请读锁
     * 2.如果已经是获取到读锁，则立即返回
     * 3.如果是乐观读，则立即获取读锁并返回新的stamp
     * 4.其他状态都返回0，代表转换读锁失败
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
            //如果中途有其他线程获取到了写锁直接退出循环，因为不符合乐观锁条件（因为乐观锁的代码是 state & SBITS == stamp & SBITS）
            if (((s = state) & SBITS) != (stamp & SBITS))
                break;

            //如果当前state没有读锁、也没有写锁
            if ((m = s & ABITS) == 0L) {
                //执行到这里，如果stamp代表有锁，则代表有其他线程在这期间修改了state
                if (a != 0L)
                    break;

                //如果没有读锁，也没有写锁，并且前面没有其他线程修改state，直接返回state即可
                return s;
            }
            //前面if语句给m进行了赋值，m = s & ABITS
            else if (m == WBIT) {//有写锁
                //a != m说明stamp和state值不一样，说明state发生了变化，再结合m == WBIT，说明有其他线程获取了写锁
                if (a != m)
                    break;
                state = next = (s += WBIT) == 0L ? ORIGIN : s;//释放写锁
                if ((h = whead) != null && h.status != 0)//唤醒下一个节点的线程
                    release(h);
                return next;
            }

            //当前stamp没有任何锁、或者有写锁（可能还有读锁，但写锁不可能和读锁共存，只有可能是使用者乱传值）
            else if (a == 0L || a >= WBIT)
                break;
            else if (m < RFULL) {//读锁未超出126个
                if (U.compareAndSwapLong(this, STATE, s, next = s - RUNIT)) {//释放读锁
                    if (m == RUNIT && (h = whead) != null && h.status != 0)//唤醒下一个节点
                        release(h);
                    //返回乐观读stamp
                    return next & SBITS;
                }
            }
            else if ((next = tryDecReaderOverflow(s)) != 0L)
                //返回乐观读stamp
                return next & SBITS;
        }
        return 0L;
    }

    /**
     * Releases the write lock if it is held, without requiring a
     * stamp value. This method may be useful for recovery after
     * errors.
     *
     * 设计方式就是，每次不管获取写锁还是释放写锁，都要加一个WBIT，这就会造成进位，如果进位超出了64位变成0，又重新赋值256
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
        //当前没有写锁，但是又要求释放写锁，这里需要抛异常
        if (((s = state) & WBIT) == 0L)
            throw new IllegalMonitorStateException();
        state = (s += WBIT) == 0L ? ORIGIN : s;
        if ((h = whead) != null && h.status != 0)
            release(h);
    }

    final void unstampedUnlockRead() {
        for (;;) {
            long s, m; WNode h;
            //当前没有锁，或者是已经有写锁，此时释放读锁肯定是不符合逻辑的，所以要抛异常
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
            if (U.compareAndSwapLong(this, STATE, s, s | RBITS)) {//这里CAS操作成功修改了state的值，其中最低7位bit的都是1
                ++readerOverflow;//readerOverflow累加
                state = s;//再重新把旧的值赋值给state
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
     *
     * 为什么要从尾部往前找，是因为在并发情况下，这一个链表有可能在执行节点移除操作，很可能某一个线程执行到这里发现链表的next为null，以为是到了尾部，就没必要往下执行了，
     * 但其实是别的线程刚刚执行cancel节点的移除操作，其实后面还有节点，所以从尾部往前查找节点可以避免这种问题
     *
     * Wakes up the successor of h (normally whead). This is normally
     * just h.next, but may require traversal from wtail if next
     * pointers are lagging. This may fail to wake up an acquiring
     * thread when one or more have been cancelled, but the cancel
     * methods themselves provide extra safeguards to ensure liveness.
     */
    private void release(WNode h) {
        if (h != null) {//如果head不为null
            WNode q; Thread w;
            U.compareAndSwapInt(h, WSTATUS, WAITING, 0);//尝试head.status设为从WAITING设为0
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
     * @param deadline 如果非0，则表示限时获取（单位纳秒），如果小于等于0，则代表一直阻塞，直到获取锁
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
                //如果其他线程获取了写锁，并且队列头尾指向同一个地址，说明当前有另一个线程获取到写锁
                spins = (m == WBIT && wtail == whead) ? SPINS : 0;
            else if (spins > 0) {
                // 写锁被其他线程占用,以随机方式探测是否要退出自旋
                if (LockSupport.nextSecondarySeed() >= 0)
                    --spins;
            }
            else if ((p = wtail) == null) { //初始化等待队列，如果wtail为null表明队列一定为空
                WNode hd = new WNode(WMODE, null);//构造一个WRITE_MODE对象
                if (U.compareAndSwapObject(this, WHEAD, null, hd))//whead指向新创建的hd对象
                    wtail = hd;//wtail也指向这个对象
            }
            else if (node == null)//如果前面条件不满足，即wtail!=null，则此刻创建新的WNode，并且新节点prev指向wtail
                node = new WNode(WMODE, p);
            else if (node.prev != p)//如果node不为null，并且node.prev != wtail，重新让node.prev指向wtail
                node.prev = p;
            else if (U.compareAndSwapObject(this, WTAIL, p, node)) {//设置wtail指向新的node
                p.next = node;//设置原来的wtail.next指向新的node
                break;
            }
        }

        for (int spins = -1;;) {
            WNode h, np, pp; int ps;
            // 前面p是赋值wtail，如果当前p结点是队首结点, 说明此刻就只有当前线程一个节点，则立即尝试获取写锁
            if ((h = whead) == p) {
                if (spins < 0)
                    spins = HEAD_SPINS;
                else if (spins < MAX_HEAD_SPINS)
                    //spins = spins<<1，左移一位，代表乘以2
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
            else if (h != null) { // 如果head节点不为空，并且whead != wtail
                WNode c; Thread w;
                while ((c = h.cowait) != null) {//如果head节点的cowait属性不为空
                    if (U.compareAndSwapObject(h, WCOWAIT, c, c.cowait) &&//利用CAS重置cowait
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
            //队列不为空并且是RMODE模式，添加该节点到尾节点的cowait链（实际上构成一个读线程stack）中
            else if (!U.compareAndSwapObject(p, WCOWAIT, node.cowait = p.cowait, node))//注意这里有可能造成一个环路node.cowait=p.cowait=node
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
