package com.jiefzz.ejoker.z.common.utilities;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.jiefzz.ejoker.z.common.system.functional.IFunction;
import com.jiefzz.ejoker.z.common.system.functional.IVoidFunction1;
import com.jiefzz.ejoker.z.common.system.functional.IVoidFunction2;

public class GenericExpression {

	/**
	 * 表达的签名
	 */
	public final String expressSignature;
	
	/**
	 * 表达的元泛型数据
	 */
	public final GenericDefination genericDefination;
	
	/**
	 * 导出表<br>
	 * key 为泛型变量的名字
	 */
	protected final Map<String, GenericExpressionExportTuple> materializedMapper;
	
	/**
	 * 当前表达的下级表达
	 */
	private final GenericExpression child;

	/**
	 * 当前表达的上级表达
	 */
	private final GenericExpression parent;

	/**
	 * 当前表达的下向扩展表达
	 */
	private final GenericExpression implementer;

	/**
	 * 当前表达的上向约束表达
	 */
	private final GenericExpression[] implementationsExpressions;
	
	/**
	 * 完全态指示指标
	 */
	private final boolean isComplete;

	private final Map<String, GenericDefinedField> fieldExpressions;
	
	protected GenericExpression(GenericDefination meta) {
		this(meta, null);
	}
	
	/**
	 * 创建半完全态表达 剔除最终态的类的泛型<br>
	 * 当然，如果最终态类没有泛型，则此表达就是完全态的。
	 * @param genericDefination
	 * @param lowerGenericExpression
	 */
	protected GenericExpression(GenericDefination genericDefination, GenericExpression lowerGenericExpression) {
		int genericDeclareAmount = genericDefination.getGenericDeclareAmount();
		int interfacesAmount = genericDefination.getInterfacesAmount();
		boolean isInterface = genericDefination.isInterface;
		this.genericDefination = genericDefination;
		this.implementationsExpressions = 0 == interfacesAmount ? null : new GenericExpression[interfacesAmount];
		this.materializedMapper = /* 0 == genericDeclareAmount ? null : */ new HashMap<>();
		
		if(isInterface) {
			// 接口 与 扩展 的关系
			this.child = null;
			this.implementer = lowerGenericExpression;
		} else {
			// 抽象 与 派生 的关系
			this.child = lowerGenericExpression;
			this.implementer = null;
		}
		
		final AtomicInteger hasGenericityPassing = new AtomicInteger(0);

		/// 获取下级表达
		if(null != lowerGenericExpression ) {
			GenericDefination lowerGe = lowerGenericExpression.genericDefination;
			
			/// 获取下级表达传递到此的dct以及dm
			GenericDefinedTypeMeta[] targetDct = isInterface ?
				lowerGe.getInterfaceDeliveryTypeMetasTableCopy(genericDefination.genericPrototypeClazz) :
				lowerGe.getDeliveryTypeMetasTableCopy();
			Map<String, String> targetDm = isInterface ?
				lowerGe.getInterfaceDeliveryMapperCopy(genericDefination.genericPrototypeClazz) :
				lowerGe.getDeliveryMapperCopy();
				
			/// 如果dct中对应的位置存在类型的值，则添加一个导出记录，里面包含 泛型变量名 -> 传递来的类型
			genericDefination.forEachGenericDeclares((genericDeclare, i) -> {
				GenericDefinedTypeMeta passTypeMeta = targetDct[i];
				GenericDefinedTypeMeta currentTypeMeta;
				if(null == passTypeMeta) {
					/// 下方表达中的genericDefination传来就是空值，有可能是隔代基础/扩展的情况，进一步排除
					
					String referenceGenericityName = targetDm.get(genericDeclare.name);
					GenericExpressionExportTuple genericExpressionExportTuple = lowerGenericExpression.materializedMapper.get(referenceGenericityName);
					if(null == genericExpressionExportTuple) {
						/// 声明泛型表中也没有传递具现化类型过来，则确认是不完全态的表达
						hasGenericityPassing.incrementAndGet();
						return;
					} else {
						currentTypeMeta = new GenericDefinedTypeMeta(genericExpressionExportTuple.declarationTypeMeta, lowerGenericExpression.materializedMapper);
					}
				} else {
					/// 为啥要创建一个新的GenericDefinedTypeMeta？
					/// 因为定义中传递过来的还有可能是带有泛型的声明的具现化类类型
					/// 如Map<K, V>这种，这样做的目的的继续具现化类。
					currentTypeMeta = new GenericDefinedTypeMeta(passTypeMeta, lowerGenericExpression.materializedMapper);
				}

				materializedMapper.put(
						genericDeclare.name,
						new GenericExpressionExportTuple(
								genericDeclare,
								currentTypeMeta
						)
				);
				
				if(!currentTypeMeta.allHasMaterialized)
					hasGenericityPassing.incrementAndGet();
			});
		} else {
			hasGenericityPassing.addAndGet(genericDeclareAmount);
		}

		/**
		 * 判定完全形态<br>
		 * 具现化过程中没有再需要从下级表达中传递过来泛型过来，则为完全形态
		 */
		isComplete = hasGenericityPassing.get() == 0;

		{
			final StringBuilder sbGenericTypeSignature = new StringBuilder();
			genericDefination.forEachGenericDeclares(genericDeclare -> {
				GenericExpressionExportTuple genericExpressionExportTuple = materializedMapper.get(genericDeclare.name);
				if(null != genericExpressionExportTuple) {
					sbGenericTypeSignature.append(genericExpressionExportTuple.declarationTypeMeta.typeName);
				} else
					sbGenericTypeSignature.append(genericDeclare.name);
				sbGenericTypeSignature.append(GenericTypeUtil.SEPARATOR);
			});
			String genericTypeSignature = sbGenericTypeSignature.toString().replaceFirst(GenericTypeUtil.SEPARATOR+"$", "");
			StringBuilder sb = new StringBuilder();
			sb.append(genericDefination.genericPrototypeClazz.getName());
			if(null != genericTypeSignature && !"".equals(genericTypeSignature)) {
				sb.append('<');
				sb.append(genericTypeSignature);
				sb.append('>');
			}
			this.expressSignature = sb.toString();
		}
		
		GenericDefination superDefination;
		if(null != (superDefination  = genericDefination.getSuperDefination())) {
			this.parent = new GenericExpression(superDefination, this);
		} else {
			this.parent = null;
		}

		final AtomicInteger cursor = new AtomicInteger(0);
		genericDefination.forEachInterfaceDefinations((interfaceClazz, ifaceDefination) -> {
			this.implementationsExpressions[cursor.getAndIncrement()] = new GenericExpression(ifaceDefination, this);
		});

		{
			// 补全field信息
			if(genericDefination.isInterface) {
				fieldExpressions = null;
			} else {
				fieldExpressions = new HashMap<>();
				genericDefination.forEachFieldDefinations((fieldName, genericDefinedField) -> {
					if(genericDefinedField.isGenericVariable) {
						GenericExpressionExportTuple genericExpressionExportTuple = materializedMapper.get(genericDefinedField.genericTypeVariableName);
						if(null == genericExpressionExportTuple) {
//							if(isComplete) {
//								throw new RuntimeException("Fuck!!! This statement should not be happen!!!");
//							} else {
								fieldExpressions.put(fieldName, new GenericDefinedField(genericDefinedField.genericDefination, genericDefinedField.field));
//								return;
//							}
						} else {
							fieldExpressions.put(fieldName, new GenericDefinedField(genericDefinedField, new GenericDefinedTypeMeta(genericExpressionExportTuple.declarationTypeMeta, lowerGenericExpression.materializedMapper)));
						}
					} else {
						fieldExpressions.put(fieldName, genericDefinedField);
					}
				});
			}
		}
		
	}
	
