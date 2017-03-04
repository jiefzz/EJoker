package com.jiefzz.ejoker.utils.relationship;

public interface RelationshipTreeUtilCallbackInterface<KVP, VP> {
	
	/**
	 * 创建一个键值集容器
	 * @return
	 * @throws Exception
	 */
	public KVP createNode();
	
	/**
	 * 创建一个值集容器
	 * @return
	 * @throws Exception
	 */
	public VP createValueSet();
	
	/**
	 * 判断容器中是否存在该键对应的值，仅仅对键值集有效
	 * @param targetNode
	 * @param key
	 * @return
	 * @throws Exception
	 */
	public boolean isHas(KVP targetNode, Object key);
	
	/**
	 * 添加元素到值集的方法
	 * @param valueSet
	 * @param child
	 * @throws Exception
	 */
	public void addToValueSet(VP valueSet, Object child);
	
	/**
	 * 添加元素到键值集的方法
	 * @param keyValueSet
	 * @param child
	 * @param key
	 * @throws Exception
	 */
	public void addToKeyValueSet(KVP keyValueSet, Object child, String key);
	
//	/**
//	 * 合并两个键值集容器的方法，仅仅对键值集有效
//	 * @param targetNode
//	 * @param tempNode
//	 * @throws Exception
//	 */
//	public void merge(KVP targetNode, KVP tempNode);
//	
//	/**
//	 * 获取某个键值对中的值，仅仅对键值集有效
//	 * @param key
//	 * @return
//	 * @throws Exception
//	 */
//	public Object getOne(KVP targetNode, String key);
}
