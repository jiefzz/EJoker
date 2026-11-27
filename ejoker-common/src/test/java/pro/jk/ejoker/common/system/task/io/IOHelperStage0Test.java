package pro.jk.ejoker.common.system.task.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 * 锁住 IOHelper 的 Stage 0 两处修复。
 * <br /><br />
 * 修复1: {@code taskContinueAction} 中 markFinish() 必须先于 completeAction.trigger()，
 * 否则 completeAction 抛异常时外层 do/while(!isFinish()) 会重放【已经成功】的 mainAction。
 * <br /><br />
 * 修复2: {@code processTaskException} 的 IOException 分支必须尊重 retryWhenFailed，
 * 否则该标志对最常见的异常类型形同虚设。
 * <br /><br />
 * 注意: retryWhenFailed=true 路径是无限重试（Stage 1 才会引入上限），故本类【不覆盖】该路径，
 * 以免测试自身挂死。只覆盖 false（终态）与成功路径。
 *
 * @author JiefzzLon
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class IOHelperStage0Test {

	private final IOHelper ioHelper = new IOHelper(RetryPolicy.defaultPolicy());

	private final static IFunction<String> NO_CONTEXT = () -> "unit-test";

	/**
	 * mainAction 重放次数的硬闸上限。
	 * <p>
	 * 正常路径下 mainAction 只应执行 1 次。若两处修复之一被回滚，
	 * {@code tryAsyncAction2} 的 {@code do/while(!isFinish())} 会无限重放 mainAction，
	 * 而该忙循环【无法被中断】：{@code executeRetryAction} 把 sleep 的
	 * {@code InterruptedException} 包成 {@code RuntimeException} 后又自己 catch 掉，
	 * 中断位被永久清除，于是 JUnit 的 {@code @Timeout} 失效 —— 回归会表现为
	 * 【JVM 挂死】而不是断言失败，CI 上比没有测试更糟。
	 * <p>
	 * 因此这里在 mainAction 内部自己设闸，超过上限即抛 {@link AssertionError}。
	 * 选 Error 而非 Exception 是有意的: IOHelper 的两处 catch 分别只捕 {@code Exception}
	 * 和 {@code RuntimeException}，Error 能穿透 {@code taskContinueAction} 与外层 do/while
	 * 一路冒泡到测试方法，使回归【响亮失败】。
	 * <p>
	 * 阈值取 5 而非 1: 给"合法但非预期"的额外调用留出余量，同时确保在 @Timeout(10s) 内必然触发
	 * （回滚后第 3 次起每轮 sleep 1000ms，5 轮约 3s）。
	 */
	private final static int MAX_REPLAY = 5;

	/**
	 * 重放硬闸: 自增计数，超过 {@link #MAX_REPLAY} 即抛 AssertionError。
	 *
	 * @param counter 调用次数计数器，测试方法据此断言
	 * @param describe 场景描述，仅用于失败信息可读
	 */
	private static void guardReplay(AtomicInteger counter, String describe) {
		int invocations = counter.incrementAndGet();
		if (invocations > MAX_REPLAY) {
			throw new AssertionError("IOHelper 回归: [" + describe + "] 的 mainAction 已被重放 "
					+ invocations + " 次（硬闸上限 " + MAX_REPLAY + "）。外层 do/while 始终未终结，"
					+ "说明 markFinish() 与 completeAction 的先后次序、或 retryWhenFailed 的判定被改坏了。");
		}
	}

	/** 构造一个总是成功返回的 mainAction，并计数其被执行的次数（含重放硬闸）。 */
	private static IFunction<Future<Void>> successfulAction(AtomicInteger counter) {
		return () -> {
			guardReplay(counter, "IO 成功路径");
			return EJokerFutureUtil.completeFuture();
		};
	}

	/**
	 * 基线: 正常情况下 mainAction 只执行一次，completeAction 执行一次，faildAction 不执行。
	 */
	@Test
	public void testSuccessPathExecutesMainActionExactlyOnce() {

		AtomicInteger mainActionInvocations = new AtomicInteger(0);
		AtomicInteger completeInvocations = new AtomicInteger(0);
		AtomicInteger failedInvocations = new AtomicInteger(0);

		ioHelper.tryAsyncAction2(
				"SuccessPath",
				successfulAction(mainActionInvocations),
				() -> completeInvocations.incrementAndGet(),
				NO_CONTEXT,
				e -> failedInvocations.incrementAndGet()
				);

		assertEquals(1, mainActionInvocations.get(), "mainAction 应当只执行一次");
		assertEquals(1, completeInvocations.get(), "completeAction 应当执行一次");
		assertEquals(0, failedInvocations.get(), "成功路径不应触发 faildAction");
	}

	/**
	 * 修复1 的回归测试（核心）。
	 * <p>
	 * completeAction 抛异常时，IO 已经成功落库，绝不能被重放。
	 * 修复前: markFinish 在 completeAction 之后，异常被吞 → finishFlag 仍为 false
	 * → 外层循环重跑 mainAction（把已成功的事件再写一遍），且因 completeAction 每次都抛，
	 * 会无限重放。
	 * <p>
	 * 断言: mainAction 恰好 1 次；调用本身能正常返回（不挂死）。
	 */
	@Test
	public void testCompleteActionFailureMustNotReplayMainAction() {

		AtomicInteger mainActionInvocations = new AtomicInteger(0);

		// completeAction 每次都抛异常
		Runnable explodingCompleteAction = () -> {
			throw new IllegalStateException("completeAction blew up after IO already succeeded");
		};

		// 修复前这里会无限重放 mainAction 从而永远不返回；修复后应当立即返回。
		ioHelper.tryAsyncAction2(
				"CompleteActionThrows",
				successfulAction(mainActionInvocations),
				() -> explodingCompleteAction.run(),
				NO_CONTEXT,
				e -> { /* faildAction: 此路径不应被走到 */ }
				);

		assertEquals(1, mainActionInvocations.get(),
				"completeAction 抛异常后 mainAction 绝不能被重放（IO 已成功，重放即重复写入）");
	}

	/**
	 * 修复1 的补充: completeAction 抛异常时，faildAction 也不应被触发。
	 * <p>
	 * IO 本身是成功的，把它报告为"失败"会让调用方做出错误决策（例如命令被判 Failed 并回滚）。
	 */
	@Test
	public void testCompleteActionFailureMustNotTriggerFailedAction() {

		AtomicInteger failedInvocations = new AtomicInteger(0);

		ioHelper.tryAsyncAction2(
				"CompleteActionThrowsNoFail",
				successfulAction(new AtomicInteger(0)),
				() -> { throw new IllegalStateException("boom"); },
				NO_CONTEXT,
				e -> failedInvocations.incrementAndGet()
				);

		assertEquals(0, failedInvocations.get(),
				"completeAction 抛异常不代表 IO 失败，不应触发 faildAction");
	}

	/**
	 * 修复2 的回归测试。
	 * <p>
	 * retryWhenFailed=false（{@code tryAsyncAction2} 6 参重载的默认值，
	 * 即 DefaultProcessingCommandHandler#handleCommandInternal 的实际用法）时，
	 * IOException 必须走终态而非无限重试。
	 * <p>
	 * 修复前: IOException 分支无条件 executeRetryAction()，无视 retryWhenFailed
	 * → 该测试会无限循环挂死。
	 */
	@Test
	public void testIOExceptionWithRetryWhenFailedFalseGoesTerminal() {

		AtomicInteger mainActionInvocations = new AtomicInteger(0);
		AtomicInteger failedInvocations = new AtomicInteger(0);
		AtomicReference<Exception> captured = new AtomicReference<>();

		ioHelper.tryAsyncAction2(
				"IoExceptionTerminal",
				() -> {
					guardReplay(mainActionInvocations, "IO 异常终态");
					// 模拟事件库不可达: 抛出被包装的 IOException
					throw IOExceptionOnRuntime.encapsulation(new IOException("event store unreachable"));
				},
				() -> { /* completeAction: 不应被走到 */ },
				NO_CONTEXT,
				e -> {
					failedInvocations.incrementAndGet();
					captured.set(e);
				}
				);

		assertEquals(1, mainActionInvocations.get(),
				"retryWhenFailed=false 时 IO 异常不应重试");
		assertEquals(1, failedInvocations.get(),
				"retryWhenFailed=false 时 IO 异常应走终态 faildAction");
		assertTrue(captured.get() instanceof IOExceptionOnRuntime,
				"终态应携带原始异常，实际为: " + captured.get());
	}

	/**
	 * 修复2 的补充: 终态携带的异常应保持原始类型，不被二次包装吞掉，
	 * 以便调用方能据此做失败分类（Stage 1 的 retryOn 谓词依赖这一点）。
	 * <p>
	 * {@link IOExceptionOnRuntime#encapsulation(Throwable)} 对 IOException 入参直接
	 * {@code super(cause)}，故原始异常可由 {@code getCause()} 还原。
	 */
	@Test
	public void testTerminalExceptionPreservesOriginalType() {

		AtomicInteger mainActionInvocations = new AtomicInteger(0);
		AtomicReference<Exception> captured = new AtomicReference<>();
		IOException original = new IOException("db connection reset");

		ioHelper.tryAsyncAction2(
				"PreserveExceptionType",
				() -> {
					guardReplay(mainActionInvocations, "终态异常类型保持");
					throw IOExceptionOnRuntime.encapsulation(original);
				},
				() -> { },
				NO_CONTEXT,
				captured::set
				);

		assertSame(original, captured.get().getCause(),
				"终态异常应可通过 getCause() 还原出原始 IOException");
	}

	/**
	 * faildAction 是普通通知 hook：它自身抛出 RuntimeException 时，不得重放 mainAction、
	 * 不得改走 completeAction，也不得把 hook 异常当作新的任务异常向外抛出。
	 * 进入 hook 前上下文必须已经终态化，否则 hook 异常会再次打开外层 do/while 的重放窗口。
	 */
	@Test
	public void testFailedActionFailureIsIsolatedAfterTerminalState() {

		AtomicInteger mainActionInvocations = new AtomicInteger(0);
		AtomicInteger completeInvocations = new AtomicInteger(0);
		AtomicInteger failedInvocations = new AtomicInteger(0);
		AtomicReference<Exception> captured = new AtomicReference<>();
		AtomicReference<IOHelper.IOHelperContext<Void>> capturedContext = new AtomicReference<>();
		IOExceptionOnRuntime original = IOExceptionOnRuntime.encapsulation(new IOException("original io failure"));

		ioHelper.tryAsyncAction2(
				"FailedActionThrows",
				() -> {
					guardReplay(mainActionInvocations, "失败 hook 异常隔离");
					throw original;
				},
				() -> completeInvocations.incrementAndGet(),
				NO_CONTEXT,
				(c, e, msg) -> {
					failedInvocations.incrementAndGet();
					captured.set(e);
					capturedContext.set(c);
					throw new IllegalStateException("faildAction blew up");
				});

		assertEquals(1, mainActionInvocations.get(), "faildAction 抛异常不得重放已经终态化的 mainAction");
		assertEquals(1, failedInvocations.get(), "faildAction 不得因自身异常被重复调用");
		assertEquals(0, completeInvocations.get(), "任务已经失败，不得因 hook 异常补调 completeAction");
		assertSame(original, captured.get(), "hook 异常不得替换回调收到的原始任务异常");
		assertTrue(capturedContext.get().isFinish(), "调用 faildAction 前必须已经完成终态化");
	}

	/**
	 * contextInfo 允许为 null。日志上下文缺失不得演化成 NPE，更不能掩盖原始任务失败。
	 */
	@Test
	public void testNullContextInfoDoesNotMaskTerminalFailure() {

		AtomicInteger mainActionInvocations = new AtomicInteger(0);
		AtomicReference<Exception> captured = new AtomicReference<>();
		AtomicReference<IOHelper.IOHelperContext<Void>> capturedContext = new AtomicReference<>();
		IOExceptionOnRuntime original = IOExceptionOnRuntime.encapsulation(new IOException("failure with null context"));

		ioHelper.tryAsyncAction2(
				"NullContextInfo",
				() -> {
					guardReplay(mainActionInvocations, "null contextInfo 终态");
					throw original;
				},
				() -> { },
				null,
				(c, e, msg) -> {
					captured.set(e);
					capturedContext.set(c);
				});

		assertEquals(1, mainActionInvocations.get(), "null contextInfo 不得导致任务被重放");
		assertSame(original, captured.get(), "null contextInfo 不得掩盖原始任务异常");
		assertEquals("[EmptyContextInfo:null]", capturedContext.get().contextInfo.trigger(),
				"null contextInfo 应得到可诊断的安全占位值");
	}

	/**
	 * contextInfo 是调用方代码，其异常必须与任务异常隔离；记录日志时不得二次抛出并覆盖原异常。
	 */
	@Test
	public void testThrowingContextInfoDoesNotMaskTerminalFailure() {

		AtomicInteger mainActionInvocations = new AtomicInteger(0);
		AtomicReference<Exception> captured = new AtomicReference<>();
		AtomicReference<IOHelper.IOHelperContext<Void>> capturedContext = new AtomicReference<>();
		IOExceptionOnRuntime original = IOExceptionOnRuntime.encapsulation(new IOException("failure with broken context"));

		ioHelper.tryAsyncAction2(
				"ThrowingContextInfo",
				() -> {
					guardReplay(mainActionInvocations, "异常 contextInfo 终态");
					throw original;
				},
				() -> { },
				() -> { throw new IllegalStateException("context unavailable"); },
				(c, e, msg) -> {
					captured.set(e);
					capturedContext.set(c);
				});

		assertEquals(1, mainActionInvocations.get(), "contextInfo 抛异常不得导致任务被重放");
		assertSame(original, captured.get(), "contextInfo 异常不得掩盖原始任务异常");
		assertEquals("[EmptyContextInfo:error] context unavailable", capturedContext.get().contextInfo.trigger(),
				"异常 contextInfo 应得到包含诊断信息的安全占位值");
	}

	/** 空白 contextInfo 同样应被归一化，避免日志出现无上下文信息的空串。 */
	@Test
	public void testBlankContextInfoUsesSafeFallback() {

		IOHelper.IOHelperContext<Void> context = new IOHelper.IOHelperContext<>(
				"BlankContextInfo",
				successfulAction(new AtomicInteger(0)),
				c -> { },
				r -> { },
				() -> " \t ",
				null,
				false,
				RetryPolicy.none());

		assertEquals("[EmptyContextInfo:blank]", context.contextInfo.trigger());
	}
}
