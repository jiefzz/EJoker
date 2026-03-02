package pro.jk.ejoker.common.context.annotation.context;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import pro.jk.ejoker.common.context.annotation.EJokerAnnotation;

/**
 * Tell the configureObject or contextObject to scan its handler method!!!
 * @author JiefzzLon
 * <br /> 标记 ej-ioc 容器 中托管的类的初始化方法，同时正常初始化优先级，可自行编排
 */
@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@EJokerAnnotation
public @interface EInitialize {

	/**
	 * 优先级，越小越先执行
	 * @return
	 */
	public int priority() default 50;
	
}
