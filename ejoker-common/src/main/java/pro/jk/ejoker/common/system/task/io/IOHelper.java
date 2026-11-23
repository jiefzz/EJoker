package pro.jk.ejoker.common.system.task.io;

import static pro.jk.ejoker.common.system.extension.LangUtil.await;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pro.jk.ejoker.common.context.annotation.context.EService;
import pro.jk.ejoker.common.system.enhance.StringUtilx;
import pro.jk.ejoker.common.system.exceptions.InterruptedOnRuntime;
import pro.jk.ejoker.common.system.extension.AsyncWrapperException;
import pro.jk.ejoker.common.system.functional.IFunction;
import pro.jk.ejoker.common.system.functional.IVoidFunction;
import pro.jk.ejoker.common.system.functional.IVoidFunction1;
import pro.jk.ejoker.common.system.functional.IVoidFunction2;
import pro.jk.ejoker.common.system.functional.IVoidFunction3;
import pro.jk.ejoker.common.system.helper.Ensure;

/**
 * 模拟IOHelper的实现
 * <br /> <a href="https://github.com/tangxuehua/ecommon/blob/master/src/ECommon/IO/IOHelper.cs">參考</a>
 * 
 * @author JiefzzLon
 *
 */
@EService
public class IOHelper {

	private final static Logger logger = LoggerFactory.getLogger(IOHelper.class);

	private final RetryPolicy retryPolicy;

	public IOHelper() {
		this(RetryPolicy.fromControlTable());
	}

	IOHelper(RetryPolicy retryPolicy) {
		this.retryPolicy = retryPolicy;
	}

	/**
	 * 返回一个绑定了指定重试策略的 IOHelper 视图，用于【按调用点覆盖重试策略】。
	 * <p>
	 * 背景：IOHelper 是被 IoC 容器装配的<b>单例</b>，其重试策略由 ControlTable 全局配置决定。
	 * 但不同调用点的失败语义并不相同 —— 命令提交要重试 ES 乐观并发冲突，
	 * 快照读取失败即失败不该重试，MQ 发送则该重试一切瞬时故障。
	 * 若没有覆盖手段，这些差异就只能写死在全局配置里，无法分场景表达。
	 * <p>
	 * 用法：
	 * <pre>
	 * ioHelper.withRetryPolicy(RetryPolicy.none())
	 *        .tryAsyncAction2("Snapshot", mainAction, completeAction, contextInfo, faildAction, true);
	 * </pre>
	 * <p>
	 * 该方法返回<b>新实例</b>（IOHelper 本身无状态，仅持有策略），
	 * 不影响容器装配的单例，也不影响其它调用点。
	 * <p>
	 * 注意：策略只决定「重试多少次、退避多久、哪些异常可重试」；
	 * 是否启用重试仍由 {@code retryWhenFailed} 参数控制。
	 * 传入 maxAttempts=1 的策略（{@link RetryPolicy#none()}）即等价于「不重试」。
	 *
	 * @param retryPolicy 本次调用链使用的重试策略；为 null 时视为使用全局策略
	 * @return 绑定了该策略的 IOHelper 实例
	 */
	public IOHelper withRetryPolicy(RetryPolicy retryPolicy) {
		if (null == retryPolicy) {
			return this;
		}
		return new IOHelper(retryPolicy);
	}

	/**
	 * 获取当前实例使用的重试策略。
	 *
	 * @return 重试策略，永不为 null
	 */
	public RetryPolicy getRetryPolicy() {
		return retryPolicy;
	}

	private <T> IOHelperContext<T> createContext(
			String actionName,
			IFunction<Future<T>> mainAction,
			IVoidFunction1<IOHelperContext<T>> loopAction,
			IVoidFunction1<T> completeAction,
			IFunction<String> contextInfo,
			IVoidFunction3<IOHelperContext<T>, Exception, String> faildAction,
			boolean retryWhenFailed) {
		return createContext(
				actionName,
				mainAction,
				loopAction,
				completeAction,
				contextInfo,
				faildAction,
				retryWhenFailed,
				this.retryPolicy);
	}

