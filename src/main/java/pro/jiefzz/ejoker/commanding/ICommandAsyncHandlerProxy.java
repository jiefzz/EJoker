package pro.jiefzz.ejoker.commanding;

import java.util.concurrent.Future;

import pro.jiefzz.ejoker.infrastructure.IObjectProxy;

public interface ICommandAsyncHandlerProxy extends IObjectProxy {
	
	default public Future<Void> handleAsync(ICommandContext context, ICommand command) {
		throw new RuntimeException("Unimplemented!!!");
	};

	default public Future<Void> handleAsync(ICommand command) {
		throw new RuntimeException("Unimplemented!!!");
	};
}
