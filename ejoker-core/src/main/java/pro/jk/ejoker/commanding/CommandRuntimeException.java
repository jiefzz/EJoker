package pro.jk.ejoker.commanding;

import pro.jk.ejoker.common.system.exceptions.NonRetryableException;

/**
 * 命令处理相关的运行时异常。
 * <p>
 * 现有抛出场景均为<b>确定性失败</b>（命令处理器未实现、参数不匹配等），
 * 重试无意义，故实现 {@link NonRetryableException} 标记为不可重试。
 */
public class CommandRuntimeException extends RuntimeException implements NonRetryableException {

	private static final long serialVersionUID = -5603655875407351334L;

	public CommandRuntimeException() {
	}

	public CommandRuntimeException(String message) {
		super(message);
	}

	public CommandRuntimeException(Throwable cause) {
		super(cause);
	}

	public CommandRuntimeException(String message, Throwable cause) {
		super(message, cause);
	}

	public CommandRuntimeException(String message, Throwable cause, boolean enableSuppression,
			boolean writableStackTrace) {
		super(message, cause, enableSuppression, writableStackTrace);
	}

}
