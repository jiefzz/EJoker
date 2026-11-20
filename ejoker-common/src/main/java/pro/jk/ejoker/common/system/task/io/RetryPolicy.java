package pro.jk.ejoker.common.system.task.io;

import java.io.IOException;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;

import pro.jk.ejoker.common.system.constant.ConfigKeyPrimary;
import pro.jk.ejoker.common.system.component.ControlTable;
import pro.jk.ejoker.common.system.component.ControlTable.RegisterTuple;
import pro.jk.ejoker.common.system.exceptions.ArgumentException;
import pro.jk.ejoker.common.system.exceptions.ArgumentNullException;
import pro.jk.ejoker.common.system.exceptions.ArgumentOutOfRangeException;
import pro.jk.ejoker.common.system.exceptions.NonRetryableException;

/**
 * 有界重试策略。
 * <p>
 * 负责三件事：
 * <ul>
 * <li>判定异常是否可重试（{@link #retryOn(Throwable)}）；</li>
 * <li>计算下一次退避时间（{@link #nextBackoffMillis(int)}，支持 Full Jitter）；</li>
 * <li>提供全局默认值与 ControlTable 覆盖值。</li>
 * </ul>
 *
 * @author JiefzzLon
 */
public final class RetryPolicy {

	public static final int DEFAULT_MAX_ATTEMPTS = 5;

	public static final long DEFAULT_BASE_DELAY_MILLIS = 200L;

	public static final long DEFAULT_MAX_DELAY_MILLIS = 5000L;

	public static final boolean DEFAULT_FULL_JITTER_ENABLED = true;

	private final int maxAttempts;

	private final long baseDelayMillis;

	private final long maxDelayMillis;

	private final boolean fullJitterEnabled;

	private final DoubleSupplier randomSupplier;

	private final Predicate<Throwable> retryOn;

	public RetryPolicy(
			int maxAttempts,
			long baseDelayMillis,
			long maxDelayMillis,
			boolean fullJitterEnabled,
			DoubleSupplier randomSupplier,
			Predicate<Throwable> retryOn) {
		this.maxAttempts = Math.max(1, maxAttempts);
		this.baseDelayMillis = Math.max(0L, baseDelayMillis);
		this.maxDelayMillis = Math.max(this.baseDelayMillis, maxDelayMillis);
		this.fullJitterEnabled = fullJitterEnabled;
		this.randomSupplier = (null == randomSupplier) ? Math::random : randomSupplier;
		this.retryOn = (null == retryOn) ? RetryPolicy::defaultRetryOn : retryOn;
	}

	public static RetryPolicy defaultPolicy() {
		return new RetryPolicy(
				DEFAULT_MAX_ATTEMPTS,
				DEFAULT_BASE_DELAY_MILLIS,
				DEFAULT_MAX_DELAY_MILLIS,
				DEFAULT_FULL_JITTER_ENABLED,
				null,
				null);
	}

	/**
	 * 「不重试」策略：任何失败都直接进入终态。
	 * <p>
	 * 适用于失败即不可恢复的调用点（如快照读取），配合
	 * {@link IOHelper#withRetryPolicy(RetryPolicy)} 使用。
	 */
	public static RetryPolicy none() {
		return new RetryPolicy(1, 0L, 0L, false, null, null);
	}

	/**
	 * 指定最大尝试次数的重试策略，沿用默认的退避参数与黑名单分类。
	 *
	 * @param maxAttempts 最大尝试次数（小于 1 时按 1 处理）
	 */
	public static RetryPolicy withMaxAttempts(int maxAttempts) {
		return new RetryPolicy(
				maxAttempts,
				DEFAULT_BASE_DELAY_MILLIS,
				DEFAULT_MAX_DELAY_MILLIS,
				DEFAULT_FULL_JITTER_ENABLED,
				null,
				null);
	}

