package pro.jiefzz.ejoker.commanding;

import java.util.concurrent.Future;

public interface IProcessingCommandHandler {

	public Future<Void> handleAsync(ProcessingCommand processingCommand);
	
}
