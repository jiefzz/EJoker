package pro.jk.ejoker.common.system.functional;

import pro.jk.ejoker.common.legacy.Suspendable;

@FunctionalInterface
public interface IFunction<TResult> {

	@Suspendable
	public TResult trigger();
	
}
