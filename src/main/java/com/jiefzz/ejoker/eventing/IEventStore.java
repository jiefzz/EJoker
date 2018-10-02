package com.jiefzz.ejoker.eventing;

import java.util.Collection;
import java.util.LinkedHashSet;

import com.jiefzz.ejoker.z.common.io.AsyncTaskResult;
import com.jiefzz.ejoker.z.common.system.extension.acrossSupport.SystemFutureWrapper;

public interface IEventStore {
	
	public boolean isSupportBatchAppendEvent();
	
	public void setSupportBatchAppendEvent(boolean supportBatchAppendEvent);
	
	public SystemFutureWrapper<AsyncTaskResult<EventAppendResult>> batchAppendAsync(LinkedHashSet<DomainEventStream> eventStreams);
	
	public SystemFutureWrapper<AsyncTaskResult<EventAppendResult>> appendAsync(DomainEventStream eventStream);
	
	public SystemFutureWrapper<AsyncTaskResult<DomainEventStream>> findAsync(String aggregateRootId, int version);
	
	public SystemFutureWrapper<AsyncTaskResult<DomainEventStream>> findAsync(String aggregateRootId, String commandId);
	
	/**
	 * 为保证返回顺序请使用LinkedHashSet
	 * // Collections.synchronizedSet(new LinkedHashSet<String>());
	 * @param aggregateRootId
	 * @param aggregateRootTypeName
	 * @param minVersion
	 * @param maxVersion
	 * @return
	 */
	public Collection<DomainEventStream> queryAggregateEvents(String aggregateRootId, String aggregateRootTypeName, long minVersion, long maxVersion);
    
	public SystemFutureWrapper<AsyncTaskResult<Collection<DomainEventStream>>> queryAggregateEventsAsync(String aggregateRootId, String aggregateRootTypeName, long minVersion, long maxVersion);

	
}
