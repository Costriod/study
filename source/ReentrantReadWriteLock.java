package java.util.concurrent.locks;
import java.util.concurrent.TimeUnit;
import java.util.Collection;
public class ReentrantReadWriteLock
        implements ReadWriteLock, java.io.Serializable {
    private static final long serialVersionUID = -6992448646407690164L;
    /**读锁*/
    private final ReentrantReadWriteLock.ReadLock readerLock;
    /**写锁*/
    private final ReentrantReadWriteLock.WriteLock writerLock;
    /**同步器对象*/
    final Sync sync;
    /**构造函数，默认非公平锁*/
    public ReentrantReadWriteLock() {
        this(false);
    }
    /**构造函数，读写锁对象创建，根据传入boolean值确认是否是公平锁
     * ReadLock和WriteLock都是一个内部类，实现了Lock接口，读锁是共享锁，写锁是排它锁，也叫独占锁，注释里面共享锁、读锁都代表读锁，写锁、独占锁、排它锁都代表写锁
     */
    public ReentrantReadWriteLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
        readerLock = new ReadLock(this);
        writerLock = new WriteLock(this);
    }
    public ReentrantReadWriteLock.WriteLock writeLock() { return writerLock; }
    public ReentrantReadWriteLock.ReadLock  readLock()  { return readerLock; }
    
    /**sync抽象类继承了AbstractQueuedSynchronizer*/
    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 6317671515068378041L;
        /**首先ReentrantReadWriterLock使用一个32位的int值state来表示锁被占用的状态，高16位表示当前所有读锁的重入次数累加值，低16位表示拥有写锁的线程的重入次数
         * （写锁就是排它锁，也称作独占锁，读锁是共享锁）由于读写锁ReentrantReadWriteLock对象拥有一个读锁和写锁，而读锁和写锁共用一个sync对象
         * SHARED_SHIFT表示读锁占用的位数，常量16
         * SHARED_UNIT读锁占有的高16位数，2^16；
         * MAX_COUNT表示申请读锁最大的线程数量，为65535
         * EXCLUSIVE_MASK表示计算写锁的具体值时，该值为 15个1，用 getState & EXCLUSIVE_MASK同一个线程获取锁的计数，大于1表示重入。
        */
        static final int SHARED_SHIFT   = 16;
        static final int SHARED_UNIT    = (1 << SHARED_SHIFT);
        static final int MAX_COUNT      = (1 << SHARED_SHIFT) - 1;
        static final int EXCLUSIVE_MASK = (1 << SHARED_SHIFT) - 1;
        
        static int sharedCount(int c)    { return c >>> SHARED_SHIFT; }
        
        static int exclusiveCount(int c) { return c & EXCLUSIVE_MASK; }
        /**静态内部类，有两个属性：
         * count 计数
         * tid 当前线程的线程id*/
        static final class HoldCounter {
            int count = 0;
            
            final long tid = getThreadId(Thread.currentThread());
        }
        
        /**静态内部类，继承ThreadLocal*/
        static final class ThreadLocalHoldCounter
            extends ThreadLocal<HoldCounter> {
            public HoldCounter initialValue() {
                return new HoldCounter();
            }
        }
        //获取当前线程读锁重入次数，因为是ThreadLocal子类对象，所以每个线程都有一份readHolds副本
        private transient ThreadLocalHoldCounter readHolds;
        //当前线程的线程id与读锁重入次数的组合对象
        private transient HoldCounter cachedHoldCounter;
        //firstReader第一个获取读锁的线程
        private transient Thread firstReader = null;
        //firstReader第一个获取读锁的线程的重入次数
        private transient int firstReaderHoldCount;
        Sync() {
            readHolds = new ThreadLocalHoldCounter();
            setState(getState()); //没理解这一步为什么获取state又重新赋值
		}
        
        
        abstract boolean readerShouldBlock();
        
        abstract boolean writerShouldBlock();
        
        /**
         * 尝试释放独占锁，传入一个int值，表示将state减去该int值，如果state减去该int值不为0，则本次释放锁失败，返回false
		 * 因为32位的state只有低16位表示写锁的重入次数，减去release的话仅仅会变动低16位，高16位不变
         * 如果为0，则将当前独占的线程设为null，表示当前没有任何线程获取了该锁，并返回true
         */
        protected final boolean tryRelease(int releases) {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int nextc = getState() - releases;
            boolean free = exclusiveCount(nextc) == 0;
            if (free)
                setExclusiveOwnerThread(null);
            setState(nextc);
            return free;
        }
        /**
         * 尝试获取独占锁
         */
        protected final boolean tryAcquire(int acquires) {
            
            Thread current = Thread.currentThread();
            int c = getState();//获取state
            int w = exclusiveCount(c);//获取写锁重入次数，每一次重入state都加1
            if (c != 0) {//代表读锁或者写锁被某些线程拿到了
			    //写锁重入次数为0并且c不为0只可能是一种情况，就是只有读锁，必须读锁都释放了才允许获取写锁
                if (w == 0 || current != getExclusiveOwnerThread())//或者当前线程不是获取写锁的线程(有可能执行tryAcquire的时候写锁被其他线程占用)
                    return false;
                if (w + exclusiveCount(acquires) > MAX_COUNT)//如果前面条件不满足，重入次数+申请的锁次数>最大值则抛出错误
                    throw new Error("Maximum lock count exceeded");
                
                setState(c + acquires);//如果前面条件都不满足，锁计数器累加，此时代表获取写锁成功
                return true;
            }
			//如果c为0，代表没有任何线程获取到锁，此时当前线程可以获取锁
            if (writerShouldBlock() ||
                !compareAndSetState(c, c + acquires))//写锁需要阻塞或者当前线程更新state失败
                return false;
            setExclusiveOwnerThread(current);//设置当前线程获取独占锁（写锁），读锁是没有setExclusiveOwnerThread的
            return true;
        }
        /**尝试释放共享锁*/
        protected final boolean tryReleaseShared(int unused) {
            Thread current = Thread.currentThread();
            if (firstReader == current) {//如果第一个获得读锁的线程是当前线程
                if (firstReaderHoldCount == 1)//如果第一个获得读锁的线程重入次数为1
                    firstReader = null;
                else
                    firstReaderHoldCount--;//否则第一个获得读锁线程重入次数减1
            } else {
                HoldCounter rh = cachedHoldCounter;
                if (rh == null || rh.tid != getThreadId(current))
                    rh = readHolds.get();//rh从当前线程的readHolds副本中取出
                int count = rh.count;
                if (count <= 1) {
                    readHolds.remove();//当前线程的readHolds副本移除
                    if (count <= 0)
                        throw unmatchedUnlockException();
                }
                --rh.count;//如果前面if条件不满足则减1
            }
            for (;;) {
                int c = getState();//当前state
                int nextc = c - SHARED_UNIT;//当前state数减去一个读锁，读锁是高16位，所以这里需要减去一个1*2^16
                if (compareAndSetState(c, nextc))
                    return nextc == 0;
            }
        }
        
        private IllegalMonitorStateException unmatchedUnlockException() {
            return new IllegalMonitorStateException(
                "attempt to unlock read lock, not locked by current thread");
        }
        /**开始尝试获取共享锁，如果失败则循环不断尝试获取锁，成功返回1，失败返回-1*/
        protected final int tryAcquireShared(int unused) {
            Thread current = Thread.currentThread();
            int c = getState();
            if (exclusiveCount(c) != 0 &&
                getExclusiveOwnerThread() != current)//当写锁重入次数大于0且当前线程没有获取到写锁，返回-1
                return -1;
            int r = sharedCount(c);//共享锁重入次数总数
            if (!readerShouldBlock() &&
                r < MAX_COUNT &&
                compareAndSetState(c, c + SHARED_UNIT)) {//开始获取共享锁
                if (r == 0) {//读锁计数为0，则当前线程立即获得读锁
                    firstReader = current;
                    firstReaderHoldCount = 1;
                } else if (firstReader == current) {//读锁重入
                    firstReaderHoldCount++;
                } else {//如果前面都不满足
                    HoldCounter rh = cachedHoldCounter;
                    if (rh == null || rh.tid != getThreadId(current))
                        cachedHoldCounter = rh = readHolds.get();
                    else if (rh.count == 0)
                        readHolds.set(rh);
                    rh.count++;
                }
                return 1;
            }
            return fullTryAcquireShared(current);
        }
        /**循环自旋，直到获取锁为止*/
        final int fullTryAcquireShared(Thread current) {
            HoldCounter rh = null;
            for (;;) {
                int c = getState();
                if (exclusiveCount(c) != 0) {//当写锁重入次数不为0
                    if (getExclusiveOwnerThread() != current)//当前线程没有获得该独占锁
                        return -1;
                } else if (readerShouldBlock()) {//读锁需要阻塞
                    if (firstReader == current) {
                        //当前线程就是第一个读线程，啥都不干
                    } else {
                        if (rh == null) {
                            rh = cachedHoldCounter;
                            if (rh == null || rh.tid != getThreadId(current)) {
                                rh = readHolds.get();
                                if (rh.count == 0)//当前线程没有获取到读锁，回收当前readHolds对象
                                    readHolds.remove();
                            }
                        }
                        if (rh.count == 0)
                            return -1;
                    }
                }
                if (sharedCount(c) == MAX_COUNT)//如果读锁数量达到最大值，抛出错误
                    throw new Error("Maximum lock count exceeded");
                if (compareAndSetState(c, c + SHARED_UNIT)) {
                    if (sharedCount(c) == 0) {
                        firstReader = current;
                        firstReaderHoldCount = 1;
                    } else if (firstReader == current) {
                        firstReaderHoldCount++;
                    } else {
                        if (rh == null)
                            rh = cachedHoldCounter;
                        if (rh == null || rh.tid != getThreadId(current))
                            rh = readHolds.get();
                        else if (rh.count == 0)
                            readHolds.set(rh);
                        rh.count++;
                        cachedHoldCounter = rh; 
                    }
                    return 1;
                }
            }
        }
        
        /**尝试获取写锁*/
        final boolean tryWriteLock() {
            Thread current = Thread.currentThread();
            int c = getState();
            if (c != 0) {
                int w = exclusiveCount(c);//独占锁重入次数
                if (w == 0 || current != getExclusiveOwnerThread())//独占锁重入次数为0或者当前线程并不是获得独占锁的线程，则返回false
                    return false;
                if (w == MAX_COUNT)//写锁重入次数等于最大值则抛出异常
                    throw new Error("Maximum lock count exceeded");
            }
            //cas对当前读锁重入次数加1失败，返回false
            if (!compareAndSetState(c, c + 1))
                return false;
            setExclusiveOwnerThread(current);
            return true;
        }
        /**尝试获取读锁*/
        final boolean tryReadLock() {
            Thread current = Thread.currentThread();
            for (;;) {
                int c = getState();
                if (exclusiveCount(c) != 0 &&//当写锁重入次数不为0并且获得写锁的线程不是当前线程，则返回false
                    getExclusiveOwnerThread() != current)
                    return false;
                int r = sharedCount(c);
                if (r == MAX_COUNT)//读锁重入次数达到最大值，抛出错误
                    throw new Error("Maximum lock count exceeded");
                if (compareAndSetState(c, c + SHARED_UNIT)) {
                    if (r == 0) {//如果读锁重入次数为0，firstReader指向当前线程，firstReaderHoldCount为1
                        firstReader = current;
                        firstReaderHoldCount = 1;
                    } else if (firstReader == current) {//当前拥有读锁的线程再次获取读锁
                        firstReaderHoldCount++;
                    } else {
                        HoldCounter rh = cachedHoldCounter;
                        if (rh == null || rh.tid != getThreadId(current))
                            cachedHoldCounter = rh = readHolds.get();
                        else if (rh.count == 0)
                            readHolds.set(rh);
                        rh.count++;
                    }
                    return true;
                }
            }
        }
        /**判断当前线程是否获取独占锁*/
        protected final boolean isHeldExclusively() {
            return getExclusiveOwnerThread() == Thread.currentThread();
        }
        
        final ConditionObject newCondition() {
            return new ConditionObject();
        }
        /**没有线程获得独占锁则返回null，如果有则返回那个获得独占锁的线程*/
        final Thread getOwner() {
            return ((exclusiveCount(getState()) == 0) ?
                    null :
                    getExclusiveOwnerThread());
        }
        /**获取读锁总数*/
        final int getReadLockCount() {
            return sharedCount(getState());
        }
        /**独占锁是否有线程在占用*/
        final boolean isWriteLocked() {
            return exclusiveCount(getState()) != 0;
        }
        /**返回当前线程拥有的独占锁重入次数*/
        final int getWriteHoldCount() {
            return isHeldExclusively() ? exclusiveCount(getState()) : 0;
        }
        /**获得当前线程拥有的读锁重入次数*/
        final int getReadHoldCount() {
            if (getReadLockCount() == 0)
                return 0;
            Thread current = Thread.currentThread();
            if (firstReader == current)
                return firstReaderHoldCount;
            HoldCounter rh = cachedHoldCounter;
            if (rh != null && rh.tid == getThreadId(current))
                return rh.count;
            int count = readHolds.get().count;
            if (count == 0) readHolds.remove();
            return count;
        }
        
        private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {
            s.defaultReadObject();
            readHolds = new ThreadLocalHoldCounter();
            setState(0); 
        }
        /**获取state*/
        final int getCount() { return getState(); }
    }
    
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = -8159625535654395037L;
        final boolean writerShouldBlock() {
            return false; 
        }
        /**head节点是获取锁的节点，所以head一定是在运行，而head后续节点肯定是等待获取锁，此时如果head的next要申请的是独占锁，那么此时读锁需要阻塞*/
        final boolean readerShouldBlock() {
        	//如果当前head节点的next节点为申请独占锁，此时返回true
            return apparentlyFirstQueuedIsExclusive();
        }
    }
    
    static final class FairSync extends Sync {
        private static final long serialVersionUID = -2274990926593161451L;
        final boolean writerShouldBlock() {
        	//判断当前线程是否有prev节点，如果有则返回true，如果当前线程是head或者队列为空则返回false
            return hasQueuedPredecessors();
        }
        final boolean readerShouldBlock() {
        	//判断当前线程是否有prev节点，如果有则返回true，如果当前线程是head或者队列为空则返回false
            return hasQueuedPredecessors();
        }
    }
    
    public static class ReadLock implements Lock, java.io.Serializable {
        private static final long serialVersionUID = -5992448646407690164L;
        private final Sync sync;
        
        protected ReadLock(ReentrantReadWriteLock lock) {
            sync = lock.sync;
        }
        /**获取一把共享锁，如果当前线程node的下一个节点也是共享锁线程，那么也同样唤醒下一个节点，否则就不唤醒*/
        public void lock() {
        	//acquireShared会获取一把共享锁，如果当前线程node的下一个节点也是共享锁线程，那么也同样唤醒下一个节点，否则就不唤醒
            sync.acquireShared(1);
        }
        /**获取一把共享锁，如果当前线程node的下一个节点也是共享锁线程并且node当前是SIGNAL状态，那么也同样唤醒下一个节点，否则就不唤醒，线程被中断则会抛异常*/
        public void lockInterruptibly() throws InterruptedException {
        	//acquireSharedInterruptibly会获取一把共享锁，如果当前线程node的下一个节点也是共享锁线程，那么也同样唤醒下一个节点，否则就不唤醒
        	//线程被中断则会抛异常
            sync.acquireSharedInterruptibly(1);
        }
        /**尝试获取读锁*/
        public boolean tryLock() {
            return sync.tryReadLock();
        }
        /**在一段超时等待期内尝试获取读锁，如果当前线程node的下一个节点也是共享锁线程并且node当前是SIGNAL状态，那么也同样唤醒下一个节点，否则就不唤醒，线程被中断则会抛异常*/
        public boolean tryLock(long timeout, TimeUnit unit)
                throws InterruptedException {
            return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
        }
        /**释放共享锁*/
        public void unlock() {
            sync.releaseShared(1);
        }
        /**读锁都是共享锁，所以没有竞争，也就不需要condition*/
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
        
        public String toString() {
            int r = sync.getReadLockCount();
            return super.toString() +
                "[Read locks = " + r + "]";
        }
    }
    
    public static class WriteLock implements Lock, java.io.Serializable {
        private static final long serialVersionUID = -4992448646407690164L;
        private final Sync sync;
        
        protected WriteLock(ReentrantReadWriteLock lock) {
            sync = lock.sync;
        }
        
        public void lock() {
            sync.acquire(1);
        }
        
        public void lockInterruptibly() throws InterruptedException {
            sync.acquireInterruptibly(1);
        }
        
        public boolean tryLock( ) {
            return sync.tryWriteLock();
        }
        
        public boolean tryLock(long timeout, TimeUnit unit)
                throws InterruptedException {
            return sync.tryAcquireNanos(1, unit.toNanos(timeout));
        }
        
        public void unlock() {
            sync.release(1);
        }
        
        public Condition newCondition() {
            return sync.newCondition();
        }
        
        public String toString() {
            Thread o = sync.getOwner();
            return super.toString() + ((o == null) ?
                                       "[Unlocked]" :
                                       "[Locked by thread " + o.getName() + "]");
        }
        
        public boolean isHeldByCurrentThread() {
            return sync.isHeldExclusively();
        }
        
        public int getHoldCount() {
            return sync.getWriteHoldCount();
        }
    }
    
    
    public final boolean isFair() {
        return sync instanceof FairSync;
    }
    
    protected Thread getOwner() {
        return sync.getOwner();
    }
    
    public int getReadLockCount() {
        return sync.getReadLockCount();
    }
    
    public boolean isWriteLocked() {
        return sync.isWriteLocked();
    }
    
    public boolean isWriteLockedByCurrentThread() {
        return sync.isHeldExclusively();
    }
    
    public int getWriteHoldCount() {
        return sync.getWriteHoldCount();
    }
    
    public int getReadHoldCount() {
        return sync.getReadHoldCount();
    }
    
    protected Collection<Thread> getQueuedWriterThreads() {
        return sync.getExclusiveQueuedThreads();
    }
    
    protected Collection<Thread> getQueuedReaderThreads() {
        return sync.getSharedQueuedThreads();
    }
    
    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }
    
    public final boolean hasQueuedThread(Thread thread) {
        return sync.isQueued(thread);
    }
    
    public final int getQueueLength() {
        return sync.getQueueLength();
    }
    
    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }
    
    public boolean hasWaiters(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.hasWaiters((AbstractQueuedSynchronizer.ConditionObject)condition);
    }
    
    public int getWaitQueueLength(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitQueueLength((AbstractQueuedSynchronizer.ConditionObject)condition);
    }
    
    protected Collection<Thread> getWaitingThreads(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitingThreads((AbstractQueuedSynchronizer.ConditionObject)condition);
    }
    
    public String toString() {
        int c = sync.getCount();
        int w = Sync.exclusiveCount(c);
        int r = Sync.sharedCount(c);
        return super.toString() +
            "[Write locks = " + w + ", Read locks = " + r + "]";
    }
    
    static final long getThreadId(Thread thread) {
        return UNSAFE.getLongVolatile(thread, TID_OFFSET);
    }
    
    private static final sun.misc.Unsafe UNSAFE;
    private static final long TID_OFFSET;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> tk = Thread.class;
            TID_OFFSET = UNSAFE.objectFieldOffset
                (tk.getDeclaredField("tid"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }
}