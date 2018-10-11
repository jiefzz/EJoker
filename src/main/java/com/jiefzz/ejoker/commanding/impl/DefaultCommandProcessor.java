package com.jiefzz.ejoker.commanding.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jiefzz.ejoker.EJokerEnvironment;
import com.jiefzz.ejoker.commanding.ICommandProcessor;
import com.jiefzz.ejoker.commanding.IProcessingCommandHandler;
import com.jiefzz.ejoker.commanding.ProcessingCommand;
import com.jiefzz.ejoker.commanding.ProcessingCommandMailbox;
import com.jiefzz.ejoker.z.common.ArgumentException;
import com.jiefzz.ejoker.z.common.context.annotation.context.Dependence;
import com.jiefzz.ejoker.z.common.context.annotation.context.EInitialize;
import com.jiefzz.ejoker.z.common.context.annotation.context.EService;
import com.jiefzz.ejoker.z.common.schedule.IScheduleService;
import com.jiefzz.ejoker.z.common.system.helper.MapHelper;
import com.jiefzz.ejoker.z.common.task.context.EJokerAsyncHelper;
import com.jiefzz.ejoker.z.common.task.context.EJokerReactThreadScheduler;
import com.jiefzz.ejoker.z.common.utils.ForEachUtil;

/**
 * 默认的命令处理类<br>
 * @author jiefzz
 *
 */
@EService
public final class DefaultCommandProcessor implements ICommandProcessor {

	private final static Logger logger = LoggerFactory.getLogger(DefaultCommandProcessor.class);
	
	private final Map<String, ProcessingCommandMailbox> mailboxDict = new ConcurrentHashMap<>();
	
	@Dependence
    private IProcessingCommandHandler handler;

	@Dependence
	private IScheduleService scheduleService;

	@Dependence
	private EJokerReactThreadScheduler eJokerReactThreadScheduler;

	@Dependence
	private EJokerAsyncHelper eJokerAsyncHelper;

	@EInitialize
	private void init() {
		scheduleService.startTask(
				String.format("%s@%d#%s", this.getClass().getName(), this.hashCode(), "cleanInactiveMailbox()"),
				this::cleanInactiveMailbox,
				EJokerEnvironment.MAILBOX_IDLE_TIMEOUT,
				EJokerEnvironment.MAILBOX_IDLE_TIMEOUT);
	}
	
	// TODO debug
	public void d1() {
		ForEachUtil.processForEach(mailboxDict, (k, v) -> {
			if(null == v) {
				logger.debug("r: {}, v=null!!!", k);
				return;
			}
			v.d1();
		});
	}
	
	@Override
	public void process(ProcessingCommand processingCommand) {
		
		String aggregateRootId = processingCommand.getMessage().getAggregateRootId();
        if (aggregateRootId==null || "".equals(aggregateRootId))
            throw new ArgumentException("aggregateRootId of command cannot be null or empty, commandId:" + processingCommand.getMessage().getId());

        ProcessingCommandMailbox mailbox = MapHelper.getOrAddConcurrent(mailboxDict, aggregateRootId, () -> new ProcessingCommandMailbox(aggregateRootId, handler, eJokerAsyncHelper));
        mailbox.enqueueMessage(processingCommand);
	}

	/**
	 * clean long time idle mailbox
	 * 清理超时mailbox的函数。<br>
	 */
	private void cleanInactiveMailbox() {
		List<String> idelMailboxKeyList = new ArrayList<>();
		ForEachUtil.processForEach(mailboxDict, (aggregateId, mailbox) -> {
			if(!mailbox.onRunning() && mailbox.isInactive(EJokerEnvironment.MAILBOX_IDLE_TIMEOUT))
				idelMailboxKeyList.add(aggregateId);
		});
		
		for(String mailboxKey:idelMailboxKeyList) {
			ProcessingCommandMailbox processingCommandMailbox = mailboxDict.get(mailboxKey);
			if(!processingCommandMailbox.isInactive(EJokerEnvironment.MAILBOX_IDLE_TIMEOUT)) {
				// 在上面判断其达到空闲条件后，又被重新使能（临界情况）
				// 放弃本次操作
				continue;
			}
			mailboxDict.remove(mailboxKey);
			logger.debug("Removed inactive command mailbox, aggregateRootId: {}", mailboxKey);
		}
	}
}