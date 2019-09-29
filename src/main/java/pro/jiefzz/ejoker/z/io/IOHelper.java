package pro.jiefzz.ejoker.z.io;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pro.jiefzz.ejoker.z.context.annotation.context.EService;
import pro.jiefzz.ejoker.z.system.extension.AsyncWrapperException;
import pro.jiefzz.ejoker.z.system.extension.acrossSupport.SystemFutureWrapper;
import pro.jiefzz.ejoker.z.system.functional.IFunction;
import pro.jiefzz.ejoker.z.system.functional.IVoidFunction;
import pro.jiefzz.ejoker.z.system.functional.IVoidFunction1;
import pro.jiefzz.ejoker.z.system.wrapper.DiscardWrapper;
import pro.jiefzz.ejoker.z.task.AsyncTaskResult;

/**
 * 模拟IOHelper的实现
 * {@link https://github.com/tangxuehua/ecommon/blob/master/src/ECommon/IO/IOHelper.cs}
 * 
 * @author JiefzzLon
 *
 */
@EService
public class IOHelper {

	private final static Logger logger = LoggerFactory.getLogger(IOHelper.class);
	
//	private final static AtomicInteger poolIndex = new AtomicInteger(0);
	
//	private final ThreadPoolExecutor retryExecutorService = new MixedThreadPoolExecutor(
//			EJokerEnvironment.ASYNC_IO_RETRY_THREADPOLL_SIZE,
//			EJokerEnvironment.ASYNC_IO_RETRY_THREADPOLL_SIZE,
//			0l, TimeUnit.MICROSECONDS,
//			new LinkedBlockingQueue<Runnable>(),
//			new ThreadFactory() {
//
//				private final AtomicInteger threadIndex = new AtomicInteger(0);
//
//				private final ThreadGroup group;
//
//				private final String namePrefix;
//
//				{
//
//					SecurityManager s = System.getSecurityManager();
//					group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
//					namePrefix = "EJokerIORetry-" + poolIndex.incrementAndGet() + "-thread-";
//				}
//
//				@Override
//				public Thread newThread(Runnable r) {
//					Thread t = new Thread(null, r, namePrefix + threadIndex.getAndIncrement(), 0);
//					if (t.isDaemon())
//						t.setDaemon(false);
//					if (t.getPriority() != Thread.NORM_PRIORITY)
//						t.setPriority(Thread.NORM_PRIORITY);
//
//					return t;
//				}
//
//			},
//			new ThreadPoolExecutor.CallerRunsPolicy());
//	
//
//	/**
//	 * ioHelper自身线程池服務主要目的还是为了IO重试<br>
//	 * 其余异步委托还是使用系统异步助手
//	 */
//	@Dependence
//	protected SystemAsyncHelper systemAsyncHelper;
//
//	@Dependence
//	private Scavenger scavenger;
//	
//	/**
//	 * 要不要考慮下，如果有重試任務正在進行的話，保存關鍵信息？還是給出異常日誌？還是阻止關閉？
//	 */
//	@EInitialize
//	private void init() {
////		scavenger.addFianllyJob(retryExecutorService::shutdown);
//	}
	

