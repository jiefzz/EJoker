package com.jiefzz.ejoker.infrastructure.varieties.publishableExceptionMessage.impl;

import com.jiefzz.ejoker.infrastructure.impl.DefaultProcessingMessageHandlerAbstract;
import com.jiefzz.ejoker.infrastructure.varieties.applicationMessage.IApplicationMessage;
import com.jiefzz.ejoker.infrastructure.varieties.applicationMessage.ProcessingApplicationMessage;
import com.jiefzz.ejoker.z.common.context.annotation.context.EService;

/**
 * 
 * * 编写本类的目的是为了
 * * 在上下文中不使用泛型注入，而是用推演
 * @author kimffy
 *
 */
@EService
public class DefaultProcessingPublishableExceptionMessageHandler extends DefaultProcessingMessageHandlerAbstract<ProcessingApplicationMessage, IApplicationMessage> {

}
