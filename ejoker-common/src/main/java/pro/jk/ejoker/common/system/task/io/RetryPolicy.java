package pro.jk.ejoker.common.system.task.io;

import java.io.IOException;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;

import pro.jk.ejoker.common.system.constant.ConfigKeyPrimary;
import pro.jk.ejoker.common.system.component.ControlTable;
import pro.jk.ejoker.common.system.component.ControlTable.RegisterTuple;

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

	private static boolean defaultRetryOn(Throwable t) {
		Throwable current = t;
		while (null != current) {
			if (current instanceof IOException || current instanceof IOExceptionOnRuntime) {
				return true;
			}
			current = current.getCause();
		}
		return false;
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
