package java.util.concurrent.locks;
import java.util.concurrent.TimeUnit;
import java.util.Collection;
public class ReentrantLock implements Lock, java.io.Serializable {
    private static final long serialVersionUID = 7373984872572414699L;
    
    private final Sync sync;
    /**Sync继承自AbstractQueuedSynchronizer抽象类（简称AQS），AbstractQueuedSynchronizer抽象类定义了基本的锁获取释放的API*/
    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = -5179523762034025860L;
        
        abstract void lock();
        
        /**
         * description.非公平锁尝试获取锁
         * 1.如果当前state为0即表示当前没有线程获取锁，接着第二次尝试compareAndSetState获取锁（第一次在NonfairSync.lock()处获取锁）
         * 2.如果当前线程等于独占锁的线程，即代表当前线程是再次获取锁的情况，即重入，此刻state需要叠加
         * 3.否则返回false
         */
        final boolean nonfairTryAcquire(int acquires) {
            final Thread current = Thread.currentThread();
            int c = getState();//当前锁计数器,0代表线程未获取锁,1代表以获取,大于1代表线程重新获取过锁,即重入
            if (c == 0) {//如果为0代表现在没有线程获取锁，所以当前线程立即获取锁并更新state
                if (compareAndSetState(0, acquires)) {//立即更新状态，并且设置当前线程独占该锁
                    setExclusiveOwnerThread(current);
                    return true;
                }
            }
            else if (current == getExclusiveOwnerThread()) {//如果当前线程等于独占锁的线程，即代表当前线程是再次获取锁的情况，即重入
                int nextc = c + acquires;
                if (nextc < 0) //整数越界，Integer.MAX_VALUE + 1会变成负数
                    throw new Error("Maximum lock count exceeded");
                setState(nextc);//这里为什么不用CAS，其实是因为当前线程本身就已经是获取到该锁了，不可能在同一时刻还有另外一个线程获取该锁，所以这里绝对线程安全，也就不用CAS更新值
                return true;
            }
            return false;
        }
        /**尝试释放锁，每释放一次，则锁计数减去releases的值，如果某线程锁计数器大于1表示重入获取锁多次，则该线程同样要多次释放锁，这点需要注意*/
        protected final boolean tryRelease(int releases) {
            int c = getState() - releases;
            if (Thread.currentThread() != getExclusiveOwnerThread())//当前线程不是获取锁的线程，则抛出异常
                throw new IllegalMonitorStateException();
            boolean free = false;
            if (c == 0) {//如果为0表示当前锁已经是free，如果不为0代表锁还没完全释放掉需要多次执行release操作
                free = true;
                setExclusiveOwnerThread(null);
            }
            setState(c);
            return free;
        }
        
        /**是否是当前线程独占该锁，如果是在读锁（共享锁）的情况下我们就不需要确认当前锁是否是独占状态*/
        protected final boolean isHeldExclusively() {
            // While we must in general read state before owner,
            // we don't need to do so to check if current thread is owner
            return getExclusiveOwnerThread() == Thread.currentThread();
        }
        
        /**Condition对象，该类在AQS内部定义，实现了Condition接口*/
        final ConditionObject newCondition() {
            return new ConditionObject();
        }
        /**获取当前获取锁的线程，如果锁计数器为0代表当前没有任何线程获取锁，所以返回null*/
        final Thread getOwner() {
            return getState() == 0 ? null : getExclusiveOwnerThread();
        }
        /**获取当前线程的锁计数器，如果当前线程未获取锁，即isHeldExclusively()为false，则返回0*/
        final int getHoldCount() {
            return isHeldExclusively() ? getState() : 0;
        }
        /**判断当前锁是否是被其他线程占用，计数器大于0即表示被占用，计数器不可能小于0*/
        final boolean isLocked() {
            return getState() != 0;
        }
        
        private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {
            s.defaultReadObject();
            setState(0); //重置当前锁的计数器state为0
        }
    }
    
    /**非公平的Sync*/
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = 7316153563782823691L;
        
        /***
         * compareAndSetState(0, 1)表示第一次主动获取锁，返回true表示获取锁成功，否则执行acquire
         */
        final void lock() {//获取锁
            if (compareAndSetState(0, 1))
                setExclusiveOwnerThread(Thread.currentThread());//将当前线程设置为独占锁的线程
            else//否则尝试获取一次锁,acquire(int args)是AQS的内部方法
                acquire(1);
        }
        /**尝试获取锁，以非公平的方式获取，不管是否获取锁都会立即返回*/
        protected final boolean tryAcquire(int acquires) {
            return nonfairTryAcquire(acquires);
        }
    }
    /**公平的Sync*/
    static final class FairSync extends Sync {
        private static final long serialVersionUID = -3000897897090466540L;
        /**
         * 和非公平锁不一样的地方就是直接acquire，而不是事先尝试获取锁
         */
        final void lock() {
            acquire(1);
        }
        
        protected final boolean tryAcquire(int acquires) {
            final Thread current = Thread.currentThread();
            int c = getState();
            if (c == 0) {
                if (!hasQueuedPredecessors() &&//通过hasQueuedPredecessors判断同步队列是否有等待线程
                    compareAndSetState(0, acquires)) {//如果当前锁计数器为0（即当前锁没有被任何线程占用，当前线程此次立即获取锁），将锁计数器设置为acquires
                    setExclusiveOwnerThread(current);//当前线程获得锁
                    return true;
                }
            }
            else if (current == getExclusiveOwnerThread()) {
                int nextc = c + acquires;
                if (nextc < 0)
                    throw new Error("Maximum lock count exceeded");
                setState(nextc);
                return true;
            }
            return false;
        }
    }
    
    public ReentrantLock() {
        sync = new NonfairSync();
    }
    
    public ReentrantLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
    }
    
    public void lock() {
        sync.lock();
    }
    
    public void lockInterruptibly() throws InterruptedException {
        sync.acquireInterruptibly(1);
    }
    
    public boolean tryLock() {
        return sync.nonfairTryAcquire(1);
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
    
    public int getHoldCount() {
        return sync.getHoldCount();
    }
    
    public boolean isHeldByCurrentThread() {
        return sync.isHeldExclusively();
    }
    
    public boolean isLocked() {
        return sync.isLocked();
    }
    
    public final boolean isFair() {
        return sync instanceof FairSync;
    }
    
    protected Thread getOwner() {
        return sync.getOwner();
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
        Thread o = sync.getOwner();
        return super.toString() + ((o == null) ?
                                   "[Unlocked]" :
                                   "[Locked by thread " + o.getName() + "]");
    }
}
