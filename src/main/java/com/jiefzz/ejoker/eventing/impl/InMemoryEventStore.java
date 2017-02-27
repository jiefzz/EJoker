package com.jiefzz.ejoker.eventing.impl;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jiefzz.ejoker.eventing.DomainEventStream;
import com.jiefzz.ejoker.eventing.EventAppendResult;
import com.jiefzz.ejoker.eventing.IEventSerializer;
import com.jiefzz.ejoker.eventing.IEventStore;
import com.jiefzz.ejoker.infrastructure.IJSONConverter;
import com.jiefzz.ejoker.z.common.context.annotation.context.Dependence;
import com.jiefzz.ejoker.z.common.context.annotation.context.EService;
import com.jiefzz.ejoker.z.common.io.AsyncTaskResult;
import com.jiefzz.ejoker.z.common.io.AsyncTaskStatus;
import com.jiefzz.ejoker.z.common.io.AsyncTaskResultBase;
import com.jiefzz.ejoker.z.common.system.extension.acrossSupport.RipenFuture;

@EService
public class InMemoryEventStore implements IEventStore {

	private final static Logger logger = LoggerFactory.getLogger(InMemoryEventStore.class);
	
	@Resource
	IJSONConverter jsonConverter;
	@Dependence
	IEventSerializer eventSerializer;
	
	public Map<Long, Object> mStorage = new LinkedHashMap<Long, Object>();

	@Override
	public boolean isSupportBatchAppendEvent() {
		// TODO 暂时不支持批量持久化
		return false;
	}

	@Override
	public void setSupportBatchAppendEvent(boolean supportBatchAppendEvent) {
	}

	@Override
	public void batchAppendAsync(LinkedHashSet<DomainEventStream> eventStreams) {
		Iterator<DomainEventStream> iterator = eventStreams.iterator();
		while(iterator.hasNext())
			appendAsync(iterator.next());
	}
	
	private AtomicLong atLong = new AtomicLong(0);

	@Override
	public Future<AsyncTaskResultBase> appendAsync(DomainEventStream eventStream) {
//		LinkedHashMap<String, Object> data = new LinkedHashMap<String, Object>();
//		data.put("aggregateRootId", eventStream.getAggregateRootId());
//		data.put("aggregateRootTypeName", eventStream.getAggregateRootTypeName());
//		data.put("commandId", eventStream.getCommandId());
//		data.put("version", eventStream.getVersion());
//		data.put("createdOn", eventStream.getTimestamp());
//		data.put("events", jsonConverter.convert(
//				eventSerializer.serializer(
//						eventStream.getEvents()
//				)
//			)
//		);
		logger.debug("模拟io! 执行次数: {}, EventStream: {}.", atLong.incrementAndGet(), eventStream.toString());
		appendsync(eventStream);
		RipenFuture<AsyncTaskResultBase> future = new RipenFuture<AsyncTaskResultBase>();
		future.trySetResult(new AsyncTaskResult<EventAppendResult>(AsyncTaskStatus.Success, EventAppendResult.Success));
		return future;
	}

	@Override
	public void appendsync(DomainEventStream eventStream) {
		mStorage.put(System.currentTimeMillis(), jsonConverter.convert(eventStream));
	}

	@Override
	public void findAsync(String aggregateRootId, int version) {
		// TODO Auto-generated method stub
		logger.debug(String.format("invoke %s#%s(%s, %d)", this.getClass().getName(), "findAsync", aggregateRootId, version));
	}

	@Override
	public void findAsync(String aggregateRootId, String commandId) {
		// TODO Auto-generated method stub
		logger.debug(String.format("invoke %s#%s(%s, %d)", this.getClass().getName(), "findAsync", aggregateRootId, commandId));
		
	}
	
	@Override
	public Collection<DomainEventStream> queryAggregateEvents(String aggregateRootId, String aggregateRootTypeName,
			long minVersion, long maxVersion) {
		return null;
	}

	@Override
	public void queryAggregateEventsAsync(String aggregateRootId, String aggregateRootTypeName, long minVersion,
			long maxVersion) {
	}

}