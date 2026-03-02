package pro.jk.ejoker.common.system.wrapper;

import java.util.concurrent.TimeUnit;

import pro.jk.ejoker.common.legacy.Suspendable;

/**
 * 這個是之前對quasar的調用的sleep的封裝，但是以後不需要了
 * <br /> 工程中有啊較多使用這個的地方，這裡就保留為 TimeUnit#sleep 的委託調用算了；
 */
public class DiscardWrapper {
	
	/**
	 * 
	 * 若遇到中断请求，只会结束sleep调用，清除标志位，不对原流程造成影响，注意语义哦 <br /><br />
	 * 方便那些不想写 try{} catch(InterruptedException) {} 语句块的
	 * 
	 * @param millis
	 */
	@Suspendable
	public static void sleepInterruptable(long millis) {
		sleepInterruptable(TimeUnit.MILLISECONDS, millis);
	}
	
	/**
	 * 
	 * 若遇到中断请求，只会结束sleep调用，不对原流程造成影响，注意语义哦 <br /><br />
	 * 方便那些不想写 try{} catch(InterruptedException) {} 语句块的
	 * 
	 * @param unit
	 * @param millis
	 */
	@Suspendable
	public static void sleepInterruptable(TimeUnit unit, long millis) {
		try {
			unit.sleep(millis);
		} catch (InterruptedException e) {
			// do nothing
		}
	}

	@Suspendable
	public static void sleep(long millis) throws InterruptedException {
		sleep(TimeUnit.MILLISECONDS, millis);
	}

	@Suspendable
	public static void sleep(TimeUnit unit, long millis) throws InterruptedException {
		unit.sleep(millis);
	}

}
