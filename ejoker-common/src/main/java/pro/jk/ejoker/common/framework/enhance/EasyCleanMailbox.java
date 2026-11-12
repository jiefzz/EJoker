package pro.jk.ejoker.common.framework.enhance;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


public abstract class EasyCleanMailbox {

	private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
	
	/**
	 * 包装读锁为使用锁
	 * @return
	 */
	public boolean tryUse() {
		return rwLock.readLock().tryLock();
//		return true;
	}
	
	public void releaseUse() {
		rwLock.readLock().unlock();
	}

	/**
	 * 包装写锁为清理锁
	 * @return
	 */
	public boolean tryClean() {
		return rwLock.writeLock().tryLock();
//		return false;
	}
	
	public void releaseClean() {
		rwLock.writeLock().unlock();
	}
	
}
