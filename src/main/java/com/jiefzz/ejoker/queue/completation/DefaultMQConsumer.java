package com.jiefzz.ejoker.queue.completation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.rocketmq.client.consumer.PullResult;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.RPCHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jiefzz.ejoker.EJokerEnvironment;
import com.jiefzz.ejoker.z.common.system.functional.IFunction1;
import com.jiefzz.ejoker.z.common.system.functional.IVoidFunction2;
import com.jiefzz.ejoker.z.common.utils.Ensure;

public class DefaultMQConsumer extends org.apache.rocketmq.client.consumer.DefaultMQPullConsumer {
	
	private final static Logger logger = LoggerFactory.getLogger(DefaultMQConsumer.class);

	private final static int maxBatch = EJokerEnvironment.MAX_BATCH_COMMANDS;
	
	private String focusTopic = "";
	
	private int maxBatchSize = maxBatch;
	
	private final AtomicBoolean boot = new AtomicBoolean(false);

	private final AtomicBoolean pasue = new AtomicBoolean(false);

	private final AtomicBoolean hasException = new AtomicBoolean(false);
	
	private Throwable lastException = null;

	private IFunction1<Boolean, MessageQueue> queueMatcher = null;

	private Set<MessageQueue> matchQueue = new HashSet<>();

	private IVoidFunction2<EJokerQueueMessage, IEJokerQueueMessageContext> messageProcessor = null;
	
	private Map<MessageQueue, Map<Long, String>> aheadCompletion = new HashMap<>();
	
	private Map<MessageQueue, AtomicLong> offsetConsumedDict = new HashMap<>();

	public DefaultMQConsumer() {
		super();
	}

	public DefaultMQConsumer(RPCHook rpcHook) {
		super(rpcHook);
	}

	public DefaultMQConsumer(String consumerGroup, RPCHook rpcHook) {
		super(consumerGroup, rpcHook);
	}

	public DefaultMQConsumer(String consumerGroup) {
		super(consumerGroup);
	}

	public void registerEJokerCallback(IVoidFunction2<EJokerQueueMessage, IEJokerQueueMessageContext> vf) {
		Ensure.equal(false, boot.get(), "DefaultMQConsumer.start");
		this.messageProcessor = vf;
	}

	public void subscribe(String topic, String subExpression) {
		Ensure.equal(false, boot.get(), "DefaultMQConsumer.start");
		this.focusTopic = topic;
	}
	
	public void start() throws MQClientException {
		super.start();
		
		loadSubcribeInfo();
		doWork();
	}
	
	public void setMaxBatchSize(int maxBatchSize) {
		this.maxBatchSize = maxBatchSize;
	}
	
	public int getMaxBatchSize() {
		return this.maxBatchSize;
	}

