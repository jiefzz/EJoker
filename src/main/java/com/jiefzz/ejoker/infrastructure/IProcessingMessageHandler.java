package com.jiefzz.ejoker.infrastructure;

public interface IProcessingMessageHandler<X extends IProcessingMessage<X, Y>, Y extends IMessage> {

	void handleAsync(X processingMessage);
	
}
