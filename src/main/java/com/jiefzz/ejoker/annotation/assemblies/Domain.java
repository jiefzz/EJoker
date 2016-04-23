package com.jiefzz.ejoker.annotation.assemblies;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.jiefzz.ejoker.annotation.EJokerAnnotation;

/**
 * Tell the configureObject or contextObject to scan its handler method!!!
 * @author JiefzzLon
 *
 */
@Target({ ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@EJokerAnnotation
public @interface Domain {

}
