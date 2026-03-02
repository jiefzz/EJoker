package pro.jk.ejoker.common.system.functional;

import pro.jk.ejoker.common.legacy.Suspendable;

@FunctionalInterface
public interface IVoidFunction {

	@Suspendable
	public void trigger();
	
}
