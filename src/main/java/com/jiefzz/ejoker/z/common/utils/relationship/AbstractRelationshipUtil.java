package com.jiefzz.ejoker.z.common.utils.relationship;

import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import com.jiefzz.ejoker.z.common.system.functional.IVoidFunction;

public abstract class AbstractRelationshipUtil {

	protected ThreadLocal<Queue<IVoidFunction>> taskQueueBox = ThreadLocal.withInitial(() -> new LinkedBlockingQueue<>());

	protected final SpecialTypeCodecStore<?> specialTypeCodecStore;
	
	protected AbstractRelationshipUtil(SpecialTypeCodecStore<?> specialTypeCodecStore) {
		this.specialTypeCodecStore = specialTypeCodecStore;
	}
	
	protected Object processWithUserSpecialCodec(Object value, Class<?> fieldType) {
		if(null == specialTypeCodecStore)
			return null;
		
		SpecialTypeCodec fieldTypeCodec = specialTypeCodecStore.getCodec(fieldType);
		if(null == fieldTypeCodec)
			return null;
		
		/// 完全类型对等
		if(fieldType.equals(value.getClass()))
			return fieldTypeCodec.encode(value);
		
		return null;
	}
	
	protected SpecialTypeCodec getDeserializeCodec(Class<?> fieldType) {
		if(null == specialTypeCodecStore)
			return null;
		
		return specialTypeCodecStore.getCodec(fieldType);
	}
	
}