	public static RetryPolicy fromControlTable() {
		registerControlTableKeys();

		int maxAttempts = ControlTable.detectAsInteger(ConfigKeyPrimary.EJOKER_IO_RETRY_MAX_ATTEMPTS);
		long baseDelayMillis = Long.parseLong(ControlTable.getConfigValue(ConfigKeyPrimary.EJOKER_IO_RETRY_BASE_DELAY_MILLIS));
		long maxDelayMillis = Long.parseLong(ControlTable.getConfigValue(ConfigKeyPrimary.EJOKER_IO_RETRY_MAX_DELAY_MILLIS));
		boolean fullJitterEnabled = ControlTable.detectAsBoolean(ConfigKeyPrimary.EJOKER_IO_RETRY_FULL_JITTER);

		return new RetryPolicy(
				maxAttempts,
				baseDelayMillis,
				maxDelayMillis,
				fullJitterEnabled,
				null,
				null);
	}

	/**
	 * 由 legacy 的 {@code maxRetryTimes} / {@code retryInterval} 构造策略。
	 * <p>
	 * <b>【语义变更】</b>历史语义是「达到 maxRetryTimes 次<b>之后</b>才开始延时重试，
	 * 且重试<b>无上限</b>」—— 即 maxRetryTimes 是<b>延时阈值</b>，不是<b>尝试上限</b>
	 * （原作者在参数注释里写的「?? 语义不符命名啊!!!」正是在说这件事）。
	 * <p>
	 * 现在的重试一律有界，因此把该数值解释为<b>最大尝试次数</b>上限，
	 * 并以 retryInterval 作为固定退避间隔（关闭 Full Jitter，保持旧版时序确定性）。
	 * <p>
	 * 换言之：legacy 调用方若曾依赖「无限重试」，现在会得到「最多试 maxRetryTimes 次」。
	 * 这正是 Stage 1 要修掉的缺陷（无上限重试会静默卡死调用方），
	 * 但对外部调用方属于行为变更，故在此显式记录。
	 *
	 * @param maxRetryTimes 现被解释为最大尝试次数上限
	 * @param retryInterval 固定退避间隔（毫秒）
	 */
	public static RetryPolicy legacyOf(int maxRetryTimes, long retryInterval) {
		return new RetryPolicy(maxRetryTimes, retryInterval, retryInterval, false, null, null);
	}

	private static void registerControlTableKeys() {
		ControlTable.provideRegisterTuple(
				RegisterTuple.of(
						ConfigKeyPrimary.EJOKER_IO_RETRY_MAX_ATTEMPTS,
						"EJOKER_IO_RETRY_MAX_ATTEMPTS",
						"" + DEFAULT_MAX_ATTEMPTS,
						"IO 重试的最大尝试次数",
						"^[1-9]\\d*$"
				),
				RegisterTuple.of(
						ConfigKeyPrimary.EJOKER_IO_RETRY_BASE_DELAY_MILLIS,
						"EJOKER_IO_RETRY_BASE_DELAY_MILLIS",
						"" + DEFAULT_BASE_DELAY_MILLIS,
						"IO 重试的基础退避时间，单位毫秒",
						"^\\d+$"
				),
				RegisterTuple.of(
						ConfigKeyPrimary.EJOKER_IO_RETRY_MAX_DELAY_MILLIS,
						"EJOKER_IO_RETRY_MAX_DELAY_MILLIS",
						"" + DEFAULT_MAX_DELAY_MILLIS,
						"IO 重试的退避上限，单位毫秒",
						"^\\d+$"
				),
				RegisterTuple.of(
						ConfigKeyPrimary.EJOKER_IO_RETRY_FULL_JITTER,
						"EJOKER_IO_RETRY_FULL_JITTER",
						"" + DEFAULT_FULL_JITTER_ENABLED,
						"IO 重试是否启用 Full Jitter",
						"(?i)^(true|false|1|0|t|f)$"
				)
		);
	}