	/**
	 * 创建执行上下文，并允许调用点【单独指定重试策略】。
	 * <p>
	 * 传入的 {@link RetryPolicy} 只作用于本次调用，不影响 IOHelper 实例上的全局策略。
	 *
	 * @param retryPolicy 本次调用的重试策略；为 null 时回退到 IOHelper 的全局策略
	 */
	private <T> IOHelperContext<T> createContext(
			String actionName,
			IFunction<Future<T>> mainAction,
			IVoidFunction1<IOHelperContext<T>> loopAction,
			IVoidFunction1<T> completeAction,
			IFunction<String> contextInfo,
			IVoidFunction3<IOHelperContext<T>, Exception, String> faildAction,
			boolean retryWhenFailed,
			RetryPolicy retryPolicy) {
		return new IOHelperContext<>(
				actionName,
				mainAction,
				loopAction,
				completeAction,
				contextInfo,
				faildAction,
				retryWhenFailed,
				(null == retryPolicy) ? this.retryPolicy : retryPolicy);
	}

	// 
	// 整数标记的方法为IO任务循环起点，带点数的是采用默认的主循环并依次减少faildAction的入参的重载
	// #1
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void tryAsyncAction2(
			String actionName,
			IFunction<Future<Void>> mainAction,
			IVoidFunction1<IOHelperContext<Void>> loopAction,
			IVoidFunction completeAction,
			IFunction<String> contextInfo,
			IVoidFunction3<IOHelperContext<Void>, Exception, String> faildAction) {
		IOHelperContext ioHelperContext = createContext(
				actionName,
				mainAction,
				loopAction,
				r -> {
					completeAction.trigger();
				},
				contextInfo,
				faildAction,
				false);
		do {
			ioHelperContext.loopAction.trigger(ioHelperContext);
		} while(!ioHelperContext.isFinish());
	}
	
	// #1.1
	public void tryAsyncAction2(
			String actionName,
			IFunction<Future<Void>> mainAction,
			IVoidFunction completeAction,
			IFunction<String> contextInfo,
			IVoidFunction3<IOHelperContext<Void>, Exception, String> faildAction) {
		// jump #1
		tryAsyncAction2(
				actionName,
				mainAction,
				this::taskContinueAction,
				completeAction,
				contextInfo,
				faildAction
				);
	}
	
	// #1.2
	public void tryAsyncAction2(
			String actionName,
			IFunction<Future<Void>> mainAction,
			IVoidFunction completeAction,
			IFunction<String> contextInfo,
			IVoidFunction2<Exception, String> faildAction) {
		// jump #1
		tryAsyncAction2(
				actionName,
				mainAction,
				this::taskContinueAction,
				completeAction,
				contextInfo,
				(c, e, eMsg) -> faildAction.trigger(e, eMsg)
				);
	}
	
	// #1.3
	public void tryAsyncAction2(
			String actionName,
			IFunction<Future<Void>> mainAction,
			IVoidFunction completeAction,
			IFunction<String> contextInfo,
			IVoidFunction1<Exception> faildAction) {
		// jump #1
		tryAsyncAction2(
				actionName,
				mainAction,
				this::taskContinueAction,
				completeAction,
				contextInfo,
				(c, e, eMsg) -> faildAction.trigger(e)
				);
	}
	
	// #1.4
	public void tryAsyncAction2(
			String actionName,
			IFunction<Future<Void>> mainAction,
			IVoidFunction completeAction,
			IFunction<String> contextInfo) {
		// jump #1
		tryAsyncAction2(
				actionName,
				mainAction,
				this::taskContinueAction,
				completeAction,
				contextInfo,
				null
				);
	}
	
	// #2
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void tryAsyncAction2(
			String actionName,
			IFunction<Future<Void>> mainAction,
			IVoidFunction1<IOHelperContext<Void>> loopAction,
			IVoidFunction completeAction,
			IFunction<String> contextInfo,
			IVoidFunction3<IOHelperContext<Void>, Exception, String> faildAction,
			boolean retryWhenFailed) {
		IOHelperContext ioHelperContext = createContext(
				actionName,
				mainAction,
				loopAction,
				r -> {
					completeAction.trigger();
				},
				contextInfo,
				faildAction,
				retryWhenFailed);
		do {
			ioHelperContext.loopAction.trigger(ioHelperContext);
		} while(!ioHelperContext.isFinish());
	}
	
	// #2.1
	public void tryAsyncAction2(
			String actionName,
			IFunction<Future<Void>> mainAction,
			IVoidFunction completeAction,
			IFunction<String> contextInfo,
			IVoidFunction3<IOHelperContext<Void>, Exception, String> faildAction,
			boolean retryWhenFailed) {
		// jump #2
		tryAsyncAction2(
				actionName,
				mainAction,
				this::taskContinueAction,
				completeAction,
				contextInfo,
				faildAction,
				retryWhenFailed
				);
	}
	
