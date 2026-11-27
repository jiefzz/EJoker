package pro.jk.ejoker.common.system.task.io;

import static pro.jk.ejoker.common.system.extension.LangUtil.await;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.StringUtils;
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
 * <p>
 * <b>中断处理原则（Stage 2 确立）</b>
 * <p>
 * 【职责切割】中断是发给某个线程的信号，只对被中断者生效。主线程（调用
 * tryAsyncAction2 并阻塞等待 IO 结果的线程）在 await / 退避 sleep 上被中断时，
 * 其职责边界是<b>强制退出</b>：异常上抛穿透 tryAsyncAction2，不执行任何 hook
 * （faildAction/completeAction）、不退避不重试、抛出前恢复中断标志。已提交任务的
 * Future 一概不管 —— 不 cancel、不等待、不追踪；任务侧是否还在执行、能否最终完成、
 * 内部是否还嵌套着子任务，均不在主线程的职责之内。副作用结局未知，善后归中断发起方
 * （停机流程/取消发起者），补偿必须幂等（消费方契约详见
 * {@link pro.jk.ejoker.common.system.exceptions.InterruptedOnRuntime} 类 javadoc）。
 * <p>
 * 【归属判据】凭异常到达 catch 现场的<b>包装形态</b>判定，而非沿 cause 链找中断语义
 * —— 后者丢失包装层级信息，无法区分中断发生在哪一侧：
 * <ul>
 * <li>直达的 {@link pro.jk.ejoker.common.system.exceptions.InterruptedOnRuntime} =
 *     主线程自己的阻塞点被中断（全工程构造点受控：LangUtil.await 与本类的退避 sleep，
 *     包装均发生在<b>当前线程栈</b>上）⇒ 强制退出；</li>
 * <li>经 {@code ExecutionException} 信封送达的一切异常（哪怕解出的是 InterruptedException /
 *     InterruptedIOException / ClosedByInterruptException）= 任务侧事实 —— 任务体在
 *     <b>工作线程</b>上被中断，是「那一次执行被打断」，与「主线程被请求停止」无关 ⇒
 *     作为<b>普通任务失败</b>交 {@link RetryPolicy#retryOn} 裁决，绝不给等待线程伪造中断标志。</li>
 * </ul>
 * 判据是<b>类型</b>不是 cause：无 cause 的 InterruptedOnRuntime 同样强制退出。
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
	 *                      【例外】主线程被中断的强制退出场景不触发本回调，而是从
	 *                      tryAsyncAction2 直接抛出 InterruptedOnRuntime（契约详见其类 javadoc）。
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
	
	// #4.4
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
			} catch (InterruptedOnRuntime ior) {
				// 【中断强制退出】主线程自己阻塞点被中断的【归属证明】直达 catch 现场：
				// 本类型全工程构造点唯一（LangUtil.await / executeRetryAction 退避），包装
				// 发生在本线程栈上，与经 ExecutionException 信封送达的任务侧中断（任务侧事实）
				// 泾渭分明。按中断契约：不执行任何 hook（faildAction/completeAction）、
				// 不退避不重试、不 markFinish，异常原样上抛 —— 由外层 catch 的放行通道恢复
				// 标志后穿透 do/while 与 tryAsyncAction2，强制终止本调用栈上的一切后续工作。
				// 已提交任务的 Future 按既定原则不管、副作用结局未知（消费方语义见
				// InterruptedOnRuntime 类 javadoc），善后归中断发起方。
				throw ior;
			} catch (Exception ex) {
				// getActuallyCause 幂等：非包装异常原样返回、Error 根因原样返回、永不返回 null，
				// 故无需 instanceof 预判，统一交给 processTaskException 按 Throwable 处理。
				// 任务侧中断类异常亦经信封到达此处 —— 作为普通任务失败交 retryOn 裁决。
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
			if(null != externalContext.completeAction)
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
			// 【中断强制退出放行通道】内层分流 throw 的 InterruptedOnRuntime、以及
			// executeRetryAction 退避包装上抛的 InterruptedOnRuntime 都会直达此处
			// （它本身是 RuntimeException）。若不显式放行，将被本兜底吞掉：
			// finishFlag 未置、do/while 继续 —— @stage0-fix 防御的「中断后重放
			// mainAction」事故原样复发。此通道是中断契约的安全带。
			// 放行前统一恢复中断标志：InterruptedException 抛出时 JVM 已清除标志，
			// 包装上抛若不恢复即中断丢失（swallow-interrupt 反模式）；恢复动作幂等。
			if (ex instanceof InterruptedOnRuntime) {
				Thread.currentThread().interrupt();
				throw ex;
			}

			if(externalContext.isFinish()) {
				logger.error("Failed to execute the completeAction!!! [taskName: {}, contextInfo: {}]",
						externalContext.actionName, externalContext.contextInfo.trigger(), ex);
				return;
			}
			logger.error("Failed to execute the taskContinueAction!!! [taskName: {}, contextInfo: {}, isMainActionFinished: {}]",
					externalContext.actionName, externalContext.contextInfo.trigger(), (null == result ? "no" : "yes"), ex);
			// 原注释: 「这里不再做出处理，会有别的问题吗？？」
			// 回答: 在 @stage0-fix 之后，这里只记日志不再处理是【安全】的 ——
			// 能走到本兜底的运行时异常只有 completeAction / contextInfo /
			// processTaskException 内 logger 语句这三种（mainAction/await 的异常已被
			// 内层 catch 分流：中断形态经放行通道上抛、其余交给 processTaskException
			// 后 return；cancelled 分支的 executeFailedAction 自带 try-catch）。
			// 而 markFinish() 已先于 completeAction 执行，finishFlag 已置 true，
			// 外层 do/while(!isFinish()) 必然退出，不会重放已经成功的 IO。
			// 遗留风险1: completeAction 失败意味着"IO 成功但后续业务动作没做"，此处仅记日志，
			//   调用方无从感知。属于 Stage 2（终态出口）范围，不在 Stage 0 内。
		}
	}

	/**
	 * 任务侧失败的统一处理入口（不含等待侧中断 —— 那由 taskContinueAction 的前置
	 * catch 分流并强制退出，到不了此处）。入参已由 {@link #taskContinueAction} 解过
	 * 一层包，此处防御性再解一次（{@link AsyncWrapperException#getActuallyCause} 幂等），
	 * 保证后续判定（取消 / retryOn / faildAction）面对的是最内层 Throwable。
	 * <p>
	 * 任务侧中断类异常（任务体在【工作线程】上被中断、经 ExecutionException 信封
	 * 送达的 InterruptedException / InterruptedIOException / ClosedByInterruptException 等）
	 * 在此作为【普通任务失败】交 {@link RetryPolicy#retryOn} 裁决（默认黑名单不含
	 * 中断类，判为可重试）—— 任务侧中断是「那一次执行被打断」，与「主线程被请求
	 * 停止」无关，重试语义与其它瞬时故障一致，且绝不给本线程伪造中断标志。
	 * <p>
	 * 入参可能是任意 Throwable，包括 Error 根因（OOM/StackOverflowError 等经
	 * FutureTask 捕获包装后在此解出）——Error 交由 {@link RetryPolicy#retryOn}
	 * 判定为不可重试，一次进入终态，绝无强转 Exception 的环节。
	 */
	private <T> void processTaskException(IOHelperContext<T> externalContext, Throwable exception) {

		Throwable ex = AsyncWrapperException.getActuallyCause(exception);

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
			// 退避 sleep 同为主线程自己的阻塞点，被中断即【中断强制退出】场景：
			// 包装为 InterruptedOnRuntime 上抛（标志恢复由 taskContinueAction 外层
			// catch 的放行通道统一执行），不执行任何 hook、不退避不重试。
			// 历史演进：stage0 时代此处走「终态化 + 恢复标志」收尾（progress.md M-5）；
			// 中断契约改为强制退出后，终态化路径整体退役 —— 异常经放行通道穿透
			// do/while 即完成退出，不存在「吞掉后 finishFlag 未置而重放」的路径。
			throw new InterruptedOnRuntime(e);
		}
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
			if (ex instanceof InterruptedOnRuntime) {
				throw ex;   // 中断必须放行(契约要求),其余异常不掩盖原始根因
			}
		}
	}

	/**
	 * 对这两个 hook ( completeAction / failAction ) 的推荐契约
	 * <br />
	 * <pre>
	 * IOHelper 是底层通用组件，明确：
	 *
	 * completeAction 只在 I/O 成功后调用一次。
	 * failAction 只在 I/O 失败后调用一次。
	 * completeAction 抛异常：
	 *   不调用 failAction；
	 *   不改变已经成功的事实；
	 *   记录或按 API 契约传播。
	 * failAction 抛异常：
	 *   不再次调用 failAction；
	 *   不覆盖原始异常；
	 *   记录日志，或作为原始异常的 suppressed。
	 * hook 不负责框架必须保证的资源清理。
	 * </pre>
	 * 文档明确 hook 是否允许抛异常，以及异常在哪个线程、由谁处理。
	 * @param <T>
	 */
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

			// 保证不受用户传入不安全闭包的影响
			IFunction<String> contextInfoSafeFunc = () -> {
				try {
					String realInfo;
					if (null == contextInfo) {
						return "[EmptyContextInfo:null]";
					} else if (StringUtils.isBlank(realInfo = contextInfo.trigger())) {
						return "[EmptyContextInfo:blank]";
					} else {
						return realInfo;
					}
				} catch (Exception e) {
					logger.error("Failed to get contextInfo!!! [taskName: {}]", actionName, e);
					return "[EmptyContextInfo:error] " + e.getMessage();
				}
			};
			
			this.actionName = actionName;
			this.mainAction = mainAction;
			this.loopAction = loopAction;
			this.completeAction = completeAction;
			this.contextInfo = contextInfoSafeFunc;
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
