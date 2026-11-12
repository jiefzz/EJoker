package pro.jk.ejoker.common.system.task.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * 锁住 {@link RetryPolicy} 的默认值、ControlTable 覆盖、异常分类与 Full Jitter 边界。
 *
 * @author JiefzzLon
 */
@Timeout(value = 5, unit = TimeUnit.SECONDS)
@TestMethodOrder(OrderAnnotation.class)
public class RetryPolicyTest {

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

	@Test
	@Order(1)
	public void testControlTableOverride() {
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
		assertTrue(policy.retryOn(new IOException("io error")));
		assertTrue(policy.retryOn(new IOExceptionOnRuntime(new IOException("io error"))));
		assertFalse(policy.retryOn(new RuntimeException("logic error")));
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