	// #2.2
	public void tryAsyncAction2(
			String actionName,
			IFunction<Future<Void>> mainAction,
			IVoidFunction completeAction,
			IFunction<String> contextInfo,
			IVoidFunction2<Exception, String> faildAction,
			boolean retryWhenFailed) {
		// jump #2
		tryAsyncAction2(
				actionName,
				mainAction,
				this::taskContinueAction,
				completeAction,
				contextInfo,
				(c, e, eMsg) -> faildAction.trigger(e, eMsg),
				retryWhenFailed
				);
	}
	
	// #2.3
	public void tryAsyncAction2(
			String actionName,
			IFunction<Future<Void>> mainAction,
			IVoidFunction completeAction,
			IFunction<String> contextInfo,
			IVoidFunction1<Exception> faildAction,
			boolean retryWhenFailed) {
		// jump #2
		tryAsyncAction2(
				actionName,
				mainAction,
				this::taskContinueAction,
				completeAction,
				contextInfo,
				(c, e, eMsg) -> faildAction.trigger(e),
				retryWhenFailed
				);
	}
	
	// #2.4
	public void tryAsyncAction2(
			String actionName,
			IFunction<Future<Void>> mainAction,
			IVoidFunction completeAction,
			IFunction<String> contextInfo,
			boolean retryWhenFailed) {
		// jump #2
		tryAsyncAction2(
				actionName,
				mainAction,
				this::taskContinueAction,
				completeAction,
				contextInfo,
				null,
				retryWhenFailed
				);
	}
	
	// #3
	public <T> void tryAsyncAction2(
			String actionName,
			IFunction<Future<T>> mainAction,
			IVoidFunction1<IOHelperContext<T>> loopAction,
			IVoidFunction1<T> completeAction,
			IFunction<String> contextInfo,
			IVoidFunction3<IOHelperContext<T>, Exception, String> faildAction) {
		IOHelperContext<T> ioHelperContext = createContext(
				actionName,
				mainAction,
				loopAction,
				completeAction,
				contextInfo,
				faildAction,
				false);
		do {
			ioHelperContext.loopAction.trigger(ioHelperContext);
		} while(!ioHelperContext.isFinish());
	}
	
	// #3.1
	public <T> void tryAsyncAction2(
			String actionName,
			IFunction<Future<T>> mainAction,
			IVoidFunction1<T> completeAction,
			IFunction<String> contextInfo,
			IVoidFunction3<IOHelperContext<T>, Exception, String> faildAction) {
		// jump #3
		tryAsyncAction2(
				actionName,
				mainAction,
				this::taskContinueAction,
				completeAction,
				contextInfo,
				faildAction
				);
	}
	
	// #3.2
	public <T> void tryAsyncAction2(
			String actionName,
			IFunction<Future<T>> mainAction,
			IVoidFunction1<T> completeAction,
			IFunction<String> contextInfo,
			IVoidFunction2<Exception, String> faildAction) {
		// jump #3
		tryAsyncAction2(
				actionName,
				mainAction,
				this::taskContinueAction,
				completeAction,
				contextInfo,
				(c, e, eMsg) -> faildAction.trigger(e, eMsg)
				);
	}
	
	// #3.3
	public <T> void tryAsyncAction2(
			String actionName,
			IFunction<Future<T>> mainAction,
			IVoidFunction1<T> completeAction,
			IFunction<String> contextInfo,
			IVoidFunction1<Exception> faildAction) {
		// jump #3
		tryAsyncAction2(
				actionName,
				mainAction,
				this::taskContinueAction,
				completeAction,
				contextInfo,
				(c, e, eMsg) -> faildAction.trigger(e)
				);
	}
	
	// #3.4
	public <T> void tryAsyncAction2(
			String actionName,
			IFunction<Future<T>> mainAction,
			IVoidFunction1<T> completeAction,
			IFunction<String> contextInfo) {
		// jump #3
		tryAsyncAction2(
				actionName,
				mainAction,
				this::taskContinueAction,
				completeAction,
				contextInfo,
				null
				);
	}
	
	// #4
	public <T> void tryAsyncAction2(
			String actionName,
			IFunction<Future<T>> mainAction,
			IVoidFunction1<IOHelperContext<T>> loopAction,
			IVoidFunction1<T> completeAction,
			IFunction<String> contextInfo,
			IVoidFunction3<IOHelperContext<T>, Exception, String> faildAction,
			boolean retryWhenFailed) {
		IOHelperContext<T> ioHelperContext = createContext(
				actionName,
				mainAction,
				loopAction,
				completeAction,
				contextInfo,
				faildAction,
				retryWhenFailed);
		do {
			ioHelperContext.loopAction.trigger(ioHelperContext);
		} while(!ioHelperContext.isFinish());
	}
	
