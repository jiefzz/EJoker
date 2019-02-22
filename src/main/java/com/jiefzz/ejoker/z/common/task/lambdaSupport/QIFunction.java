package com.jiefzz.ejoker.z.common.task.lambdaSupport;

import co.paralleluniverse.fibers.SuspendExecution;

public interface QIFunction<T> {

	public T trigger() throws SuspendExecution, Exception;
	
}
