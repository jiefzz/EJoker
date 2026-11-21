package pro.jk.ejoker.common.system.wrapper;

import java.util.concurrent.TimeUnit;

/**
 * 這個是之前對quasar的調用的sleep的封裝，但是以後不需要了
 * <br /> 工程中有啊較多使用這個的地方，這裡就保留為 TimeUnit#sleep 的委託調用算了；
 */
public class DiscardWrapper {
	
	/**
	 * 若遇到中断请求，只结束 sleep 调用并<b>恢复中断标志位</b>，不向上抛异常。<br /><br />
	 * 方便那些不想写 try{} catch(InterruptedException) {} 语句块的。<br /><br />
	 * 注意：调用方有责任通过 {@code Thread.currentThread().isInterrupted()}
	 * 感知中断并决定后续行为，否则取消请求会被本方法截断。<br />
	 * 此外，本方法返回后若标志位已置位，后续任何可中断阻塞调用
	 * (sleep / wait / take / lockInterruptibly 等)会立刻抛出
	 * InterruptedException 并再次清除标志位。
	 *
	 * @param millis
	 */
	public static void sleepQuietly(long millis) {
		sleepQuietly(TimeUnit.MILLISECONDS, millis);
	}

	/**
	 * 若遇到中断请求，只结束 sleep 调用并<b>恢复中断标志位</b>，不向上抛异常。<br /><br />
	 * 方便那些不想写 try{} catch(InterruptedException) {} 语句块的。<br /><br />
	 * 注意：调用方有责任通过 {@code Thread.currentThread().isInterrupted()}
	 * 感知中断并决定后续行为，否则取消请求会被本方法截断。<br />
	 * 此外，本方法返回后若标志位已置位，后续任何可中断阻塞调用
	 * (sleep / wait / take / lockInterruptibly 等)会立刻抛出
	 * InterruptedException 并再次清除标志位。
	 *
	 * @param unit
	 * @param millis
	 */
	public static void sleepQuietly(TimeUnit unit, long millis) {
		try {
			unit.sleep(millis);
		} catch (InterruptedException e) {
			// 恢复中断标志位：InterruptedException 抛出时 JVM 已清除标志，
			// 不补回则中断信号在此层被永久截断，上层 / 线程池无法感知取消。
			Thread.currentThread().interrupt();
		}
	}

	public static void sleep(long millis) throws InterruptedException {
		sleep(TimeUnit.MILLISECONDS, millis);
	}

	public static void sleep(TimeUnit unit, long millis) throws InterruptedException {
		unit.sleep(millis);
	}

}
