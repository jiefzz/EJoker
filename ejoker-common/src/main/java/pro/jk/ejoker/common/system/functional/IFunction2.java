package pro.jk.ejoker.common.system.functional;

import pro.jk.ejoker.common.legacy.Suspendable;

@FunctionalInterface
public interface IFunction2<TResult, TP1, TP2> {

	@Suspendable
	public TResult trigger(TP1 p1, TP2 p2);
	
}