	// #4.1
	public <T> void tryAsyncAction2(
			String actionName,
			IFunction<Future<T>> mainAction,
			IVoidFunction1<T> completeAction,
			IFunction<String> contextInfo,
			IVoidFunction3<IOHelperContext<T>, Exception, String> faildAction,
			boolean retryWhenFailed) {
		// jump #4
		tryAsyncAction2(
				actionName,
				mainAction,
				this::taskContinueAction,
				completeAction,
				contextInfo,
				faildAction,
				retryWhenFailed
				);
	}
	
	// #4.2
	public <T> void tryAsyncAction2(
			String actionName,
			IFunction<Future<T>> mainAction,
			IVoidFunction1<T> completeAction,
			IFunction<String> contextInfo,
			IVoidFunction2<Exception, String> faildAction,
			boolean retryWhenFailed) {
		// jump #4
		tryAsyncAction2(
				actionName,
				mainAction,
				this::taskContinueAction,
				completeAction,
				contextInfo,
				(c, e, eMsg) -> faildAction.trigger(e, eMsg),
				retryWhenFailed
				);
	}
	
	
	/**
	 * Task Executor and Monitor
	 * @param actionName 
	 * @param mainAction - The closure of the task.
	 * @param completeAction - The closure will be executed while main action got success.
	 * @param contextInfo - The closure about get context info.
	 * @param faildAction - The closure will be executed while main action got timeout or exception.
	 * @param retryWhenFailed - The flag about whether do retry while main action got failed.
	 */
	// #4.3
	public <T> void tryAsyncAction2(
			String actionName,
			IFunction<Future<T>> mainAction,
			IVoidFunction1<T> completeAction,
			IFunction<String> contextInfo,
			IVoidFunction1<Exception> faildAction,
			boolean retryWhenFailed) {
		// jump #4
		tryAsyncAction2(
				actionName,
				mainAction,
				this::taskContinueAction,
				completeAction,
				contextInfo,
				(c, e, eMsg) -> faildAction.trigger(e),
				retryWhenFailed
				);
	}
	
