package com.jiefzz.ejoker.z.common.system.functional;

import co.paralleluniverse.fibers.Suspendable;

@FunctionalInterface
public interface IVoidFunction {

	@Suspendable
	public void trigger();
	
}
