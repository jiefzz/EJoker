package pro.jiefzz.ejoker.common.system.task;

public final class AsyncTaskResult<T> {

	public final static AsyncTaskResult<Void> Success = new AsyncTaskResult<>(AsyncTaskStatus.Success);

	protected final AsyncTaskStatus status;
	
	protected final String errorMessage;
	
//	private final T data;
	private final Object data;

	public AsyncTaskResult(AsyncTaskStatus status) {
		this(status, null, null);
	}
	public AsyncTaskResult(T data) {
		this(AsyncTaskStatus.Success, null, data);
	}
//	public AsyncTaskResult(AsyncTaskStatus status, T data) {
//		this(status, null, data);
//	}
	public AsyncTaskResult(AsyncTaskStatus status, String errorMessage) {
		this(status, errorMessage, null);
	}
	public AsyncTaskResult(AsyncTaskStatus status, String errorMessage, T data) {
		this.status = status;
		this.errorMessage = errorMessage;
		this.data = data;
	}

	/* ========Getter and Setter========= */

	
	public AsyncTaskStatus getStatus() {
		return status;
	}

	public String getErrorMessage() {
		return errorMessage;
	}
	
	@SuppressWarnings("unchecked")
	public T getData() {
		return (T )data;
	}

}
