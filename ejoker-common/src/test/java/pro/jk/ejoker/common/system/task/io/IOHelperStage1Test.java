package pro.jk.ejoker.common.system.task.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import pro.jk.ejoker.common.system.extension.acrossSupport.EJokerFutureUtil;
import pro.jk.ejoker.common.system.functional.IFunction;

/**
 * 锁住 Stage 1：有界重试、retryOn 分类、预算耗尽与退避。
 *
 * @author JiefzzLon
 */
@Timeout(value = 5, unit = TimeUnit.SECONDS)
public class IOHelperStage1Test {

	private static final IFunction<String> NO_CONTEXT = () -> "unit-test";

	private static IFunction<Future<Void>> successfulAction(AtomicInteger counter) {
		return () -> {
			counter.incrementAndGet();
			return EJokerFutureUtil.completeFuture();
		};
	}

	private static IFunction<Future<Void>> ioFailureAction(AtomicInteger counter) {
		return () -> {
			counter.incrementAndGet();
			throw IOExceptionOnRuntime.encapsulation(new IOException("always fail"));
		};
	}

	private static IFunction<Future<Void>> illegalArgumentAction(AtomicInteger counter) {
		return () -> {
			counter.incrementAndGet();
			throw new IllegalArgumentException("non retryable");
		};
	}

	@Test
	public void testBoundedRetryOnRepeatedIOException() {
		AtomicInteger mainActionInvocations = new AtomicInteger(0);
		AtomicInteger failedInvocations = new AtomicInteger(0);

		IOHelper ioHelper = new IOHelper(new RetryPolicy(3, 0L, 0L, false, () -> 0.0d, null));

		ioHelper.tryAsyncAction2(
				"BoundedRetry",
				ioFailureAction(mainActionInvocations),
				r -> { },
				NO_CONTEXT,
				e -> failedInvocations.incrementAndGet(),
				true
				);

		assertEquals(3, mainActionInvocations.get(), "maxAttempts=3 时应恰好执行 3 次");
		assertEquals(1, failedInvocations.get(), "预算耗尽后应恰好进入一次终态失败");
	}

	@Test
	public void testNonRetryableExceptionGoesTerminal() {
		AtomicInteger mainActionInvocations = new AtomicInteger(0);
		AtomicInteger failedInvocations = new AtomicInteger(0);

		IOHelper ioHelper = new IOHelper(new RetryPolicy(3, 0L, 0L, false, () -> 0.0d, null));

		ioHelper.tryAsyncAction2(
				"NonRetryable",
				illegalArgumentAction(mainActionInvocations),
				r -> { },
				NO_CONTEXT,
				e -> failedInvocations.incrementAndGet(),
				true
				);

		assertEquals(1, mainActionInvocations.get(), "IllegalArgumentException 不应触发重试");
		assertEquals(1, failedInvocations.get(), "不可重试异常应直接进入终态失败");
	}

	@Test
	public void testRetryBudgetExhaustionCallsFailedAction() {
		AtomicInteger mainActionInvocations = new AtomicInteger(0);
		AtomicInteger failedInvocations = new AtomicInteger(0);
		AtomicReference<IOHelper.IOHelperContext<Void>> capturedContext = new AtomicReference<>();

		IOHelper ioHelper = new IOHelper(new RetryPolicy(2, 0L, 0L, false, () -> 0.0d, null));

		ioHelper.tryAsyncAction2(
				"BudgetExhausted",
				ioFailureAction(mainActionInvocations),
				r -> { },
				NO_CONTEXT,
				(c, e, msg) -> {
					failedInvocations.incrementAndGet();
					capturedContext.set(c);
				},
				true
				);

		assertEquals(2, mainActionInvocations.get(), "maxAttempts=2 时应恰好执行 2 次");
		assertEquals(1, failedInvocations.get(), "预算耗尽后应进入一次终态失败");
		assertEquals(2, capturedContext.get().getCurrentRetryTimes(), "终态上下文应记录满额重试次数");
	}

	@Test
	public void testBackoffIsAppliedBetweenAttempts() {
		AtomicInteger mainActionInvocations = new AtomicInteger(0);
		AtomicInteger failedInvocations = new AtomicInteger(0);

		IOHelper ioHelper = new IOHelper(new RetryPolicy(2, 10L, 10L, false, () -> 1.0d, null));

		long start = System.nanoTime();
		ioHelper.tryAsyncAction2(
				"Backoff",
				ioFailureAction(mainActionInvocations),
				r -> { },
				NO_CONTEXT,
				e -> failedInvocations.incrementAndGet(),
				true
				);
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

		assertEquals(2, mainActionInvocations.get(), "退避不应改变尝试次数");
		assertEquals(1, failedInvocations.get(), "退避后仍应进入终态失败");
		assertTrue(elapsedMillis >= 10L, "至少应观察到一次 10ms 退避，实际: " + elapsedMillis + "ms");
	}

	@Test
	public void testStage0RegressionStillHolds() {
		AtomicInteger mainActionInvocations = new AtomicInteger(0);

		IOHelper ioHelper = new IOHelper(new RetryPolicy(3, 0L, 0L, false, () -> 0.0d, null));

		ioHelper.tryAsyncAction2(
				"CompleteActionThrows",
				successfulAction(mainActionInvocations),
				r -> { throw new IllegalStateException("completeAction blew up"); },
				NO_CONTEXT,
				e -> { },
				true
				);

		assertEquals(1, mainActionInvocations.get(), "completeAction 失败后绝不能重放已经成功的 mainAction");
	}
}