	/**
	 * 填入泛型目标 并 自动步进的 复制构造方法
	 * @param target 需要复制的目标
	 * @param lowerGenericExpression 下级表达 （ 或是继承类的表达 或是 接口扩展的表达 ）
	 * @param definedTypeMetas 泛型实例化列表
	 */
	protected GenericExpression(GenericExpression target, GenericExpression lowerGenericExpression, final GenericDefinedTypeMeta... definedTypeMetas) {
		int genericTypeAmount = target.genericDefination.getGenericDeclareAmount();
		if(genericTypeAmount > 0) {
			if(null == definedTypeMetas || genericTypeAmount != definedTypeMetas.length) {
				String errInfo = String.format("Unmatch amount of parameterized type!!! target=%s",
						target.genericDefination.genericPrototypeClazz.getName());
				throw new RuntimeException(errInfo);
			}
			
		}
		
		this.genericDefination = target.genericDefination;
		// 在完全态下的复制构造过程中 即便有泛型类型参数表，但也可能在meta中提供了参数表，而不是从参数中传入
		this.expressSignature = getExpressionSignature(target.genericDefination.getGenericPrototypeClazz(), definedTypeMetas);
		boolean isInterface = genericDefination.isInterface;
		int interfacesAmount = genericDefination.getInterfacesAmount();
//		this.materializedMapper = 0 == genericTypeAmount ? null : new HashMap<>(target.materializedMapper);
		this.materializedMapper = /* 0 == genericDeclareAmount ? null : */ new HashMap<>();
		if(!target.isComplete && (null == definedTypeMetas || definedTypeMetas.length != genericTypeAmount))
			throw new RuntimeException();
		this.isComplete = true;
		
		if(isInterface) {
			this.implementer = lowerGenericExpression;
			this.child = null;
		} else {
			this.implementer = null;
			this.child = lowerGenericExpression;
		}
		
		genericDefination.forEachGenericDeclares((genericDeclare, i) -> {
			materializedMapper.put(genericDeclare.name,
					new GenericExpressionExportTuple(genericDeclare, definedTypeMetas[i]));
		});
		
		if(null != target.parent) {
			GenericExpression upperGe = target.parent;
			GenericDefinedTypeMeta[] deliveryClassesTable = getDCT(
					() -> genericDefination.getDeliveryTypeMetasTableCopy(),
					() -> genericDefination.getDeliveryMapperCopy());
			this.parent = new GenericExpression(upperGe, this, deliveryClassesTable);
		} else 
			this.parent = null;

		if(0 != interfacesAmount) {
			this.implementationsExpressions = new GenericExpression[interfacesAmount];
			for(int i = 0; i<target.implementationsExpressions.length; i++) {
				
				GenericExpression upperGe = target.implementationsExpressions[i];
				GenericDefinedTypeMeta[] deliveryClassesTable = getDCT(
						() -> genericDefination.getInterfaceDeliveryTypeMetasTableCopy(upperGe.genericDefination.genericPrototypeClazz),
						() -> genericDefination.getInterfaceDeliveryMapperCopy(upperGe.genericDefination.genericPrototypeClazz));
				
				this.implementationsExpressions[i] = new GenericExpression(upperGe, this, deliveryClassesTable);
			}
		} else {
			this.implementationsExpressions = null;
		}

		{
			// 补全field信息
			if(genericDefination.isInterface) {
				fieldExpressions = null;
			} else {
				fieldExpressions = new HashMap<>();
				ForEachUtil.processForEach(target.fieldExpressions, (fieldName, genericDefinedField) -> {
					GenericDefinedTypeMeta currentGenericDefinedTypeMeta;
					if(genericDefinedField.isGenericVariable) {
						/// 如果是泛型类型变量，则从 exportMapper 泛型导出表中获取对应具现化类型
						GenericExpressionExportTuple genericExpressionExportTuple = materializedMapper.get(genericDefinedField.genericTypeVariableName);
						currentGenericDefinedTypeMeta = genericExpressionExportTuple.declarationTypeMeta;
					} else {
						/// 如果是普通类型变量，则分情况处理
						GenericDefinedTypeMeta originalGenericDefinedTypeMeta = genericDefinedField.genericDefinedTypeMeta;
//						if(originalGenericDefinedTypeMeta.hasGenericDeclare) {
//							/ 声明中带有泛型
//							currentGenericDefinedTypeMeta = new GenericDefinedTypeMeta(originalGenericDefinedTypeMeta, materializedMapper);
//							fillAndCompleteGenericDefinedTypeMeta(
//									genericDefinedField,
//									materializedMapper.keySet(),
//									currentGenericDefinedTypeMeta.deliveryTypeMetasTable,
//									currentGenericDefinedTypeMeta.boundsUpper,
//									currentGenericDefinedTypeMeta.boundsLower);
//						} else {
							/// 声明中没有泛型, 则直接复制构造一个GenericDefinedTypeMeta
//							if(originalGenericDefinedTypeMeta.isWildcardType) {
//								throw new RuntimeException("Fuck!!! This statement should not be happen!!!");
//							}
							currentGenericDefinedTypeMeta = new GenericDefinedTypeMeta(originalGenericDefinedTypeMeta, materializedMapper);
//						}
						
					}
					fieldExpressions.put(
							fieldName,
							new GenericDefinedField(genericDefinedField, currentGenericDefinedTypeMeta));
				});
			}
		}
		
	}
	
