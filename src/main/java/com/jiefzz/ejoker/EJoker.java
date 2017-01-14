package com.jiefzz.ejoker;

import com.jiefzz.ejoker.z.common.context.IEJokerContext;
import com.jiefzz.ejoker.z.common.context.IEJokerSimpleContext;
import com.jiefzz.ejoker.z.common.context.impl.DefaultEJokerContext;

/**
 * E-Joker instance provider. E-Joker context provider.
 * @author JiefzzLon
 *
 */
public class EJoker {
	
	// public:
	
	public static EJoker getInstance(){
		if ( instance == null )
			instance = new EJoker();
		return instance;
	}
	
	public IEJokerSimpleContext getEJokerContext(){
		return context;
	}

	// private:
	
	private EJoker() {
		context = new DefaultEJokerContext();
		context.scanPackageClassMeta("com.jiefzz.ejoker");
	}

	// properties:
	
	private static EJoker instance;
	private IEJokerContext context;
	
}