	// #4.3
	public <T> void tryAsyncAction2(
			String actionName,
			IFunction<Future<T>> mainAction,
			IVoidFunction1<T> completeAction,
			IFunction<String> contextInfo,
			boolean retryWhenFailed) {
		// jump #4
		tryAsyncAction2(
				actionName,
				mainAction,
				this::taskContinueAction,
				completeAction,
				contextInfo,
				null,
				retryWhenFailed
				);
	}
	
	
	/**
	 * 如果自己实现loopAction的话请务必在自定义的loopAction中执行当前调用
	 * @param externalContext
	 */
	public <T> void taskContinueAction(IOHelperContext<T> externalContext) {
		
		Future<T> task = null;
		T result = null;
		
		try {
			try {
				task = externalContext.mainAction.trigger();
				result = await(task); //task.get();
			} catch (Exception ex) {
				// getActuallyCause 幂等：非包装异常原样返回、Error 根因原样返回、永不返回 null，
				// 故无需 instanceof 预判，统一交给 processTaskException 按 Throwable 处理。
				processTaskException(externalContext, AsyncWrapperException.getActuallyCause(ex));
				return;
			}
			if (task.isCancelled()) {
				logger.warn("Async task was cancelled! [taskName: {}, contextInfo: {}, retryTimes: {}]",
						externalContext.actionName,
						externalContext.contextInfo.trigger(),
						externalContext.currentRetryTimes);
				String cancleInfo = StringUtilx.fmt("Async task was cancelled! [taskName: {}]", externalContext.actionName);
				executeFailedAction(
						externalContext,
						new CancellationException(cancleInfo),
						cancleInfo);
				return;
			}

			// @stage0-fix: 先终结循环，再执行完成回调。
			// 修复前的顺序是 completeAction.trigger() 在前、markFinish() 在后。一旦 completeAction
			// 抛异常，异常会被下方 catch(RuntimeException) 吞掉（仅记日志），finishFlag 仍为 false，
			// 外层 tryAsyncAction2 的 do/while(!isFinish()) 便会重新执行整个 mainAction ——
			// 即把【已经成功落库】的事件/消息再提交一遍。
			// IO 既已成功，回调失败绝不能反过来触发 IO 重放，故 markFinish 必须提前。
			externalContext.markFinish();
			externalContext.completeAction.trigger(result);

//			if (result == null) {
//				logger.error("Async task result is `null`!!! [taskName: {}, contextInfo: {}, retryTimes: {}]",
//						externalContext.actionName,
//						externalContext.contextInfo.trigger(),
//						externalContext.currentRetryTimes);
//				if (externalContext.retryWhenFailed) {
//					executeRetryAction(externalContext);
//				} else {
//					executeFailedAction(externalContext, new RuntimeException("task result is `null`!!!"));
//				}
//				return;
//			}
//			
//			
//			switch (result.getStatus()) {
//			case Success:
//				externalContext.markFinish();
//				if(null != externalContext.completeAction)
//					externalContext.completeAction.trigger(result.getData());
//				break;
//			case IOException:
//				logger.error(
//						"Async task result status is io exception, try to run the async task again. [taskName: {}, contextInfo: {}, retryTimes: {}, errorMsg:{}]",
//						externalContext.actionName,
//						externalContext.contextInfo.trigger(),
//						externalContext.currentRetryTimes,
//						result.getErrorMessage());
//				executeRetryAction(externalContext);
//				break;
//			case Failed:
//				logger.error("Async task failed!!! [taskName: {}, contextInfo: {}, retryTimes: {}, errorMsg: {}]",
//						externalContext.actionName,
//						externalContext.contextInfo.trigger(),
//						externalContext.currentRetryTimes,
//						result.getErrorMessage());
//				if (externalContext.retryWhenFailed) {
//					executeRetryAction(externalContext);
//				} else {
//					executeFailedAction(externalContext, new RuntimeException(result.getErrorMessage()));
//				}
//				break;
//			default:
//				assert false;
//			}
		} catch (RuntimeException ex) {
			logger.error("Failed to execute the taskContinueAction!!! [taskName: {}, contextInfo: {}, isMainActionFinished: {}]",
					externalContext.actionName, externalContext.contextInfo.trigger(), (null == result ? "no" : "yes"), ex);
			// 原注释: 「这里不再做出处理，会有别的问题吗？？」
			// 回答: 在 @stage0-fix 之后，这里只记日志不再处理是【安全】的 ——
			// 能走到本 catch 的运行时异常只有 completeAction 或 contextInfo 抛出这两种
			// （mainAction/await 的异常已被内层 catch 交给 processTaskException 后 return，
			//   cancelled 分支的 executeFailedAction 自带 try-catch）。
			// 而 markFinish() 已先于 completeAction 执行，finishFlag 已置 true，
			// 外层 do/while(!isFinish()) 必然退出，不会重放已经成功的 IO。
			// 遗留风险1: completeAction 失败意味着"IO 成功但后续业务动作没做"，此处仅记日志，
			//   调用方无从感知。属于 Stage 2（终态出口）范围，不在 Stage 0 内。
			// 遗留风险2: 本 catch 的日志语句自身会调用 contextInfo.trigger()，若异常正是它抛出的，
			//   则会二次抛出并逃逸出本方法。属既有问题，未在本次改动范围内。
		}
	}

