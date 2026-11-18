package pro.jk.ejoker.common.system.task.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.TestMethodOrder;

import pro.jk.ejoker.common.system.component.ControlTable;
import pro.jk.ejoker.common.system.exceptions.ArgumentException;
import pro.jk.ejoker.common.system.exceptions.ArgumentNullException;
import pro.jk.ejoker.common.system.exceptions.ArgumentOutOfRangeException;
import pro.jk.ejoker.common.system.exceptions.NonRetryableException;

/**
 * 锁住 {@link RetryPolicy} 的默认值、ControlTable 覆盖、异常分类与 Full Jitter 边界。
 *
 * @author JiefzzLon
 */
@Timeout(value = 5, unit = TimeUnit.SECONDS)
@TestMethodOrder(OrderAnnotation.class)
public class RetryPolicyTest {

	/**
	 * 用于验证「标记接口 ⇒ 不可重试」的测试异常。
	 */
	static class MarkedPermanentException extends RuntimeException implements NonRetryableException {
		private static final long serialVersionUID = 1L;
		MarkedPermanentException(String message) {
			super(message);
		}
	}

	/**
	 * 用于验证「cause 链成环」的防御性测试异常。
	 * <p>
	 * <b>关键设计</b>：{@code getCause()} 在被调用超过 {@link #SELF_CAUSE_LIMIT} 次后返回 null。
	 * 这样即使实现的环检测被回退、遍历变成无界循环，测试也只是【多转几圈】而非
	 * 【永久挂死】——一个会挂死的测试比没有测试更糟（CI 上构建会卡住而不是变红），
	 * 且挂死还会留下一个永久自旋、吃满 CPU 的线程。
	 */
	static class CyclicException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		/** 兜底上限：达到后 getCause() 返回 null，让测试能自行终止。 */
		static final int SELF_CAUSE_LIMIT = 1_000_000;

		final AtomicInteger causeCalls = new AtomicInteger(0);

		CyclicException(String message) {
			super(message);
		}

