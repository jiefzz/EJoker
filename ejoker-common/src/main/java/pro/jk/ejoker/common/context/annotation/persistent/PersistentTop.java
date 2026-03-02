package pro.jk.ejoker.common.context.annotation.persistent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import pro.jk.ejoker.common.context.annotation.EJokerAnnotation;

/**
 * While doing persistent job, we do not find any properties from it's father.
 * @author JiefzzLon
 * <br /> 这个注解标记一个对象的是序列化的顶部，不再往上递归
 */
@Target({ ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@EJokerAnnotation
public @interface PersistentTop {

}