	/**
	 * 默认「不可重试」的异常类型黑名单。
	 * <p>
	 * 采用<b>黑名单</b>而非白名单模型：默认重试一切异常，只排除下列确定性的永久失败——
	 * 重试它们只会白白消耗退避时间并掩盖真实缺陷。
	 * <p>
	 * 黑名单之外的异常一律视为可重试，包括但不限于：
	 * <ul>
	 * <li>{@link IOException} / {@link IOExceptionOnRuntime}（MQ、RPC 等基础设施瞬时故障）；</li>
	 * <li>ES 乐观并发冲突（{@code UnmatchEventVersionException}、{@code AggregateRootReferenceChangedException}）——
	 *     它们继承自 {@code RuntimeException} 而非 {@code IOException}，
	 *     若用白名单模型会被漏掉，而它们恰恰是最该重试的（重载聚合后重放命令）；</li>
	 * <li>其它任何 {@code RuntimeException}。</li>
	 * </ul>
	 * 业务侧若需表达「这个失败重试也没用」，让异常实现 {@link NonRetryableException} 即可
	 * （ejoker-core 的业务异常受模块依赖方向限制，无法在此直接列举）。
	 */
	private final static Class<?>[] DEFAULT_NON_RETRYABLE_TYPES = new Class<?>[] {
			// JDK：参数非法、空指针、状态非法 —— 多为编程错误，重试无意义
			IllegalArgumentException.class,
			NullPointerException.class,
			IllegalStateException.class,
			// EJoker 自有的参数类异常（均直接继承 RuntimeException，无共同基类，需逐个列出）
			ArgumentException.class,
			ArgumentNullException.class,
			ArgumentOutOfRangeException.class
	};

	/**
	 * cause 链遍历的最大深度上限，防止异常链成环导致死循环。
	 * <p>
	 * 该上限是<b>承重</b>的：若遍历逻辑回退为「只认 IO 异常」之类的无界 while 循环，
	 * 环状 cause 链会让判定线程永久自旋。包级可见以便测试直接引用该常量。
	 */
	static final int MAX_CAUSE_DEPTH = 32;

	/**
	 * 默认的可重试判定：黑名单模型。
	 * <p>
	 * 沿 cause 链逐环检查，只要<b>任意一环</b>命中黑名单类型或实现了
	 * {@link NonRetryableException}，即判定为不可重试。
	 * <p>
	 * 「任意一环命中即不可重试」是有意为之：包装异常（{@code AsyncWrapperException}、
	 * {@code IOExceptionOnRuntime} 等）不应掩盖内层的确定性失败。
	 */
	private static boolean defaultRetryOn(Throwable t) {
		Throwable current = t;
		for (int depth = 0; null != current && depth < MAX_CAUSE_DEPTH; depth++) {
			// Error（OOM / StackOverflowError / LinkageError 等）意味着 JVM 级永久损伤，
			// 重试只会加剧恶化并拖垮重试预算，判定为不可重试。
			// 因 Error 家族无法穷举具体类型，故不走 Class[] 黑名单而用 instanceof 直判。
			if (current instanceof Error) {
				return false;
			}
			if (current instanceof NonRetryableException) {
				return false;
			}
			for (Class<?> nonRetryableType : DEFAULT_NON_RETRYABLE_TYPES) {
				if (nonRetryableType.isInstance(current)) {
					return false;
				}
			}
			Throwable cause = current.getCause();
			// 防御异常链成环
			if (cause == current) {
				break;
			}
			current = cause;
		}
		return true;
	}

	public int getMaxAttempts() {
		return maxAttempts;
	}

	public long getBaseDelayMillis() {
		return baseDelayMillis;
	}

	public long getMaxDelayMillis() {
		return maxDelayMillis;
	}

	public boolean isFullJitterEnabled() {
		return fullJitterEnabled;
	}

	public boolean retryOn(Throwable t) {
		return retryOn.test(t);
	}

	public long nextBackoffMillis(int attemptIndex) {
		long cap = baseDelayMillis;
		int effectiveAttempt = Math.max(1, attemptIndex);
		for (int i = 1; i < effectiveAttempt; i++) {
			cap = Math.min(maxDelayMillis, cap * 2);
			if (cap == maxDelayMillis) {
				break;
			}
		}
		if (!fullJitterEnabled) {
			return cap;
		}
		return (long) (randomSupplier.getAsDouble() * cap);
	}
}