	/**
	 * 异常终态化处理入口。入参已由 {@link #taskContinueAction} 解过一层包，
	 * 此处防御性再解一次（{@link AsyncWrapperException#getActuallyCause} 幂等），
	 * 保证后续判定（isInterruption / retryOn / faildAction）面对的是最内层 Throwable。
	 * <p>
	 * 入参可能是任意 Throwable，包括 Error 根因（OOM/StackOverflowError 等经
	 * FutureTask 捕获包装后在此解出）——Error 交由 {@link RetryPolicy#retryOn}
	 * 判定为不可重试，一次进入终态，绝无强转 Exception 的环节。
	 */
	private <T> void processTaskException(IOHelperContext<T> externalContext, Throwable exception) {
		
		Throwable ex = AsyncWrapperException.getActuallyCause(exception);

		if (exception instanceof InterruptedOnRuntime)
			ex = exception.getCause();

		// 【中断终态化】中断类异常（InterruptedException / InterruptedIOException / ClosedByInterruptException）
		// 是上层请求停止的协作式取消信号，不是可重试的瞬时 IO 故障，须在重试判定之前拦截：
		// 1. 恢复中断标志 —— LangUtil.await 捕获 InterruptedException 时 JVM 已清除标志，
		//    若不恢复，do/while 之上的调用栈对「线程已被请求停止」失去感知；
		// 2. 走终态失败而非退避重试 —— markFinish 保证外层 do/while(!isFinish()) 退出，
		//    不会在被中断的线程上重放 mainAction，也不会在退避 sleep 上继续空耗重试预算。
		if (isInterruption(ex)) {
			logger.error("Async task was interrupted, treat it as a terminal failure without retry. [taskName: {}, contextInfo: {}, retryTimes: {}, errorMsg:{}]",
					externalContext.actionName, externalContext.contextInfo.trigger(), externalContext.currentRetryTimes, ex.getMessage(), ex);
			String interruptInfo = StringUtilx.fmt("Async task was interrupted! [taskName: {}]", externalContext.actionName);
			// 先终态化（markFinish + faildAction 通知）再恢复中断标志：
			// faildAction 必须在「无标志」的线程上执行 —— 若先置位，回调内的任何阻塞调用
			// （sleep/get/latch.await）会立刻抛 InterruptedException 并被 executeFailedAction
			// 的 catch 吞掉，善后逻辑执行到一半。标志在 do/while 退出前恢复即可，
			// tryAsyncAction2 返回后的上层调用栈仍能感知「线程已被请求停止」。
			// faildAction 侧如需区分中断，凭 exception 类型判断，不依赖线程标志。
			executeFailedAction(externalContext, ex, interruptInfo);
			Thread.currentThread().interrupt();
			return;
		}
		
		// 【取消终态化】future.get() 对已取消任务抛 CancellationException（await 不拦截它，
		// 原样到达此处）。取消是上层放弃本次任务结果的信号，同样不是可重试的 IO 故障。
		// 默认黑名单因 CancellationException 继承 IllegalStateException 而「碰巧」判其
		// 不可重试，但该判定依赖 JDK 继承巧合 —— 调用点自定义 retryOn 谓词完全可能判其
		// 可重试，一旦重试即重放 mainAction，而 cancel(true) 的任务副作用可能已部分发生
		// （双发风险）。故在此显式终态化，并与 taskContinueAction 的 isCancelled 防御分支
		// 保持一致的失败呈现形态（CancellationException 本尊 + 描述性 errorMsg）。
		if (ex instanceof CancellationException) {
			logger.warn("Async task was cancelled, treat it as a terminal failure without retry. [taskName: {}, contextInfo: {}, retryTimes: {}, errorMsg:{}]",
					externalContext.actionName, externalContext.contextInfo.trigger(), externalContext.currentRetryTimes, ex.getMessage(), ex);
			String cancelInfo = StringUtilx.fmt("Async task was cancelled! [taskName: {}]", externalContext.actionName);
			executeFailedAction(externalContext, ex, cancelInfo);
			return;
		}
		
		if (externalContext.retryWhenFailed) {
			logger.error("Async task failed, will consult retry policy. [taskName: {}, contextInfo: {}, retryTimes: {}, errorMsg:{}]",
					externalContext.actionName, externalContext.contextInfo.trigger(), externalContext.currentRetryTimes, ex.getMessage(), ex);
			executeRetryAction(externalContext, ex);
		} else {
			logger.error("Async task failed and retryWhenFailed=false, treat it as a terminal failure. [taskName: {}, contextInfo: {}, retryTimes: {}, errorMsg:{}]",
					externalContext.actionName, externalContext.contextInfo.trigger(), externalContext.currentRetryTimes, ex.getMessage(), ex);
			executeFailedAction(externalContext, ex);
		}
	}