	public Class<?> getDeclarePrototype() {
		return genericDefination.genericPrototypeClazz;
	}
	
	public int getExportAmount() {
		return null == materializedMapper ? 0 : materializedMapper.size();
	}

	public int getInterfacesAmount() {
		return genericDefination.getInterfacesAmount();
	}
	
	public boolean isComplete() {
		return isComplete;
	}
	
	public GenericExpression getChild() {
		return child;
	}
	
	public GenericExpression getParent() {
		return parent;
	}
	
	public GenericExpression getImplementer() {
		return implementer;
	}
	
	public void forEachImplementationsExpressions(IVoidFunction1<GenericExpression> vf) {
		ForEachUtil.processForEach(implementationsExpressions, vf);
	}
	
	public void forEachImplementationsExpressionsDeeply(IVoidFunction1<GenericExpression> vf) {
		ForEachUtil.processForEach(implementationsExpressions, genericExpression -> {
			vf.trigger(genericExpression);
			genericExpression.forEachImplementationsExpressionsDeeply(vf);
		});
	}
	
	public void forEachFieldExpressions(IVoidFunction2<String, GenericDefinedField> vf) {
		ForEachUtil.processForEach(fieldExpressions, vf);
		
	}
	
	private GenericDefinedTypeMeta[] getDCT(IFunction<GenericDefinedTypeMeta[]> originalGenericDefinationDCTGetter,
			IFunction<Map<String, String>> originalGenericDefinationDMGetter) {
		GenericDefinedTypeMeta[] geDeliveryTypeMetasTable = originalGenericDefinationDCTGetter.trigger();
		if (null == geDeliveryTypeMetasTable)
			return geDeliveryTypeMetasTable;
		Map<String, String> geDeliveryMapper = originalGenericDefinationDMGetter.trigger();
		for (int j = 0; j < geDeliveryTypeMetasTable.length; j++) {
			if (null != geDeliveryTypeMetasTable[j]) {
				geDeliveryTypeMetasTable[j] = new GenericDefinedTypeMeta(geDeliveryTypeMetasTable[j], materializedMapper);
				continue;
			}
			String mapperTypeVariableName = geDeliveryMapper.get("" + j);
			GenericExpressionExportTuple exportTuple = materializedMapper.get(mapperTypeVariableName);
			if (null == exportTuple)
				throw new RuntimeException();
			geDeliveryTypeMetasTable[j] = exportTuple.declarationTypeMeta;
		}
		return geDeliveryTypeMetasTable;
	}
	
