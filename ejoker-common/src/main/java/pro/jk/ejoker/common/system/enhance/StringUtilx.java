package pro.jk.ejoker.common.system.enhance;

import java.io.UnsupportedEncodingException;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import pro.jk.ejoker.common.system.component.ControlTable;
import pro.jk.ejoker.common.system.constant.ConfigKeyPrimary;

public final class StringUtilx {

	public static boolean isNullOrEmpty(String targetString) {
		return null==targetString || "".equals(targetString);
	}
	
	public static boolean notEmpty(String targetString) {
		return null!=targetString && !"".equals(targetString);
	}
	
	public static boolean isNullOrWhiteSpace(String targetString) {
		return null==targetString || "".equals(targetString.trim());
	}
	
	public static boolean isSenseful(String targetString) {
		return null!=targetString && !"".equals(targetString.trim());
	}
	
	public static byte[] getBytes(String data, String charsetName) {
		try {
			return data.getBytes(charsetName);
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * 实现类似 slf4j 的占位填充功能。用以替换 String.format <br />
	 * <br />
	 * <b>【强制约束】占位符只支持空花括号「{}」，即花括号内不得包含任何字面字符。</b><br />
	 * <br />
	 * * 本方法<b>不处理</b>花括号内写了字面字符的占位符（如 {x}、{name}），此类模板不在支持范围内——<br />
	 * * 实参充足时，此类占位符的内层字符会被静默丢弃（占位符替换语义使然，如 fmt("a{x}b", "X") 得到 "aXb"）；<br />
	 * * 实参数量不足时，从第一个未消费的占位符起，模板剩余部分<b>按原样输出</b>（含花括号内字符）。<br />
	 * <br />
	 * * 正确用法：fmt("a={}, b={}", arg1, arg2)<br />
	 * * 错误用法：fmt("a={x}, b={name}", ...)<br />
	 * * 模板中需要输出字面 '{' / '}' 时，请使用反斜杠转义：\{ \}<br />
	 * <br />
	 * @param template
	 * @param args
	 * @return
	 */
	public static String fmt(String template, Object... args) {
		if(null == template || "".equals(template))
			return "";
		
		MarkTuple analyzeOccupation = analyzeOccupation(template);
		
		if(null == args)
			args = emptyArgs;
		char[] chars = template.toCharArray();
		
		int esIndex = -1;
		int[] escapes = analyzeOccupation.escapes;
		if(null != escapes && 0 < escapes.length)
			esIndex = 0;

		int[] occupation = analyzeOccupation.marks;
		StringBuilder sb = new StringBuilder();
		int lx = -2;
		int ly = -1;
		int i = 0;
		do {
			lx = occupation[2*i];
			if(esIndex>=0) {
				int latestEsPoint = escapes[esIndex];
				if(latestEsPoint >= ly+1 && latestEsPoint < lx) {
					int startAt = ly+1;
					do {
						sb.append(chars, startAt, latestEsPoint-startAt);
						startAt = latestEsPoint+1;
					} while((latestEsPoint = escapes[++esIndex]) >= ly+1 && escapes[esIndex] < lx);
					sb.append(chars, startAt, lx-startAt);
				} else {
					sb.append(chars, ly+1, lx-(ly+1));
				}
				if(latestEsPoint<0)
					esIndex = -1;
			} else
				sb.append(chars, ly+1, lx-(ly+1));
			// if ll +1 != lr ??
			// TODO 有特殊功能要实现不？
			ly = occupation[2*i+1];
			if(ly < 0)
				break;
			if(i < args.length && null != args[i])
				sb.append(args[i]);
			else if(i >= args.length) {
				// 实参不足：从未消费的占位符开头（lx，即本轮 '{' 的位置）起，模板剩余部分按原样输出
				sb.append(chars, lx, chars.length-lx);
			}
		} while (i++ < args.length);
		return sb.toString();
	}
	
	//{}
	// "This is a value of {}, name is {}, review."
	//  0....5....0....5....0....5....0....5....0....5....0
	//           10        20        30        40        50 
	
	private final static Map<String, MarkTuple> CacheMarks = new ConcurrentHashMap<>();
	
	private final static int appendSize = 16;
	
	private final static Object[] emptyArgs = new Object[] {null};
	
	private final static MarkTuple analyzeOccupation(String template) {
		MarkTuple analyzeOccupation = MapUtilx.getOrAdd(CacheMarks, template, () -> {
			char[] chars = template.toCharArray();
			char latestSymbol = (char )0;
			int[] marks = new int[appendSize];
			int[] escapes = null;
			int index = 0;
			int indexEscape = 0;
			for(int i=0; i<chars.length; i++) {
				char currC = chars[i];
				if('{' == currC || '}' == currC) {
					if(i>0 && '\\' == chars[i-1]) {
						if(null == escapes)
							escapes = new int[appendSize];
						else if(indexEscape == escapes.length)
							escapes = generalNewMarks(escapes, appendSize); // 扩容
						escapes[indexEscape++] = i-1;
						continue;
					}
					if(latestSymbol == currC)
						throw new RuntimeException("Unexpect string template!!! index: " + i + ", template: "+ template);
					latestSymbol = currC;
					marks[index++] = i;
					if(0 == index%appendSize)
						marks = generalNewMarks(marks, appendSize); // 扩容
				}
			}
			if(0 != index%2)
				throw new RuntimeException("Unexpect string template!!! unmatch '{' and '}', template: "+ template);
			if(index == marks.length) {
				marks = generalNewMarks(marks, 2); // 扩容
			}
			marks[index++] = chars.length;
			for(int j = index; j<marks.length; j++)
				marks[j] = -1;
			
			if(null != escapes) {
				for(int k = indexEscape; k<escapes.length; k++)
					escapes[k] = -1;
				return new MarkTuple(marks, escapes);
			}
			clean();
			return new MarkTuple(marks);
		});
		analyzeOccupation.counter.incrementAndGet();
		return analyzeOccupation;
	}
	
	private final static int[] generalNewMarks(int[] prevous, int appendSize) {
		int[] marksNew = new int[prevous.length + appendSize];
		System.arraycopy(prevous, 0, marksNew, 0, prevous.length);
		return marksNew;
	}

	private final static AtomicInteger ExecCounter = new AtomicInteger(0);
	
	private final static class MarkTuple {
		
		private AtomicInteger counter = new AtomicInteger(0);
		
		private int[] marks;
		
		private int[] escapes;
		
		public MarkTuple(int[] marks, int[] escapes) {
			this.marks = marks;
			this.escapes = escapes;
		}
		
		public MarkTuple(int[] marks) {
			this.marks = marks;
			this.escapes = null;
		}
	}
	
	private final static void clean() {
		int hit = ExecCounter.incrementAndGet();
		if(hit % CleanInterval == 0) {
			OptionalDouble average = CacheMarks.values().parallelStream().mapToInt(v -> v.counter.get()).average();
			if(average.isPresent()) {
				double avg = average.getAsDouble();
				double fate = avg >= 5 ? CleanThresholdTab[5] : CleanThresholdTab[(int )avg];
				double limit = avg * fate;
				Iterator<Entry<String, MarkTuple>> iterator = CacheMarks.entrySet().iterator();
				while(iterator.hasNext()) {
					Entry<String, MarkTuple> current = iterator.next();
					AtomicInteger c = current.getValue().counter;
					int tHit = c.get();
					if(tHit <= limit)
						iterator.remove();
					else
						c.set(tHit/2);
				}
			}
		}
	}
	
	private final static int CleanInterval;
	
	// 0号元素是用不上的，但是需要占位；
	private final static double[] CleanThresholdTab = {1, 4, 3, 2, 1.05, 0.95};
	
	static {
		CleanInterval = ControlTable.detectAsInteger(
				ConfigKeyPrimary.EJOKER_FMT_CLEAN_INTERVAL);
	}
}