	private <T> void executeRetryAction(IOHelperContext<T> externalContext, Throwable exception) {
		RetryPolicy policy = externalContext.retryPolicy;

		if (!policy.retryOn(exception)) {
			logger.error("Async task failed with non-retryable exception, treat it as a terminal failure. [taskName: {}, contextInfo: {}, retryTimes: {}, errorMsg:{}]",
					externalContext.actionName, externalContext.contextInfo.trigger(), externalContext.currentRetryTimes, exception.getMessage(), exception);
			executeFailedAction(externalContext, exception);
			return;
		}

		externalContext.currentRetryTimes++;

		if (externalContext.currentRetryTimes >= policy.getMaxAttempts()) {
			logger.error("Async task retry budget exhausted, treat it as a terminal failure. [taskName: {}, contextInfo: {}, retryTimes: {}, maxAttempts: {}, errorMsg:{}]",
					externalContext.actionName, externalContext.contextInfo.trigger(), externalContext.currentRetryTimes, policy.getMaxAttempts(), exception.getMessage(), exception);
			executeFailedAction(externalContext, exception);
			return;
		}

		long backoffMillis = policy.nextBackoffMillis(externalContext.currentRetryTimes);
		try {
			TimeUnit.MILLISECONDS.sleep(backoffMillis);
		} catch (InterruptedException e) {
			// 退避期间被中断：直接终态化，不再向上抛 RuntimeException。
			// 原实现 throw new RuntimeException(e) 会从 processTaskException 逃逸到
			// taskContinueAction 的外层 catch(RuntimeException) 被吞掉，且 finishFlag 未置，
			// 外层 do/while(!isFinish()) 将重放 mainAction —— 相当于在线程刚被请求停止的
			// 背景下把 IO 主动作再执行一遍（progress.md 遗留项 M-5 的另一半）。
			logger.error("Backoff sleep was interrupted, abort retrying and treat it as a terminal failure. [taskName: {}, contextInfo: {}, retryTimes: {}, errorMsg:{}]",
					externalContext.actionName, externalContext.contextInfo.trigger(), externalContext.currentRetryTimes, e.getMessage(), e);
			String interruptInfo = StringUtilx.fmt("Backoff was interrupted! [taskName: {}]", externalContext.actionName);
			// 先终态化（markFinish + faildAction）再恢复标志，避免回调在带标志的线程上
			// 执行时阻塞调用被立刻打断、善后做一半（同 processTaskException 中断分支）。
			executeFailedAction(externalContext, e, interruptInfo);
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * 判断异常链中是否携带「线程被请求中断」的语义。
	 * <p>
	 * 覆盖三种形态：
	 * <ul>
	 * <li>{@link InterruptedException} —— await/Future.get 等阻塞协调点被中断；</li>
	 * <li>{@link InterruptedIOException} —— java.io 阻塞 IO 被中断；</li>
	 * <li>{@link ClosedByInterruptException} —— NIO 通道因中断被强制关闭（此时重试必然继续失败）。</li>
	 * </ul>
	 * 沿 cause 链遍历（mainAction 可能把 IO 异常包装在 RuntimeException 中抛出），
	 * 深度上限复用 {@link RetryPolicy} 的 MAX_CAUSE_DEPTH，防异常链成环导致死循环。
	 */
	private static boolean isInterruption(Throwable t) {
		Throwable current = t;
		for (int depth = 0; null != current && depth < RetryPolicy.MAX_CAUSE_DEPTH; depth++) {
			if (current instanceof InterruptedException
					|| current instanceof InterruptedIOException
					|| current instanceof ClosedByInterruptException) {
				return true;
			}
			Throwable cause = current.getCause();
			// 防御异常链成环
			if (cause == current) {
				break;
			}
			current = cause;
		}
		return false;
	}
	
	private <T> void executeFailedAction(IOHelperContext<T> externalContext, Throwable exception) {
		executeFailedAction(externalContext, exception, exception.getMessage());
	}

	/**
	 * 终态化并触发 faildAction 回调。入参为任意 Throwable（含 Error 根因）；
	 * 回调签名是 Exception，非 Exception 的 Throwable 包一层 RuntimeException 传递，
	 * 调用方可经 getCause() 还原原始对象，保持「回调必收到非 null 的 Exception」的契约。
	 */
	private <T> void executeFailedAction(IOHelperContext<T> externalContext, Throwable exception, String errorMsg) {
		externalContext.markFinish();
		try {
			if(null != externalContext.faildAction) {
				Exception forCallback = (exception instanceof Exception)
						? (Exception ) exception
						: new RuntimeException(StringUtilx.fmt(
								"Non-Exception throwable escaped from async task: {}", exception), exception);
				externalContext.faildAction.trigger(externalContext, forCallback, errorMsg);
			}
	    } catch (RuntimeException ex) {
	        logger.error("Failed to execute the failedAction!!! [taskName: {}, contextInfo: {}]",
	        				externalContext.actionName, externalContext.contextInfo.trigger(), ex);
	    }
	}
	
	public static class IOHelperContext<T> {

		/**
		 * 当前重试次数
		 */
		private int currentRetryTimes = 0;

		/**
		 * 标识-当失败时是否重试
		 */
		public final boolean retryWhenFailed;

		/**
		 * 重试策略<br />
		 * 当 retryWhenFailed 为 true 时生效
		 */
		protected final RetryPolicy retryPolicy;

		protected final String actionName;
		
		protected final IFunction<Future<T>> mainAction;
		
		protected final IVoidFunction1<IOHelperContext<T>> loopAction;
		
		protected final IVoidFunction1<T> completeAction;
		
		protected final IFunction<String> contextInfo;
		
		protected final IVoidFunction3<IOHelperContext<T>, Exception, String> faildAction;
		
		private final AtomicBoolean finishFlag = new AtomicBoolean(false);
		
		/**
		 * ioHelper执行上下文，此上下文是为了减少栈深度设计的<br /><br />
		 * 执行上下文不是线程安全的，<br />
		 * 也就是我们不应该在ioHelper类之外多次执行taskContinueAction方法<br /><br />
		 *
		 * @param actionName io任务的名字
		 * @param mainAction io任务的入口
		 * @param loopAction io任务失败后的循环过程(mainAction失败时的，会根据retry相关参数执行)
		 * @param completeAction io任务完成后触发的方法
		 * @param contextInfo 用户自定义的上下文参数信息，供输出到日志详细或调试使用
		 * @param faildAction io任务失败后触发的方法
		 * @param retryWhenFailed 标记：失败后是否重试
		 * @param retryPolicy 重试策略
		 */
		public IOHelperContext(
				String actionName,
				IFunction<Future<T>> mainAction,
				IVoidFunction1<IOHelperContext<T>> loopAction,
				IVoidFunction1<T> completeAction,
				IFunction<String> contextInfo,
				IVoidFunction3<IOHelperContext<T>, Exception, String> faildAction,
				boolean retryWhenFailed,
				RetryPolicy retryPolicy) {
			
			Ensure.notNullOrEmpty(actionName, "actionName");
			Ensure.notNull(mainAction, "mainAction");
			Ensure.notNull(loopAction, "loopAction");
			Ensure.notNull(retryPolicy, "retryPolicy");
			
			this.actionName = actionName;
			this.mainAction = mainAction;
			this.loopAction = loopAction;
			this.completeAction = completeAction;
			this.contextInfo = contextInfo;
			this.faildAction = faildAction;
			
			this.retryWhenFailed = retryWhenFailed;
			this.retryPolicy = retryPolicy;
		}

		@Deprecated
		public IOHelperContext(
				String actionName,
				IFunction<Future<T>> mainAction,
				IVoidFunction1<IOHelperContext<T>> loopAction,
				IVoidFunction1<T> completeAction,
				IFunction<String> contextInfo,
				IVoidFunction3<IOHelperContext<T>, Exception, String> faildAction,
				boolean retryWhenFailed,
				int maxRetryTimes,
				long retryInterval) {
			this(
					actionName,
					mainAction,
					loopAction,
					completeAction,
					contextInfo,
					faildAction,
					retryWhenFailed,
					// 【语义变更】maxRetryTimes 现被解释为「最大尝试次数」上限。
					// 历史语义是「达到该次数后才开始延时，且重试无上限」—— 即它本是延时阈值而非上限
					// （原作者参数注释里的「?? 语义不符命名啊!!!」正是在说这件事）。
					// 现在重试一律有界，故取其上限含义；详见 RetryPolicy#legacyOf。
					RetryPolicy.legacyOf(maxRetryTimes, retryInterval));
		}

		@Deprecated
		public IOHelperContext(
				String actionName,
				IFunction<Future<T>> mainAction,
				IVoidFunction1<IOHelperContext<T>> loopAction,
				IVoidFunction1<T> completeAction,
				IFunction<String> contextInfo,
				IVoidFunction3<IOHelperContext<T>, Exception, String> faildAction,
				boolean retryWhenFailed,
				int maxRetryTimes) {
			this(actionName, mainAction, loopAction, completeAction, contextInfo, faildAction,
					retryWhenFailed, maxRetryTimes, 1000L);
		}

		public IOHelperContext(
				String actionName,
				IFunction<Future<T>> mainAction,
				IVoidFunction1<IOHelperContext<T>> loopAction,
				IVoidFunction1<T> completeAction,
				IFunction<String> contextInfo,
				IVoidFunction3<IOHelperContext<T>, Exception, String> faildAction,
				boolean retryWhenFailed) {
			this(actionName, mainAction, loopAction, completeAction, contextInfo, faildAction,
					retryWhenFailed, RetryPolicy.fromControlTable());
		}

		public IOHelperContext(
				String actionName,
				IFunction<Future<T>> mainAction,
				IVoidFunction1<IOHelperContext<T>> loopAction,
				IVoidFunction1<T> completeAction,
				IFunction<String> contextInfo,
				IVoidFunction3<IOHelperContext<T>, Exception, String> faildAction) {
			this(actionName, mainAction, loopAction, completeAction, contextInfo, faildAction,
					false);
		}
		
		public boolean isFinish() {
			return this.finishFlag.get();
		}
		
		public int getCurrentRetryTimes() {
			return currentRetryTimes;
		}
		
		public void markFinish() {
			this.finishFlag.set(true);
		}
	}
}
