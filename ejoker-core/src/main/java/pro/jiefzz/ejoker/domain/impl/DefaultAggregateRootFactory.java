package pro.jiefzz.ejoker.domain.impl;

import pro.jiefzz.ejoker.common.context.annotation.context.EService;
import pro.jiefzz.ejoker.common.system.helper.StringHelper;
import pro.jiefzz.ejoker.domain.IAggregateRoot;
import pro.jiefzz.ejoker.domain.IAggregateRootFactory;

@EService
public class DefaultAggregateRootFactory implements IAggregateRootFactory {

	@Override
	public IAggregateRoot createAggregateRoot(Class<? extends IAggregateRoot> aggregateRootType) {
		try {
			return aggregateRootType.newInstance();
		} catch (InstantiationException | IllegalAccessException e) {
			throw new RuntimeException(StringHelper.fill("Could not create new instance!!! [type: {}]", aggregateRootType.getName()), e) ;
		}
	}

}