		@Override
		public synchronized Throwable getCause() {
			if (causeCalls.incrementAndGet() > SELF_CAUSE_LIMIT) {
				return null;
			}
			return this;
		}
	}

	@BeforeAll
	static void setUpSystemProperties() {
		System.setProperty("ejoker.io.retry.maxAttempts", "9");
		System.setProperty("ejoker.io.retry.baseDelayMillis", "300");
		System.setProperty("ejoker.io.retry.maxDelayMillis", "4000");
		System.setProperty("ejoker.io.retry.fullJitter", "false");
	}

	@AfterAll
	static void clearSystemProperties() {
		System.clearProperty("ejoker.io.retry.maxAttempts");
		System.clearProperty("ejoker.io.retry.baseDelayMillis");
		System.clearProperty("ejoker.io.retry.maxDelayMillis");
		System.clearProperty("ejoker.io.retry.fullJitter");
	}

	@SuppressWarnings("unchecked")
	private static void resetControlTableCache() {
		try {
			Field field = ControlTable.class.getDeclaredField("CONFIG_CACHE_STORE");
			field.setAccessible(true);
			Map<Object, Object> cache = (Map<Object, Object>) field.get(null);
			cache.clear();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	@Order(1)
	public void testControlTableOverride() {
		resetControlTableCache();
		RetryPolicy policy = RetryPolicy.fromControlTable();
		assertEquals(9, policy.getMaxAttempts());
		assertEquals(300L, policy.getBaseDelayMillis());
		assertEquals(4000L, policy.getMaxDelayMillis());
		assertFalse(policy.isFullJitterEnabled());
	}

	@Test
	@Order(2)
	public void testDefaultPolicyBounds() {
		RetryPolicy policy = RetryPolicy.defaultPolicy();
		assertEquals(5, policy.getMaxAttempts());
		assertEquals(200L, policy.getBaseDelayMillis());
		assertEquals(5000L, policy.getMaxDelayMillis());
		assertTrue(policy.isFullJitterEnabled());
	}

	@Test
	public void testRetryOnClassification() {
		RetryPolicy policy = RetryPolicy.defaultPolicy();

		// 黑名单模型：默认重试一切异常
		assertTrue(policy.retryOn(new IOException("io error")));
		assertTrue(policy.retryOn(new IOExceptionOnRuntime(new IOException("io error"))));
		assertTrue(policy.retryOn(new RuntimeException("unknown failure")));
	}

	@Test
	public void testNonRetryableBlacklist() {
		RetryPolicy policy = RetryPolicy.defaultPolicy();

		// 确定性失败不应重试
		assertFalse(policy.retryOn(new IllegalArgumentException("bad arg")));
		assertFalse(policy.retryOn(new NullPointerException("npe")));
		assertFalse(policy.retryOn(new IllegalStateException("bad state")));
		assertFalse(policy.retryOn(new ArgumentException("bad arg")));
		assertFalse(policy.retryOn(new ArgumentNullException("null arg")));
		assertFalse(policy.retryOn(new ArgumentOutOfRangeException("out of range")));
	}

	@Test
	public void testNonRetryableIsDetectedThroughCauseChain() {
		RetryPolicy policy = RetryPolicy.defaultPolicy();

		// 包装异常不应掩盖内层的确定性失败
		assertFalse(policy.retryOn(new RuntimeException("wrapper", new IllegalArgumentException("root cause"))));
		assertFalse(policy.retryOn(new IOExceptionOnRuntime(new IOException("io", new IllegalArgumentException("root")))));

		// 但纯 IO 故障仍应可重试
		assertTrue(policy.retryOn(new IOExceptionOnRuntime(new IOException("plain io"))));
	}

	@Test
	public void testMarkerInterfaceMakesExceptionNonRetryable() {
		RetryPolicy policy = RetryPolicy.defaultPolicy();

		// 业务异常通过标记接口表达「重试无意义」（跨模块场景）
		assertFalse(policy.retryOn(new MarkedPermanentException("permanent")));
		// 被包装的标记异常同样不可重试
		assertFalse(policy.retryOn(new IOExceptionOnRuntime(new IOException("wrap", new MarkedPermanentException("permanent")))));
	}

	@Test
	public void testCyclicCauseChainIsBounded() {
		RetryPolicy policy = RetryPolicy.defaultPolicy();

		// 异常链成环是防御性场景：判定必须终止，且不得无界遍历
		CyclicException cyclic = new CyclicException("cycle");
		assertTrue(policy.retryOn(cyclic), "环状 cause 链仍应给出判定结果（视为可重试）");
		assertTrue(cyclic.causeCalls.get() <= RetryPolicy.MAX_CAUSE_DEPTH,
				"cause 链遍历必须有深度上限，实际调用 getCause() " + cyclic.causeCalls.get()
				+ " 次（上限 " + RetryPolicy.MAX_CAUSE_DEPTH + "）");
	}

	@Test
	public void testNonePolicyDisablesRetry() {
		RetryPolicy policy = RetryPolicy.none();
		assertEquals(1, policy.getMaxAttempts());
		assertEquals(0L, policy.getBaseDelayMillis());
	}

	@Test
	public void testWithMaxAttemptsKeepsDefaultBackoff() {
		RetryPolicy policy = RetryPolicy.withMaxAttempts(3);
		assertEquals(3, policy.getMaxAttempts());
		assertEquals(200L, policy.getBaseDelayMillis());
		assertEquals(5000L, policy.getMaxDelayMillis());
		assertTrue(policy.isFullJitterEnabled());
	}

	@Test
	public void testLegacyFactoryMapsToBoundedPolicy() {
		RetryPolicy policy = RetryPolicy.legacyOf(4, 250L);
		assertEquals(4, policy.getMaxAttempts());
		assertEquals(250L, policy.getBaseDelayMillis());
		assertEquals(250L, policy.getMaxDelayMillis());
		assertFalse(policy.isFullJitterEnabled());
	}

	@Test
	public void testFullJitterBackoffBounds() {
		DoubleSupplier zero = () -> 0.0d;
		DoubleSupplier one = () -> 1.0d;

		RetryPolicy zeroPolicy = new RetryPolicy(5, 200L, 5000L, true, zero, null);
		RetryPolicy onePolicy = new RetryPolicy(5, 200L, 5000L, true, one, null);

		assertEquals(0L, zeroPolicy.nextBackoffMillis(1));
		assertEquals(200L, onePolicy.nextBackoffMillis(1));
		assertEquals(3200L, onePolicy.nextBackoffMillis(5));
		assertEquals(5000L, onePolicy.nextBackoffMillis(10));
	}
}
