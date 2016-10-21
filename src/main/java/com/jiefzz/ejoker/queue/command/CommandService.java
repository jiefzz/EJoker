package com.jiefzz.ejoker.queue.command;

import java.nio.charset.Charset;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jiefzz.ejoker.commanding.CommandExecuteTimeoutException;
import com.jiefzz.ejoker.commanding.CommandResult;
import com.jiefzz.ejoker.commanding.CommandReturnType;
import com.jiefzz.ejoker.commanding.ICommand;
import com.jiefzz.ejoker.commanding.ICommandRoutingKeyProvider;
import com.jiefzz.ejoker.commanding.ICommandService;
import com.jiefzz.ejoker.commanding.ICommandTopicProvider;
import com.jiefzz.ejoker.infrastructure.IJSONConverter;
import com.jiefzz.ejoker.queue.QueueMessageTypeCode;
import com.jiefzz.ejoker.queue.SendQueueMessageService;
import com.jiefzz.ejoker.z.common.context.annotation.context.EService;
import com.jiefzz.ejoker.z.common.io.AsyncTaskResult;
import com.jiefzz.ejoker.z.common.io.AsyncTaskStatus;
import com.jiefzz.ejoker.z.common.io.BaseAsyncTaskResult;
import com.jiefzz.ejoker.z.common.system.extension.FutureTaskCompletionSource;
import com.jiefzz.ejoker.z.common.system.extension.FutureTaskUtils;
import com.jiefzz.ejoker.z.common.task.AsyncPool;
import com.jiefzz.ejoker.z.common.task.IAsyncTask;
import com.jiefzz.ejoker.z.common.task.ThreadPoolMaster;
import com.jiefzz.ejoker.z.common.utilities.Ensure;
import com.jiefzz.ejoker.z.queue.IConsumer;
import com.jiefzz.ejoker.z.queue.IProducer;
import com.jiefzz.ejoker.z.queue.IQueueWokerService;
import com.jiefzz.ejoker.z.queue.protocols.Message;

/**
 * @author JiefzzLon
 *
 */
@EService
public class CommandService implements ICommandService, IQueueWokerService {

	final static Logger logger = LoggerFactory.getLogger(CommandService.class);
	
	/**
	 * TODO 辅助实现 async/await 调用
	 */
	private AsyncPool asyncPool = ThreadPoolMaster.getPoolInstance(CommandService.class);
	
	/**
	 * all command will send by this object.
	 */
	@Resource
	private SendQueueMessageService sendQueueMessageService;
	@Resource
	private IJSONConverter jsonConverter;
	@Resource
	private ICommandTopicProvider commandTopicProvider;
	@Resource
	private ICommandRoutingKeyProvider commandRouteKeyProvider;
	@Resource
	private CommandResultProcessor commandResultProcessor;

	private IProducer producer;

	public IProducer getProducer() { return producer; }
	public CommandService useProducer(IProducer producer) { this.producer = producer; return this;}

	@Override
	public IQueueWokerService start() {
		producer.start();
		return this;
	}

	@Override
	public IQueueWokerService subscribe(String topic) {
		logger.warn("The method: {}.subscribe(String topic) should not be use! Please fix it.", this.getClass().getName());
		return this;
	}

	@Override
	public IQueueWokerService shutdown() {
		producer.shutdown();
		return this;
	}
	
	@Override
	public Future<BaseAsyncTaskResult> sendAsync(ICommand command) {
		try {
			return sendQueueMessageService.sendMessageAsync(producer, buildCommandMessage(command), commandRouteKeyProvider.getRoutingKey(command));
		} catch ( Exception e ) {
			e.printStackTrace();
			return FutureTaskUtils.buildFromResult(new BaseAsyncTaskResult(AsyncTaskStatus.Failed, e.getMessage()));
		}
	}

	@Override
	public void send(ICommand command) {
		sendQueueMessageService.sendMessage(producer, buildCommandMessage(command), commandRouteKeyProvider.getRoutingKey(command));
	}

	@Override
	public CommandResult execute(ICommand command, int timeoutMillis) {
		return execute(command, CommandReturnType.CommandExecuted, timeoutMillis);
	}

	@Override
	public CommandResult execute(ICommand command, CommandReturnType commandReturnType, int timeoutMillis) {
		Future<AsyncTaskResult<CommandResult>> executeAsync = executeAsync(command, commandReturnType);
		AsyncTaskResult<CommandResult> asyncTaskResult;
		try {
			asyncTaskResult = executeAsync.get(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			logger.error("Command execute timeout! [commandId={}, aggregateRootId={}]", command.getId(), command.getAggregateRootId());
			throw new CommandExecuteTimeoutException(e);
		}
		return asyncTaskResult.getData();
	}

	@Override
	public Future<AsyncTaskResult<CommandResult>> executeAsync(ICommand command) {
		return executeAsync(command, CommandReturnType.CommandExecuted);
	}

	/**
	 * 此方法实现起来比较绕，援引自C# ENode.EQueue.CommandService.executeAsync<br>
	 * Java中没有c# 的 async/await 调用，只能用最原始的创建线程任务对象的方法。
	 */
	@Override
	public Future<AsyncTaskResult<CommandResult>> executeAsync(ICommand command, CommandReturnType commandReturnType) {
		return asyncPool.execute(
				new IAsyncTask<AsyncTaskResult<CommandResult>>() {
					private ICommand command;
					private CommandReturnType commandReturnType;

					public IAsyncTask<AsyncTaskResult<CommandResult>> init(ICommand command, CommandReturnType commandReturnType){
						this.command = command;
						this.commandReturnType = commandReturnType;
						return this;
					}
					
					@Override
					public AsyncTaskResult<CommandResult> call() throws Exception {
						try {
							Ensure.notNull(commandResultProcessor, "commandResultProcessor");
							FutureTaskCompletionSource<AsyncTaskResult<CommandResult>> taskCompletionSource = new FutureTaskCompletionSource<AsyncTaskResult<CommandResult>>();
							commandResultProcessor.regiesterProcessingCommand(command, commandReturnType, taskCompletionSource);
							
							BaseAsyncTaskResult result = sendQueueMessageService.sendMessageAsync(producer, buildCommandMessage(command, true), commandRouteKeyProvider.getRoutingKey(command)).get();
							if(AsyncTaskStatus.Success == result.getStatus())
								return taskCompletionSource.task.get();
							commandResultProcessor.processFailedSendingCommand(command);
							return new AsyncTaskResult<CommandResult>(result.getStatus(), result.getErrorMessage());
						} catch ( Exception e ) {
							return new AsyncTaskResult<CommandResult>(AsyncTaskStatus.Failed, e.getMessage());
						}
					}
					
				}.init(command, commandReturnType)
		);
	}

	private Message buildCommandMessage(ICommand command){
		return buildCommandMessage(command, false);
	}

	private Message buildCommandMessage(ICommand command, boolean needReply){
		Ensure.notNull(command.getAggregateRootId(), "aggregateRootId");
        String commandData = jsonConverter.convert(command);
        String topic = commandTopicProvider.getTopic(command);
        String replyAddress = ""; //needReply && _commandResultProcessor != null ? _commandResultProcessor.BindingAddress.ToString() : null;
        String messageData = jsonConverter.convert(new CommandMessage(commandData, replyAddress));
        return new Message(
            topic,
            QueueMessageTypeCode.CommandMessage.ordinal(),
            messageData.getBytes(Charset.forName("UTF-8")),
            command.getClass().getName());
	}

}