	private void fillAndCompleteGenericDefinedTypeMeta(
			final GenericDefinedField genericDefinedField,
			final Set<String> exportTypeVariableNames,
			GenericDefinedTypeMeta[] deliveryTypeMetasTable,
			GenericDefinedTypeMeta[] boundsUpper,
			GenericDefinedTypeMeta[] boundsLower) {
		GenericDefinedTypeMeta[][] group = new GenericDefinedTypeMeta[][] {deliveryTypeMetasTable, boundsUpper, boundsLower};
		
		for(GenericDefinedTypeMeta[] item : group) {
			if(null == item || 0 == item.length)
				continue;
			for(int i = 0; i<item.length; i++) {
				GenericDefinedTypeMeta typeMeta = item[i];
				if(exportTypeVariableNames.contains(typeMeta.typeName)) {
					GenericExpressionExportTuple genericExpressionExportTuple = materializedMapper.get(typeMeta.typeName);
					item[i] = genericExpressionExportTuple.declarationTypeMeta;
				}
				fillAndCompleteGenericDefinedTypeMeta(genericDefinedField, exportTypeVariableNames, typeMeta.deliveryTypeMetasTable, typeMeta.boundsUpper, typeMeta.boundsLower);
			}
		}
	}

	public final static String getExpressionSignature(Class<?> prototype, GenericDefinedTypeMeta... typeMetas) {

		StringBuilder sb = new StringBuilder();
		sb.append(prototype.getName());
		if(null!=typeMetas && 0 != typeMetas.length) {
			sb.append('<');
			for(GenericDefinedTypeMeta typeMeta:typeMetas) {
				sb.append(typeMeta.typeName);
				sb.append(GenericTypeUtil.SEPARATOR);
			}
			sb.append('>');
		}
		return sb.toString().replaceFirst(GenericTypeUtil.SEPARATOR+">$", ">");
		
	}
	
	public final static String getExpressionSignature(Class<?> prototype, Class<?>... classes) {

		StringBuilder sb = new StringBuilder();
		sb.append(prototype.getName());
		if(null!=classes && 0 != classes.length) {
			sb.append('<');
			for(Class<?> clazz:classes) {
				sb.append(clazz.getName());
				sb.append(GenericTypeUtil.SEPARATOR);
			}
			sb.append('>');
		}
		return sb.toString().replaceFirst(GenericTypeUtil.SEPARATOR+">$", ">");
		
	}
	
	public final static String getExpressionSignature(Class<?> prototype) {
		return prototype.getName();
	}

}
