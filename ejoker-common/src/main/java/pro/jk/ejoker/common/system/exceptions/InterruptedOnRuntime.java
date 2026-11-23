package pro.jk.ejoker.common.system.exceptions;

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
