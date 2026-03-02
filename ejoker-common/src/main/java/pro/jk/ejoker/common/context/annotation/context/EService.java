package pro.jk.ejoker.common.context.annotation.context;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import pro.jk.ejoker.common.context.annotation.EJokerAnnotation;

/**
 * Tell the configureObject or contextObject to scan its handler method!!!
 * @author JiefzzLon
 * <br /> 标记 ej-ioc 容器 中托管的类， 在构建 ioc容器是会把这些服务类全部初始化
 * <br /> 这里不支持spring哪些复杂的依赖声明，例如构建函数，final标记等，这里仅仅支持无参构建和 {@link EService} 标记注入
 *
 */
@Target({ ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@EJokerAnnotation
public @interface EService {

	/**
	 * 这里的 {@link ESType} 有  {@link ESType#COMMAND_HANDLER} 和  {@link ESType#MESSAGE_HANDLER} 这两个有特殊的扫描方法的注入，
	 * <br /> 他们的handler方法会被构建为类型+handler的入口字典，是EJ的特殊支持
	 * <br /> 其他的用  {@link ESType#NORMAL} 即可
	 * @return
	 */
	public String type() default ESType.NORMAL;
	
}
