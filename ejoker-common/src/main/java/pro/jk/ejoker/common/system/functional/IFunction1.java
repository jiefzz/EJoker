package pro.jk.ejoker.common.system.functional;

import pro.jk.ejoker.common.legacy.Suspendable;

@FunctionalInterface
public interface IFunction1<TResult, TP1> {

	@Suspendable
	public TResult trigger(TP1 p1);
	
}