	private void doWork() {
		for (MessageQueue mq : matchQueue) {
			
			final AtomicLong maxOffset;
			long minOffset;
			long consumedOffset;
			try {
				maxOffset = new AtomicLong(maxOffset(mq));
				minOffset = minOffset(mq);
				consumedOffset = fetchConsumeOffset(mq, true);
			} catch (MQClientException e3) {
				throw new RuntimeException(e3);
			}
			
			logger.debug("Consume from the queue: {}", mq);
			logger.debug("consumer.maxOffset(mq) = {}", maxOffset.get());
			logger.debug("consumer.minOffset(mq) = {}", minOffset);
			logger.debug("consumer.fetchConsumeOffset(mq, true) = {}", consumedOffset);
			
			new Thread(() -> {
				
				final AtomicLong offset = new AtomicLong(consumedOffset);
				
				for( ; !hasException.get(); ) {

					try {
						
						while(pasue.get()) {
							logger.debug("The consumer has been pasue! Waiting!");
							TimeUnit.MILLISECONDS.sleep(600);
						}
					
						long currentOffset = offset.get();
						if( maxOffset.get() <= currentOffset ) {
//							logger.debug("The queue has no more message! Waiting!");
							TimeUnit.MILLISECONDS.sleep(600);
							maxOffset.getAndSet(maxOffset(mq));
							continue;
						}
						
						logger.debug("current offset = {}", currentOffset);
						
						// TODO tag 置为 null，消费端让mqSelecter发挥作用，tag让其在生产端发挥作用吧
						PullResult pullResult = pullBlockIfNotFound(mq, null, currentOffset, maxBatchSize);
						
						switch (pullResult.getPullStatus()) {
						case FOUND:
							List<MessageExt> messageExtList = pullResult.getMsgFoundList();
							for (int i = 0; i<messageExtList.size(); i++) {
								final long consumingOffset = currentOffset + i + 1;
								MessageExt rmqMsg = messageExtList.get(i);
								EJokerQueueMessage queueMessage = new EJokerQueueMessage(
										rmqMsg.getTopic(),
										rmqMsg.getFlag(),
										rmqMsg.getBody(),
										rmqMsg.getTags());
								messageProcessor.trigger(queueMessage, message -> tryMarkCompletion(mq, consumingOffset));
							}
							offset.getAndSet(pullResult.getNextBeginOffset());
							maxOffset.getAndSet(pullResult.getMaxOffset());
							break;
						case NO_MATCHED_MSG:
							logger.debug("NO_MATCHED_MSG");
							break;
						case NO_NEW_MSG:
							logger.debug("NO_NEW_MSG");
							break;
						case OFFSET_ILLEGAL:
							logger.debug("OFFSET_ILLEGAL");
							hasException.set(true);
							lastException = new RuntimeException("OFFSET_ILLEGAL");
							break;
						default:
							assert false;
						}
						
					} catch (Exception e) {
						if(hasException.compareAndSet(false, true))
							lastException = e;
						else
							e.printStackTrace();
						
						break;
					}
				}
				
				if(hasException.get())
					throw new RuntimeException(lastException);
				
			}).start();
		}

	}
	
	private void loadSubcribeInfo() {

		Set<MessageQueue> messageQueues;
		try {
			messageQueues = fetchSubscribeMessageQueues(focusTopic);
		} catch (MQClientException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}

		for (MessageQueue mq : messageQueues) {

			if (null != queueMatcher && !queueMatcher.trigger(mq)) {
				continue;
			}

			matchQueue.add(mq);
			
			{
				aheadCompletion.put(mq, new HashMap<>());
				offsetConsumedDict.put(mq, new AtomicLong(0));
				
				try {
					offsetConsumedDict.put(mq, new AtomicLong(fetchConsumeOffset(mq, true)));
				} catch (MQClientException e3) {
					throw new RuntimeException(e3);
				}
				
			}
		}
		
		if(0 == matchQueue.size())
			throw new RuntimeException("No queue was selected!!!");
		
	}
	
	private void tryMarkCompletion(MessageQueue mq, long comsumedOffset) {
		logger.info("Receive local completion. Queue: {}, offset {}", mq, comsumedOffset);
		AtomicLong currentComsumedOffset = offsetConsumedDict.get(mq);
		Map<Long, String> aheadOffsetDict = aheadCompletion.get(mq);
		
		if(!currentComsumedOffset.compareAndSet(comsumedOffset - 1, comsumedOffset)) {
			aheadOffsetDict.put(comsumedOffset, "");
			/// TODO 
			if(currentComsumedOffset.get() - comsumedOffset > 0) {
				/// TODO Whether this statement will occur?
			}
		} else {
			int delta = 1;
			for( ; null != aheadOffsetDict.remove(comsumedOffset + delta); delta++);
			delta --;
			if(delta > 0) {
				currentComsumedOffset.compareAndSet(comsumedOffset, comsumedOffset + delta);
			}
		}
		
	}
	
	public void syncOffsetToBroker() {
		for(MessageQueue mq : matchQueue)
			try {
				updateConsumeOffset(mq, offsetConsumedDict.get(mq).get());
			} catch (MQClientException e) {
				throw new RuntimeException(e);
			}
		/// TODO Should we check whether the cursor is changed or not?
		// Maybe we shouldn't take it into consideration.
		// Mandatory sync to broker! 
		getOffsetStore().persistAll(matchQueue);
		
//		logger.debug("show offset info: ");
//		showOffsetInfo()
//	}
//	
//	public void showOffsetInfo() {
//		for(MessageQueue mq : matchQueue)
//			logger.debug("offsetConsumedDict.get({}).get() = {}", mq, offsetConsumedDict.get(mq).get());
	}
}
