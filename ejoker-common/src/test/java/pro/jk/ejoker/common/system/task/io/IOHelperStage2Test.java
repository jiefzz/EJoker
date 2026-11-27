package pro.jk.ejoker.common.system.task.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import pro.jk.ejoker.common.system.exceptions.InterruptedOnRuntime;
import pro.jk.ejoker.common.system.extension.acrossSupport.EJokerFutureUtil;
import pro.jk.ejoker.common.system.functional.IFunction;

/**
 * 锁住 Stage 2「中断强制退出」契约。
 * <br /><br />
 * 契约内容（详见 {@link InterruptedOnRuntime} 类 javadoc）：
 * <ol>
 * <li>主线程自己阻塞点被中断（{@code InterruptedOnRuntime} 直达 catch 现场）⇒ 强制退出：
 *     异常上抛穿透 {@code tryAsyncAction2}，不执行任何 hook、不退避不重试、抛出前恢复中断标志；</li>
 * <li>任务侧中断（任务体在<b>工作线程</b>被中断、经 {@code ExecutionException} 信封送达）
 *     ⇒ 普通任务失败：交 {@code retryOn} 裁决（默认判可重试），<b>绝不给等待线程伪造中断标志</b>；</li>
 * <li>判据是【类型】不是 cause —— 无 cause 的 {@code InterruptedOnRuntime} 同样强制退出。</li>
 * </ol>
 * 回归形态提示：若外层 catch 的【放行通道】被移除，强制退出异常会被兜底吞掉、
 * finishFlag 未置，do/while 无限重放 mainAction —— 各用例的 mainAction 内置硬闸
 * （Error 穿透 IOHelper 两层 catch 响亮失败，不依赖 @Timeout）。
 *
 * @author JiefzzLon
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class IOHelperStage2Test {

	private final IOHelper ioHelper = new IOHelper(RetryPolicy.defaultPolicy());

	/** 小退避策略：重试场景不拖慢测试，同时保留足够的重试预算。 */
	private final IOHelper fastRetryIoHelper = new IOHelper(new RetryPolicy(5, 1L, 1L, false, null, null));

	private final static IFunction<String> NO_CONTEXT = () -> "unit-test";

	private final static int MAX_REPLAY = 5;

	private static void guardReplay(AtomicInteger counter, String describe) {
		int invocations = counter.incrementAndGet();
		if (invocations > MAX_REPLAY) {
			throw new AssertionError("IOHelper 回归: [" + describe + "] 的 mainAction 已被重放 "
					+ invocations + " 次（硬闸上限 " + MAX_REPLAY + "）。大概率是中断强制退出异常"
					+ "被外层兜底 catch 吞掉、finishFlag 未置所致 —— 放行通道被改坏了。");
		}
	}

	/** 即使断言在中断标志清理前失败，也不能把标志泄漏给同线程执行的后续用例。 */
	@AfterEach
	void clearInterruptFlag() {
		Thread.interrupted();
	}

	/**
	 * 契约1（等待侧·真实 await）: 主线程在 Future.get() 上被中断 ⇒ 强制退出。
	 * 包装异常携带原始 InterruptedException，两个 hook 均不执行，且 IOHelper 不管理已提交的 Future。
	 */
	@Test
	public void testAwaitSideInterruptForcesExitWithoutHook() {

		AtomicInteger mainActionInvocations = new AtomicInteger(0);
		AtomicInteger failedInvocations = new AtomicInteger(0);
		AtomicInteger completeInvocations = new AtomicInteger(0);
		CompletableFuture<Void> pendingTask = new CompletableFuture<>();

		// get() 观察到预置标志后立即抛 InterruptedException，无需引入额外线程和时序等待。
		Thread.currentThread().interrupt();
		InterruptedOnRuntime thrown = assertThrows(InterruptedOnRuntime.class,
				() -> ioHelper.tryAsyncAction2(
						"AwaitSideInterrupt",
						() -> {
							guardReplay(mainActionInvocations, "等待侧中断强制退出");
							return pendingTask;
						},
						() -> completeInvocations.incrementAndGet(),
						NO_CONTEXT,
						e -> failedInvocations.incrementAndGet()));

		assertTrue(thrown.getCause() instanceof InterruptedException,
				"await 中断应保留原始 InterruptedException，实际 cause: " + thrown.getCause());
		assertEquals(1, mainActionInvocations.get(), "强制退出经异常穿透 do/while，mainAction 恰执行一次");
		assertEquals(0, failedInvocations.get(), "中断强制退出不得触发 faildAction hook");
		assertEquals(0, completeInvocations.get(), "中断强制退出不得触发 completeAction hook");
		assertFalse(pendingTask.isDone(), "等待侧强制退出不得取消或完成已提交的 Future");
		assertTrue(Thread.interrupted(), "放行通道应在抛出前恢复中断标志");
	}

	/**
	 * 契约3: 判据是类型不是 cause —— 无 cause 的 InterruptedOnRuntime 同样强制退出，
	 * 不得因 cause 缺失而误入失败/重试路径。
	 */
	@Test
	public void testNoCauseInterruptedOnRuntimeAlsoForcesExit() {

		AtomicInteger mainActionInvocations = new AtomicInteger(0);
		AtomicInteger failedInvocations = new AtomicInteger(0);

		assertThrows(InterruptedOnRuntime.class,
				() -> ioHelper.tryAsyncAction2(
						"NoCauseInterruptedOnRuntime",
						() -> {
							guardReplay(mainActionInvocations, "无 cause 中断强制退出");
							throw new InterruptedOnRuntime();
						},
						() -> { },
						NO_CONTEXT,
						e -> failedInvocations.incrementAndGet()));

		assertEquals(1, mainActionInvocations.get(), "无 cause 形态同样应经异常一次穿透，不得重放");
		assertEquals(0, failedInvocations.get(), "无 cause 形态同样不得触发 faildAction hook");
		assertTrue(Thread.interrupted(), "无 cause 形态同样应恢复中断标志");
	}

	/**
	 * 契约1（退避侧）: 主线程在退避 sleep 上被中断 ⇒ 同样强制退出（包装 InterruptedOnRuntime 上抛），
	 * 不走「终态化 + faildAction」的旧路径。
	 * <p>
	 * 时序构造：mainAction 先给当前线程置中断标志再抛可重试异常 —— 进入退避 sleep 时
	 * 标志尚在，sleep 立即抛 InterruptedException，即模拟「退避中被外部中断」。
	 */
	@Test
	public void testBackoffInterruptForcesExitWithoutHook() {

		AtomicInteger mainActionInvocations = new AtomicInteger(0);
		AtomicInteger failedInvocations = new AtomicInteger(0);

		InterruptedOnRuntime thrown = assertThrows(InterruptedOnRuntime.class,
				() -> fastRetryIoHelper.tryAsyncAction2(
						"BackoffInterrupt",
						() -> {
							guardReplay(mainActionInvocations, "退避中断强制退出");
							Thread.currentThread().interrupt();
							throw IOExceptionOnRuntime.encapsulation(new IOException("io failed, enter retry"));
						},
						() -> { },
						NO_CONTEXT,
						e -> failedInvocations.incrementAndGet(),
						true));

		assertTrue(thrown.getCause() instanceof InterruptedException,
				"退避中断应包装原始 InterruptedException，实际 cause: " + thrown.getCause());
		assertEquals(1, mainActionInvocations.get(), "退避中断应异常一次穿透，不得回到循环重放 mainAction");
		assertEquals(0, failedInvocations.get(), "退避中断不得触发 faildAction hook（旧终态化路径已退役）");
		assertTrue(Thread.interrupted(), "放行通道应在抛出前恢复中断标志");
	}

	/**
	 * 契约2（任务侧·终态）: 任务体在工作线程被中断、经 ExecutionException 信封送达 ⇒
	 * 普通任务失败：retryWhenFailed=false 时终态化，faildAction 收到裸 InterruptedException
	 * （凭类型可判），且<b>不给等待线程伪造中断标志</b>。
	 */
	@Test
	public void testTaskSideInterruptGoesOrdinaryFailureWithoutFlag() {

		AtomicInteger mainActionInvocations = new AtomicInteger(0);
		AtomicInteger failedInvocations = new AtomicInteger(0);
		AtomicReference<Exception> captured = new AtomicReference<>();

		ioHelper.tryAsyncAction2(
				"TaskSideInterruptTerminal",
				() -> {
					guardReplay(mainActionInvocations, "任务侧中断终态");
					// 模拟任务体在【工作线程】上被中断：经 FutureTask → ExecutionException 信封送达
					return CompletableFuture.<Void>failedFuture(
							new InterruptedException("worker thread was interrupted"));
				},
				() -> { /* completeAction: 不应被走到 */ },
				NO_CONTEXT,
				e -> {
					failedInvocations.incrementAndGet();
					captured.set(e);
				});

		assertEquals(1, mainActionInvocations.get(), "retryWhenFailed=false 时任务侧中断应一次终态化");
		assertEquals(1, failedInvocations.get(), "任务侧中断作为普通失败应触发 faildAction");
		assertTrue(captured.get() instanceof InterruptedException,
				"回调应收到裸 InterruptedException（信封解出、凭类型可判），实际为: " + captured.get());
		assertFalse(Thread.interrupted(),
				"任务侧中断是任务侧自己的失败，绝不能给等待线程伪造中断标志");
	}

	/**
	 * 契约2（任务侧·可重试）: 任务侧中断在默认 retryOn 黑名单外 ⇒ 判可重试，
	 * 重放 mainAction 可恢复成功 —— 锁定「任务侧中断 ≠ 主线程被请求停止」的重试语义。
	 */
	@Test
	public void testTaskSideInterruptIsRetryableAndCanRecover() {

		AtomicInteger mainActionInvocations = new AtomicInteger(0);
		AtomicInteger completeInvocations = new AtomicInteger(0);
		AtomicInteger failedInvocations = new AtomicInteger(0);

		fastRetryIoHelper.tryAsyncAction2(
				"TaskSideInterruptRetry",
				() -> {
					guardReplay(mainActionInvocations, "任务侧中断重试恢复");
					if (1 == mainActionInvocations.get()) {
						// 第一次：任务体在【工作线程】被中断（ExecutionException 信封形态）
						return CompletableFuture.<Void>failedFuture(
								new InterruptedException("worker thread was interrupted"));
					}
					// 重放后恢复成功
					return EJokerFutureUtil.completeFuture();
				},
				() -> completeInvocations.incrementAndGet(),
				NO_CONTEXT,
				e -> failedInvocations.incrementAndGet(),
				true);

		assertEquals(2, mainActionInvocations.get(), "任务侧中断应判可重试并重放 mainAction 恰一次");
		assertEquals(1, completeInvocations.get(), "重放成功后应正常触发 completeAction");
		assertEquals(0, failedInvocations.get(), "恢复成功的场景不应触发 faildAction");
		assertFalse(Thread.interrupted(), "重试任务侧中断的全程都不应置等待线程的中断标志");
	}

	/**
	 * faildAction 的普通 RuntimeException 会被隔离，但 InterruptedOnRuntime 是强制退出控制信号，
	 * 必须继续原样穿透，不能被 hook 异常兜底吞掉。
	 */
	@Test
	public void testInterruptedOnRuntimeFromFailedActionForcesExit() {

		AtomicInteger mainActionInvocations = new AtomicInteger(0);
		AtomicInteger completeInvocations = new AtomicInteger(0);
		AtomicInteger failedInvocations = new AtomicInteger(0);
		InterruptedOnRuntime sent = new InterruptedOnRuntime(new InterruptedException("failed hook interrupted"));

		InterruptedOnRuntime thrown = assertThrows(InterruptedOnRuntime.class,
				() -> ioHelper.tryAsyncAction2(
						"FailedActionInterrupt",
						() -> {
							guardReplay(mainActionInvocations, "失败 hook 中断穿透");
							throw IOExceptionOnRuntime.encapsulation(new IOException("original io failure"));
						},
						() -> completeInvocations.incrementAndGet(),
						NO_CONTEXT,
						e -> {
							failedInvocations.incrementAndGet();
							throw sent;
						}));

		assertSame(sent, thrown, "faildAction 抛出的强制退出异常应原样上抛");
		assertEquals(1, mainActionInvocations.get(), "强制退出后不得重放 mainAction");
		assertEquals(1, failedInvocations.get(), "faildAction 应只进入一次");
		assertEquals(0, completeInvocations.get(), "失败路径不得触发 completeAction");
		assertTrue(Thread.interrupted(), "hook 的强制退出异常同样应恢复中断标志");
	}

	/**
	 * completeAction 中的 InterruptedOnRuntime 也不能被普通 hook 异常日志兜底吞掉；
	 * IO 已成功这一事实意味着不得调用 faildAction，也不得重放 mainAction。
	 */
	@Test
	public void testInterruptedOnRuntimeFromCompleteActionForcesExitWithoutFailedHook() {

		AtomicInteger mainActionInvocations = new AtomicInteger(0);
		AtomicInteger completeInvocations = new AtomicInteger(0);
		AtomicInteger failedInvocations = new AtomicInteger(0);
		InterruptedOnRuntime sent = new InterruptedOnRuntime(new InterruptedException("complete hook interrupted"));

		InterruptedOnRuntime thrown = assertThrows(InterruptedOnRuntime.class,
				() -> ioHelper.tryAsyncAction2(
						"CompleteActionInterrupt",
						() -> {
							guardReplay(mainActionInvocations, "完成 hook 中断穿透");
							return EJokerFutureUtil.completeFuture();
						},
						() -> {
							completeInvocations.incrementAndGet();
							throw sent;
						},
						NO_CONTEXT,
						e -> failedInvocations.incrementAndGet()));

		assertSame(sent, thrown, "completeAction 抛出的强制退出异常应原样上抛");
		assertEquals(1, mainActionInvocations.get(), "IO 已成功，强制退出不得导致 mainAction 重放");
		assertEquals(1, completeInvocations.get(), "completeAction 应只进入一次");
		assertEquals(0, failedInvocations.get(), "completeAction 异常不得把成功的 IO 改判为失败");
		assertTrue(Thread.interrupted(), "hook 的强制退出异常同样应恢复中断标志");
	}
}
