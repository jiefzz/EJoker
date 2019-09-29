package pro.jiefzz.ejoker.z.system.helper;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import pro.jiefzz.ejoker.z.system.functional.IVoidFunction;
import pro.jiefzz.ejoker.z.system.functional.IVoidFunction1;
import pro.jiefzz.ejoker.z.system.wrapper.DiscardWrapper;

/**
 * 等待期望值 - 盲等
 * * 请问java能用inline内联函数吗？
 * @author kimffy
 *
 */
public final class AcquireHelper {

	public static void waitAcquire(AtomicBoolean ab, long msPerLoop, IVoidFunction loopAction) {
		while (ab.get()) {
			loopAction.trigger();
			DiscardWrapper.sleepInterruptable(TimeUnit.MILLISECONDS, msPerLoop);
		}
	}

	public static void waitAcquire(AtomicBoolean ab, long msPerLoop) {
		while (ab.get()) {
			DiscardWrapper.sleepInterruptable(TimeUnit.MILLISECONDS, msPerLoop);
		}
	}

	public static void waitAcquire(AtomicBoolean ab, long msPerLoop, IVoidFunction1<Integer> loopAction) {
		int i = -1;
		while (ab.get()) {
			loopAction.trigger(++i);
			DiscardWrapper.sleepInterruptable(TimeUnit.MILLISECONDS, msPerLoop);
		}
	}

	public static void waitAcquire(AtomicBoolean ab, boolean expect, long msPerLoop, IVoidFunction loopAction) {
		while (expect != ab.get()) {
			loopAction.trigger();
			DiscardWrapper.sleepInterruptable(TimeUnit.MILLISECONDS, msPerLoop);
		}
	}

	public static void waitAcquire(AtomicBoolean ab, boolean expect, long msPerLoop) {
		while (expect != ab.get()) {
			DiscardWrapper.sleepInterruptable(TimeUnit.MILLISECONDS, msPerLoop);
		}
	}

	public static void waitAcquire(AtomicBoolean ab, boolean expect, long msPerLoop, IVoidFunction1<Integer> loopAction) {
		int i = -1;
		while (expect != ab.get()) {
			loopAction.trigger(++i);
			DiscardWrapper.sleepInterruptable(TimeUnit.MILLISECONDS, msPerLoop);
		}
	}
}
