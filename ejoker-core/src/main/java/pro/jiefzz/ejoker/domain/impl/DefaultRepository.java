package pro.jiefzz.ejoker.domain.impl;

import static pro.jiefzz.ejoker.common.system.extension.LangUtil.await;

import java.util.concurrent.Future;

import pro.jiefzz.ejoker.common.context.annotation.context.Dependence;
import pro.jiefzz.ejoker.common.context.annotation.context.EService;
import pro.jiefzz.ejoker.common.system.exceptions.ArgumentNullException;
import pro.jiefzz.ejoker.common.system.extension.acrossSupport.EJokerFutureUtil;
import pro.jiefzz.ejoker.common.system.task.context.SystemAsyncHelper;
import pro.jiefzz.ejoker.domain.IAggregateRoot;
import pro.jiefzz.ejoker.domain.IMemoryCache;
import pro.jiefzz.ejoker.domain.IRepository;

@EService
public class DefaultRepository implements IRepository {

	@Dependence
	private IMemoryCache memoryCache;
	
	@Dependence
	private SystemAsyncHelper systemAsyncHelper;

	@Override
	public Future<IAggregateRoot> getAsync(Class<IAggregateRoot> aggregateRootType, Object aggregateRootId) {
		if (aggregateRootType == null)
			throw new ArgumentNullException("aggregateRootType");
		if (aggregateRootId == null)
			throw new ArgumentNullException("aggregateRootId");

		// TODO @await
		IAggregateRoot aggregateRoot = await(memoryCache.getAsync(aggregateRootId, aggregateRootType));
		if(null == aggregateRoot)
			aggregateRoot =  await(memoryCache.refreshAggregateFromEventStoreAsync(aggregateRootType, aggregateRootId.toString()));
		return EJokerFutureUtil.completeFuture(aggregateRoot);
		
	}

}
