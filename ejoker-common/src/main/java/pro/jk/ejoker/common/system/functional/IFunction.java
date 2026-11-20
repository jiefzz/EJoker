package pro.jk.ejoker.common.system.functional;

@FunctionalInterface
public interface IFunction<TResult> {

	public TResult trigger();
	
}
