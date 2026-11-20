package pro.jk.ejoker.common.system.functional;

@FunctionalInterface
public interface IFunction1<TResult, TP1> {

	public TResult trigger(TP1 p1);
	
}
