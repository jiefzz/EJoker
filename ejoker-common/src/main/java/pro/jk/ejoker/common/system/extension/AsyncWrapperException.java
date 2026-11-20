package pro.jk.ejoker.common.system.extension;

/**
 * 对异步任务中执行时出现的异常进行包装<br>
 * * 这样吧所以checked和unchecked的异常都封装起来
 * @author kimffy
 *
 */
public class AsyncWrapperException extends RuntimeException {

	private static final long serialVersionUID = 4393129161706628965L;

	public AsyncWrapperException(Throwable cause) {
		super("Please see cause!", cause);
	}
	
	public AsyncWrapperException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * 解开 AsyncWrapperException 包装，返回最内层非 AsyncWrapperException 的 Throwable。
	 * <p>
	 * 契约：
	 * <ul>
	 * <li>非包装入参<b>原样返回</b>（幂等，调用方无需先做 instanceof 预判）；</li>
	 * <li>根因可能是任意 Throwable —— 包括 Error（任务抛出 OOM/StackOverflowError 等经
	 *     FutureTask 捕获后成为包装链的 cause），Error 根因<b>原样返回</b>，
	 *     绝不做 {@code (Exception)} 强转（历史实现在此抛 ClassCastException，
	 *     连锁导致 IOHelper 的 do/while 无限重放 mainAction）；</li>
	 * <li>cause 缺失（null）或链成环时返回当前层的 AsyncWrapperException，
	 *     <b>永不因解包而返回 null</b>（历史实现返回 null 会令下游 NPE）；</li>
	 * <li>遍历带深度上限，防御理论上的成环 cause 链。</li>
	 * </ul>
	 *
	 * @param t 待解包的异常，契约上应非 null（catch 语句绑定的异常对象恒非 null）
	 * @return 最内层非 AsyncWrapperException 的 Throwable；入参为 null 时返回 null
	 */
	public final static Throwable getActuallyCause(Throwable t) {
		Throwable e = t;
		for(int depth = 0; null != e && e instanceof AsyncWrapperException && depth < 32; depth++) {
			Throwable cause = e.getCause();
			// cause 缺失或自环：无法继续解包，返回当前层（保持非 null 返回）
			if(null == cause || cause == e)
				return e;
			e = cause;
		}
		return e;
	}
}
