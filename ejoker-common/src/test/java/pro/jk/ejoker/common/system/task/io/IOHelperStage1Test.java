package pro.jk.ejoker.common.system.task.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
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

	@Test
	public void testLegacyPublicConstructorsRemainCompatible() {
		IOHelper.IOHelperContext<Void> context = new IOHelper.IOHelperContext<>(
				"LegacyConstructor",
				successfulAction(new AtomicInteger(0)),
				c -> { },
				r -> { },
				NO_CONTEXT,
				(c, e, msg) -> { },
				true,
				4,
				250L
				);

		assertEquals(4, context.retryPolicy.getMaxAttempts(), "旧 public 构造器应保留并映射到 maxAttempts");
		assertEquals(250L, context.retryPolicy.getBaseDelayMillis(), "旧 public 构造器应保留并映射到 baseDelayMillis");
	}

	/**
	 * per-call-site 覆盖: 同一 IOHelper 实例上，不同调用点可以使用不同的重试策略。
	 * <p>
	 * 背景: IOHelper 由 IoC 装配为单例，策略默认来自 ControlTable 全局配置。
	 * 但「命令提交要重试 ES 冲突」与「快照读取失败即失败」这类差异无法用全局配置表达。
	 */
	@Test
	public void testPerCallSiteRetryPolicyOverride() {
		AtomicInteger globalInvocations = new AtomicInteger(0);
		AtomicInteger overriddenInvocations = new AtomicInteger(0);
		AtomicInteger failedInvocations = new AtomicInteger(0);

		// 全局策略：最多 3 次
		IOHelper ioHelper = new IOHelper(new RetryPolicy(3, 0L, 0L, false, () -> 0.0d, null));

		// 调用点 A：沿用全局策略
		ioHelper.tryAsyncAction2(
				"GlobalPolicy",
				ioFailureAction(globalInvocations),
				r -> { },
				NO_CONTEXT,
				e -> failedInvocations.incrementAndGet(),
				true
				);

		// 调用点 B：覆盖为「不重试」
		ioHelper.withRetryPolicy(RetryPolicy.none()).tryAsyncAction2(
				"OverriddenPolicy",
				ioFailureAction(overriddenInvocations),
				r -> { },
				NO_CONTEXT,
				e -> failedInvocations.incrementAndGet(),
				true
				);

		assertEquals(3, globalInvocations.get(), "沿用全局策略的调用点应执行 3 次");
		assertEquals(1, overriddenInvocations.get(), "覆盖为 none() 的调用点应只执行 1 次");
		assertEquals(2, failedInvocations.get(), "两个调用点都应各自进入一次终态失败");
	}

	/**
	 * per-call-site 覆盖不应污染全局单例：withRetryPolicy 返回新实例。
	 */
	@Test
	public void testWithRetryPolicyDoesNotMutateOriginalInstance() {
		IOHelper ioHelper = new IOHelper(new RetryPolicy(3, 0L, 0L, false, () -> 0.0d, null));
		IOHelper overridden = ioHelper.withRetryPolicy(RetryPolicy.withMaxAttempts(7));

		assertNotSame(ioHelper, overridden, "withRetryPolicy 应返回新实例");
		assertEquals(3, ioHelper.getRetryPolicy().getMaxAttempts(), "原实例的策略不应被改写");
		assertEquals(7, overridden.getRetryPolicy().getMaxAttempts(), "新实例应使用覆盖后的策略");
		assertSame(ioHelper, ioHelper.withRetryPolicy(null), "传入 null 应返回原实例");
	}

	/**
	 * 黑名单模型下，通用 RuntimeException 也应重试（有界），而不是一次就判终态。
	 * <p>
	 * 这与「IO 异常才重试」的旧行为不同：ES 乐观并发冲突继承自 RuntimeException 而非
	 * IOException，白名单模型会把它漏掉。
	 */
	@Test
	public void testGenericRuntimeExceptionIsRetriedUnderBlacklistModel() {
		AtomicInteger mainActionInvocations = new AtomicInteger(0);
		AtomicInteger failedInvocations = new AtomicInteger(0);

		IOHelper ioHelper = new IOHelper(new RetryPolicy(3, 0L, 0L, false, () -> 0.0d, null));

		ioHelper.tryAsyncAction2(
				"GenericRuntimeFailure",
				() -> {
					mainActionInvocations.incrementAndGet();
					throw new RuntimeException("unknown transient failure");
				},
				r -> { },
				NO_CONTEXT,
				e -> failedInvocations.incrementAndGet(),
				true
				);

		assertEquals(3, mainActionInvocations.get(), "黑名单模型下普通 RuntimeException 也应有界重试");
		assertEquals(1, failedInvocations.get(), "预算耗尽后应进入一次终态失败");
	}

	/**
	 * 取消（CancellationException）必须显式终态化，不依赖「其继承 IllegalStateException
	 * 恰好命中默认黑名单」的巧合 —— 即使调用点自定义 retryOn 谓词判其为可重试，
	 * 也不得重放 mainAction（防双发）。
	 */
	@Test
	public void testCancellationGoesTerminalEvenWhenRetryOnSaysRetry() {
		AtomicInteger mainActionInvocations = new AtomicInteger(0);
		AtomicInteger failedInvocations = new AtomicInteger(0);
		AtomicReference<Exception> capturedException = new AtomicReference<>();
		AtomicReference<String> capturedMsg = new AtomicReference<>();

		// retryOn 一律返回 true 的自定义策略 —— 模拟自定义谓词判取消可重试的最严苛场景
		IOHelper ioHelper = new IOHelper(new RetryPolicy(3, 0L, 0L, false, () -> 0.0d, t -> true));

		ioHelper.tryAsyncAction2(
				"CancelledFuture",
				() -> {
					mainActionInvocations.incrementAndGet();
					// 使用真实已取消 Future，覆盖 get() -> CancellationException -> processTaskException 的完整链路。
					CompletableFuture<Void> cancelledTask = new CompletableFuture<>();
					cancelledTask.cancel(true);
					return cancelledTask;
				},
				r -> { },
				NO_CONTEXT,
				(c, e, msg) -> {
					failedInvocations.incrementAndGet();
					capturedException.set(e);
					capturedMsg.set(msg);
				},
				true
				);

		assertEquals(1, mainActionInvocations.get(), "取消绝不能触发重试/重放 mainAction（防双发）");
		assertEquals(1, failedInvocations.get(), "取消应恰好进入一次终态失败");
		assertTrue(capturedException.get() instanceof CancellationException, "faildAction 应收到 CancellationException 本尊");
		assertNotNull(capturedMsg.get(), "errorMsg 应为描述性信息而非 null（get() 抛出的裸 CancellationException 无 message）");
		assertTrue(capturedMsg.get().contains("CancelledFuture"), "errorMsg 应包含任务名");
	}
}
