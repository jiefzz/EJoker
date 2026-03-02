package pro.jk.ejoker.common.context.dev2;

/**
 * 每次扫入一个类时，都会回调这个方法，当需要找某个注解标记的类或有某个接口的类是，就能用上
 * <br />
 * 扫描阶段的处理，在类扫描阶段接入，IoC构建阶段，明确是有啥特殊需求的，才会用，其他择忽略
 */
public interface IEjokerClazzScannerHook {

	void process(Class<?> clazz);
	
}
