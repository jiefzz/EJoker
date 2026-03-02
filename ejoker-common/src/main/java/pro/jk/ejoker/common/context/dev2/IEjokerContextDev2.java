package pro.jk.ejoker.common.context.dev2;

import pro.jk.ejoker.common.system.functional.IVoidFunction;

/**
 * 完整IoC的需要有的接口，
 * <br /> 注册，反注册，注销IoC容器（以及hook实现），以及另加一个主动托管服务类(供特殊需求，手动构建，再托管到IoC中）
 */
public interface IEjokerContextDev2 extends IEJokerSimpleContext/*, IEJokerClazzScanner*/ {

	public void refresh();
	
	public void discard();

	public void destroyRegister(IVoidFunction vf, int priority);
	
	default public void destroyRegister(IVoidFunction vf) {
		destroyRegister(vf, 50);
	}
	
	/**
	 * 浅注册： 仅仅注入 当前表现态 的类型 与 传入对象的 对应关系
	 * @param instance
	 */
	public void shallowRegister(Object instance);
	
}
