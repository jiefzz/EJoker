package pro.jk.ejoker.common.context.dev2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pro.jk.ejoker.common.context.ContextRuntimeException;
import pro.jk.ejoker.common.system.enhance.StringUtilx;
import pro.jk.ejoker.common.system.functional.IVoidFunction1;

/**
 * 类构建器，同时支持构建后的直接触发自定义的后继逻辑处理
 */
public final class EJokerInstanceBuilder {
	
	private final static Logger logger = LoggerFactory.getLogger(EJokerInstanceBuilder.class);

	public static Object doCreate(Class<?> clazz, IVoidFunction1<Object> afterEffector) {
		Object newInstance;
		try {
			// 在这里可以看到，这里只能无参构建
			newInstance = clazz.newInstance();
		} catch (InstantiationException | IllegalAccessException ex) {
			String errInfo = StringUtilx.fmt("Cannot create new instance!!! [type: {}]", clazz.getName());
			logger.error(errInfo, ex);
			throw new ContextRuntimeException(errInfo, ex);
		}
		if(null != afterEffector)
			afterEffector.trigger(newInstance);
		return newInstance;
	}
}
