package pro.jk.ejoker.common.system.wrapper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 歷史遺留的問題，後續不要用了，全部直接用juc下的CDL即可
 */
public class CountDownLatchWrapper {

	public static CountDownLatch newCountDownLatch() {
		return newCountDownLatch(1);
	}

	public static CountDownLatch newCountDownLatch(int count) {
		return new CountDownLatch(count);
	}

	public static void await(Object handle) throws InterruptedException {
		if(handle instanceof CountDownLatch) {
			((CountDownLatch) handle).await();
		} else {
			throw new IllegalArgumentException("handle is not CountDownLatch");
		}
	}
	
	public static boolean await(Object handle, long timeout, TimeUnit unit) throws InterruptedException {
		if(handle instanceof CountDownLatch) {
			return ((CountDownLatch) handle).await(timeout, unit);
		} else {
			throw new IllegalArgumentException("handle is not CountDownLatch");
		}
	}

	/**
	 * Just clean the interrupt flag and do nothing while interrupt() invoke.
	 * @param handle
	 */
	@SuppressWarnings("deprecation")
	public static void awaitInterruptable(Object handle){
		try {
			await(handle);
		} catch (InterruptedException e) {
			// ...
			// ignore
		}
	}
	
	/**
	 * Just clean the interrupt flag and do nothing while interrupt() invoke.
	 * @param handle
	 * @param timeout
	 * @param unit
	 * @return await enough or not
	 */
	@SuppressWarnings("deprecation")
	public static boolean awaitInterruptable(Object handle, long timeout, TimeUnit unit){
		try {
			return await(handle, timeout, unit);
		} catch (InterruptedException e) {
			// ...
			// ignore
			return false;
		}
	}

	public static void countDown(Object handle) {
		if(handle instanceof CountDownLatch) {
			((CountDownLatch) handle).countDown();
		} else {
			throw new IllegalArgumentException("handle is not CountDownLatch");
		}
	}

}
