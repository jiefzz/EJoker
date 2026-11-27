package pro.jk.ejoker.common.system.exceptions;

/**
 * 主线程（等待侧）自身阻塞点被中断的专用载体类型。全工程构造点受控共两处：
 * {@code LangUtil.await}（{@code Future.get()} 抛 InterruptedException 时包装）与
 * {@code IOHelper.executeRetryAction}（退避 sleep 被中断时包装），包装均发生在<b>当前线程栈</b>上 ——
 * 因此 catch 现场直达本类型即是「主线程自己被中断」的归属证明，与经
 * {@code ExecutionException} 信封送达的任务侧中断（任务侧事实，由 RetryPolicy.retryOn
 * 按普通失败裁决）泾渭分明。
 * <p>
 * IOHelper 中断契约（Stage 2 强制退出语义）：
 * <ul>
 * <li>IOHelper 的 IO 循环收到本类型即<b>强制退出</b>：异常上抛穿透 {@code tryAsyncAction2}，
 *     不执行任何 hook（faildAction / completeAction）、不退避不重试，抛出前恢复中断标志；</li>
 * <li>消费方语义：捕获本类型 = 本次 IO 循环被强制终止，已提交任务的副作用<b>结局未知</b>
 *     （可能已部分/全部发生），善后归中断发起方（停机流程 / 取消发起者）——
 *     补偿动作必须幂等（消息唯一 ID / 唯一键 upsert / 版本号）；</li>
 * <li>包装时 cause 必须携带原始中断异常（归属证据 + 栈保留），请勿使用无 cause 构造器。</li>
 * </ul>
 *
 * @author JiefzzLon
 */
public class InterruptedOnRuntime extends RuntimeException {

	private static final long serialVersionUID = -4986838330940904766L;

	public InterruptedOnRuntime() {
	}

	public InterruptedOnRuntime(String message) {
		super(message);
	}

	public InterruptedOnRuntime(Throwable cause) {
		super(cause);
	}

	public InterruptedOnRuntime(String message, Throwable cause) {
		super(message, cause);
	}

	public InterruptedOnRuntime(String message, Throwable cause, boolean enableSuppression,
			boolean writableStackTrace) {
		super(message, cause, enableSuppression, writableStackTrace);
	}

}
