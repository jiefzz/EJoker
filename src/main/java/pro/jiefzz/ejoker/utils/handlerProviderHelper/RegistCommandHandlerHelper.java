package pro.jiefzz.ejoker.utils.handlerProviderHelper;

import pro.jiefzz.ejoker.commanding.AbstractCommandHandler;
import pro.jiefzz.ejoker.utils.handlerProviderHelper.containers.CommandHandlerPool;
import pro.jiefzz.ejoker.z.context.annotation.assemblies.CommandHandler;
import pro.jiefzz.ejoker.z.context.dev2.IEjokerContextDev2;

public final class RegistCommandHandlerHelper {

	static public void checkAndRegistCommandHandler(Class<?> clazz, CommandHandlerPool commandHandlerPool ,IEjokerContextDev2 ejokerContext) {
		
		if(clazz.isAnnotationPresent(CommandHandler.class)) {
			commandHandlerPool.regist((Class<? extends AbstractCommandHandler> )clazz, () -> ejokerContext);
		}
		
	}
	
}
