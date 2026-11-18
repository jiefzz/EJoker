package pro.jk.ejoker.common.system.exceptions;

/**
 * 标记「永久性失败」异常：重试无意义，应当立即进入终态。
 * <p>
 * {@link pro.jk.ejoker.common.system.task.io.RetryPolicy} 采用<b>黑名单</b>模型：
 * 默认重试一切异常，仅当异常（<b>或其 cause 链上的任一环</b>）实现本接口、
 * 或命中内置黑名单类型时，才判定为不可重试。
 * <p>
 * 之所以需要这个标记接口：ejoker-common 无法引用 ejoker-core 中的业务异常类型
 * （模块依赖方向是 core → common），跨模块表达「这个失败重试也没用」只能靠标记。
 * <p>
 * 典型适用场景：处理器未实现、参数非法、配置错误等确定性失败。
 *
 * @author JiefzzLon
 */
public interface NonRetryableException {
}