	public void tryAsyncAction2(
			String actionName,
			IFunction<SystemFutureWrapper<AsyncTaskResult<Void>>> mainAction,
			IVoidFunction completeAction,
			IFunction<String> contextInfo,
			IVoidFunction1<Exception> faildAction) {
		tryAsyncAction2(
				actionName,
				mainAction,
				null,
				completeAction,
				contextInfo,
				faildAction);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void tryAsyncAction2(
			String actionName,
			IFunction<SystemFutureWrapper<AsyncTaskResult<Void>>> mainAction,
			IVoidFunction1<IOHelperContext<Void>> loopAction,
			IVoidFunction completeAction,
			IFunction<String> contextInfo,
			IVoidFunction1<Exception> faildAction) {
		IOHelperContext ioHelperContext = new IOHelperContext(
				this,
				actionName,
				mainAction,
				loopAction,
				r -> {
					completeAction.trigger();
				},
				contextInfo,
				faildAction);
		do {
			ioHelperContext.loopAction.trigger(ioHelperContext);
		} while(!ioHelperContext.isFinish());
	}

	public void tryAsyncAction2(
			String actionName,
			IFunction<SystemFutureWrapper<AsyncTaskResult<Void>>> mainAction,
			IVoidFunction completeAction,
			IFunction<String> contextInfo,
			IVoidFunction1<Exception> faildAction,
			boolean retryWhenFailed) {
		tryAsyncAction2(
				actionName,
				mainAction,
				null,
				completeAction,
				contextInfo,
				faildAction,
				retryWhenFailed);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void tryAsyncAction2(
			String actionName,
			IFunction<SystemFutureWrapper<AsyncTaskResult<Void>>> mainAction,
			IVoidFunction1<IOHelperContext<Void>> loopAction,
			IVoidFunction completeAction,
			IFunction<String> contextInfo,
			IVoidFunction1<Exception> faildAction,
			boolean retryWhenFailed) {
		IOHelperContext ioHelperContext = new IOHelperContext(
				this,
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

	public <T> void tryAsyncAction2(
			String actionName,
			IFunction<SystemFutureWrapper<AsyncTaskResult<T>>> mainAction,
			IVoidFunction1<T> completeAction,
			IFunction<String> contextInfo,
			IVoidFunction1<Exception> faildAction) {
		tryAsyncAction2(
				actionName,
				mainAction,
				null,
				completeAction,
				contextInfo,
				faildAction);
	}

	public <T> void tryAsyncAction2(
			String actionName,
			IFunction<SystemFutureWrapper<AsyncTaskResult<T>>> mainAction,
			IVoidFunction1<IOHelperContext<T>> loopAction,
			IVoidFunction1<T> completeAction,
			IFunction<String> contextInfo,
			IVoidFunction1<Exception> faildAction) {
		IOHelperContext<T> ioHelperContext = new IOHelperContext<>(
				this,
				actionName,
				mainAction,
				loopAction,
				completeAction,
				contextInfo,
				faildAction);
		do {
			ioHelperContext.loopAction.trigger(ioHelperContext);
		} while(!ioHelperContext.isFinish());
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
	public <T> void tryAsyncAction2(
			String actionName,
			IFunction<SystemFutureWrapper<AsyncTaskResult<T>>> mainAction,
			IVoidFunction1<T> completeAction,
			IFunction<String> contextInfo,
			IVoidFunction1<Exception> faildAction,
			boolean retryWhenFailed) {
		tryAsyncAction2(
				actionName,
				mainAction,
				null,
				completeAction,
				contextInfo,
				faildAction,
				retryWhenFailed);
	}

	public <T> void tryAsyncAction2(
			String actionName,
			IFunction<SystemFutureWrapper<AsyncTaskResult<T>>> mainAction,
			IVoidFunction1<IOHelperContext<T>> loopAction,
			IVoidFunction1<T> completeAction,
			IFunction<String> contextInfo,
			IVoidFunction1<Exception> faildAction,
			boolean retryWhenFailed) {
		IOHelperContext<T> ioHelperContext = new IOHelperContext<>(
				this,
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

	public <T> void taskContinueAction(IOHelperContext<T> externalContext) {
		
		SystemFutureWrapper<AsyncTaskResult<T>> task;
		
		try {
			task = externalContext.mainAction.trigger();
			AsyncTaskResult<T> result = null;
			try {
				result = task.get();
			} catch (Exception ex) {
				Exception cause;
				if(ex instanceof AsyncWrapperException) {
					cause = AsyncWrapperException.getActuallyCause(ex);
				} else {
					cause = (Exception )ex.getCause();
				}
				processTaskException(externalContext, null == cause ? ex : cause);
				return;
			}
			if (task.isCancelled()) {
				logger.error("Async task '{}' was cancelled, context info: {}, current retryTimes: {}.",
						externalContext.actionName,
						externalContext.contextInfo.trigger(),
						externalContext.currentRetryTimes);
				executeFailedAction(
						externalContext,
						new Exception(
								String.format(
										"Async task '%s' was cancelled.",
										externalContext.actionName)
								)
						);
				return;
			}
			if (result == null) {
				logger.error("Async task '{}' result is null, context info: {}, current retryTimes: {}",
						externalContext.actionName,
						externalContext.contextInfo.trigger(),
						externalContext.currentRetryTimes);
				if (externalContext.retryWhenFailed) {
					executeRetryAction(externalContext);
				} else {
					executeFailedAction(externalContext, new RuntimeException("task result is null!!!"));
				}
				return;
			}
			switch (result.getStatus()) {
			case Success:
				externalContext.completeAction.trigger(result.getData());
				externalContext.markFinish();
				break;
			case IOException:
				logger.error(
						"Async task '{}' result status is io exception, context info: {}, current retryTimes:{}, errorMsg:{}, try to run the async task again.",
						externalContext.actionName,
						externalContext.contextInfo.trigger(),
						externalContext.currentRetryTimes,
						result.getErrorMessage());
				executeRetryAction(externalContext);
				break;
			case Failed:
				logger.error("Async task '{}' failed, context info: {}, current retryTimes:{}, errorMsg:{}",
						externalContext.actionName,
						externalContext.contextInfo.trigger(),
						externalContext.currentRetryTimes,
						result.getErrorMessage());
				if (externalContext.retryWhenFailed) {
					executeRetryAction(externalContext);
				} else {
					executeFailedAction(externalContext, new RuntimeException(result.getErrorMessage()));
				}
				break;
			default:
				assert false;
			}
		} catch (Exception ex) {
			logger.error(
					String.format(
							"Failed to execute the taskContinueAction, asyncActionName: %s, contextInfo: %s",
							externalContext.actionName,
							externalContext.contextInfo.trigger()),
					ex);
			// 这里不再做出处理，会有别的问题吗？？
		}
	}

	private void processTaskException(IOHelperContext<?> externalContext, Exception exception) {
		if (exception instanceof IOException || exception instanceof IOExceptionOnRuntime) {
			logger.error(
					String.format(
							"Async task '%s' has io exception, context info: %s, current retryTimes: %d, try to run the async task again.",
							externalContext.actionName,
							externalContext.contextInfo.trigger(),
							externalContext.currentRetryTimes),
					exception);
			executeRetryAction(externalContext);
		} else {
			logger.error(
					String.format(
							"Async task '%s' has unknown exception, context info: %s, current retryTimes: %d",
							externalContext.actionName,
							externalContext.contextInfo.trigger(),
							externalContext.currentRetryTimes),
					exception);
			if (externalContext.retryWhenFailed) {
				executeRetryAction(externalContext);
			} else {
				executeFailedAction(externalContext, exception);
			}
		}
	}

	private <T> void executeRetryAction(IOHelperContext<T> externalContext) {
		externalContext.currentRetryTimes++;
		try {
			if (externalContext.currentRetryTimes >= externalContext.maxRetryTimes) {
				DiscardWrapper.sleepInterruptable(TimeUnit.MILLISECONDS, externalContext.retryInterval);
//				retryExecutorService.submit(() -> {
//					DiscardWrapper.sleep(TimeUnit.MILLISECONDS, externalContext.retryInterval);
//					externalContext.loopAction.trigger(externalContext);
//					});
			} else {
//				externalContext.loopAction.trigger(externalContext);
			}
		} catch (RuntimeException ex) {
			logger.error(
					String.format(
							"Failed to execute the retryAction, asyncActionName: %s, context info: %s",
							externalContext.actionName,
							externalContext.contextInfo.trigger()),
					ex);
		}
	}

	private void executeFailedAction(IOHelperContext<?> externalContext, Exception exception) {
		try {
			externalContext.faildAction.trigger(exception);
	    } catch (RuntimeException ex) {
	        logger.error(
	        		String.format(
	        				"Failed to execute the failedAction of asyncAction: %s, contextInfo: %s",
	        				externalContext.actionName,
	        				externalContext.contextInfo.trigger()),
	        		ex);
	    } finally {
			externalContext.markFinish();
		}
	}
	
	private static class IOHelperContext<T> {

		/**
		 * 当前重试次数
		 */
		private int currentRetryTimes = 0;

		/**
		 * 标识-当失败时是否重试
		 */
		public final boolean retryWhenFailed;

		/**
		 * 最大的即时重试次数<br>
		 * 若失败重试次数超过此数字，则执行重试时会等待 ${重试间隔} ms 的时间再重试
		 */
		protected final int maxRetryTimes;

		/**
		 * 重试间隔
		 */
		protected final long retryInterval;

		protected final String actionName;
		
		protected final IFunction<SystemFutureWrapper<AsyncTaskResult<T>>> mainAction;
		
		protected final IVoidFunction1<IOHelperContext<T>> loopAction;
		
		protected final IVoidFunction1<T> completeAction;
		
		protected final IFunction<String> contextInfo;
		
		protected final IVoidFunction1<Exception> faildAction;
		
		private IOHelper ioHelper = null;
		
		private final AtomicBoolean finishFlag = new AtomicBoolean(false);
		
		public IOHelperContext(
				IOHelper ioHelper,
				String actionName,
				IFunction<SystemFutureWrapper<AsyncTaskResult<T>>> mainAction,
				IVoidFunction1<IOHelperContext<T>> loopAction,
				IVoidFunction1<T> completeAction,
				IFunction<String> contextInfo,
				IVoidFunction1<Exception> faildAction,
				boolean retryWhenFailed, int maxRetryTimes, long retryInterval) {
			this.ioHelper = ioHelper;
			this.actionName = actionName;
			this.mainAction = mainAction;
			this.loopAction = null == loopAction
					? this.ioHelper::taskContinueAction
					: loopAction;
			this.completeAction = completeAction;
			this.contextInfo = contextInfo;
			this.faildAction = faildAction;
			
			this.retryWhenFailed = retryWhenFailed;
			this.maxRetryTimes = maxRetryTimes;
			this.retryInterval = retryInterval;
		}

		public IOHelperContext(
				IOHelper ioHelper,
				String actionName,
				IFunction<SystemFutureWrapper<AsyncTaskResult<T>>> mainAction,
				IVoidFunction1<IOHelperContext<T>> loopAction,
				IVoidFunction1<T> completeAction,
				IFunction<String> contextInfo,
				IVoidFunction1<Exception> faildAction,
				boolean retryWhenFailed, int maxRetryTimes) {
			this(ioHelper, actionName, mainAction, loopAction, completeAction, contextInfo, faildAction,
					retryWhenFailed, maxRetryTimes, 1000l);
		}

		public IOHelperContext(
				IOHelper ioHelper,
				String actionName,
				IFunction<SystemFutureWrapper<AsyncTaskResult<T>>> mainAction,
				IVoidFunction1<IOHelperContext<T>> loopAction,
				IVoidFunction1<T> completeAction,
				IFunction<String> contextInfo,
				IVoidFunction1<Exception> faildAction,
				boolean retryWhenFailed) {
			this(ioHelper, actionName, mainAction, loopAction, completeAction, contextInfo, faildAction,
					retryWhenFailed, 3);
		}

		public IOHelperContext(
				IOHelper ioHelper,
				String actionName,
				IFunction<SystemFutureWrapper<AsyncTaskResult<T>>> mainAction,
				IVoidFunction1<IOHelperContext<T>> loopAction,
				IVoidFunction1<T> completeAction,
				IFunction<String> contextInfo,
				IVoidFunction1<Exception> faildAction) {
			this(ioHelper, actionName, mainAction, loopAction, completeAction, contextInfo, faildAction,
					false);
		}
		
		public boolean isFinish() {
			return this.finishFlag.get();
		}
		
		protected void markFinish() {
			this.finishFlag.set(true);
		}
	}
}
