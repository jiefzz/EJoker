package pro.jk.ejoker.common.system.enhance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 锁定 {@link StringUtilx#fmt(String, Object...)} 的行为契约。
 * <br /><br />
 * 依据 fmt 的 javadoc【强制约束】：占位符只支持空花括号「{}」，花括号内写了字面字符的
 * 占位符（如 {x}、{name}）不在支持范围内。
 * <br /><br />
 * 本类分四组：
 * <ol>
 * <li>规范用法的期望行为（模板/实参各种形态）；</li>
 * <li>转义（\{ \}）的剥除与配对豁免行为；</li>
 * <li>解析期 fail-fast 异常（连续同类符号、奇偶不配）；</li>
 * <li><b>非规范用法（非空占位符）的行为锁定</b>——实参充足时内层字符被静默丢弃（占位符替换语义），
 *     实参不足时从 '{' 起按原样输出（缺参分支修复后的回归守护，修复内容：起点 ly-1 改为 lx）；
 *     null 实参数组的「占位符整体消失」是另一条独立路径的语义快照，不在该修复范围内。</li>
 * </ol>
 * 注：内部模板缓存 {@code CacheMarks} 的 clean 逐出机制（EJOKER_FMT_CLEAN_INTERVAL，
 * 默认 256 次触发）属性能优化而非行为契约，不在断言范围内，仅覆盖缓存结果稳定性与
 * 异常模板不毒化缓存两点。
 *
 * @author JiefzzLon
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class StringUtilxFmtTest {

	// ==================================================================
	// 第一组：规范用法的期望行为（占位符一律使用空「{}」）
	// ==================================================================

	/** 空模板与 null 模板都归一为空串。 */
	@Test
	public void nullOrEmptyTemplateYieldsEmpty() {
		assertEquals("", StringUtilx.fmt(null));
		assertEquals("", StringUtilx.fmt(""));
	}

	/** 无占位符的模板原样返回，不消费任何实参。 */
	@Test
	public void templateWithoutPlaceholderIsVerbatim() {
		assertEquals("hello world", StringUtilx.fmt("hello world"));
	}

	/** 单占位符的标准替换。 */
	@Test
	public void singlePlaceholder() {
		assertEquals("aXb", StringUtilx.fmt("a{}b", "X"));
	}

	/** 多占位符按序替换，字面文本完整保留。 */
	@Test
	public void multiplePlaceholders() {
		assertEquals("a, b, c", StringUtilx.fmt("{}, {}, {}", "a", "b", "c"));
		assertEquals("count=3, id=100", StringUtilx.fmt("count={}, id={}", 3, 100L));
	}

	/** 相邻占位符之间无字面文本时不产生多余输出。 */
	@Test
	public void adjacentPlaceholders() {
		assertEquals("ab", StringUtilx.fmt("{}{}", "a", "b"));
	}

	/** 模板整体就是一个占位符。 */
	@Test
	public void placeholderOnlyTemplate() {
		assertEquals("X", StringUtilx.fmt("{}", "X"));
	}

	/** 实参个数多于占位符时，多余实参被忽略（与 slf4j 语义一致）。 */
	@Test
	public void extraArgsAreIgnored() {
		assertEquals("aXb", StringUtilx.fmt("a{}b", "X", "Y", "Z"));
	}

	/** 实参个数少于占位符时，未消费的空占位符及其后模板文本【原样保留】。 */
	@Test
	public void missingArgsKeepsRestOfTemplateVerbatim() {
		assertEquals("aXb{}c", StringUtilx.fmt("a{}b{}c", "X"));
	}

	/** 占位符位置的实参值为 null 时，占位符被移除（不输出 "null"，与 slf4j 不同，属既定语义）。 */
	@Test
	public void nullArgRemovesPlaceholder() {
		assertEquals("ab", StringUtilx.fmt("a{}b", (Object) null));
		assertEquals("ab", StringUtilx.fmt("a{}b", (Object[]) null));
	}

	/** 多个实参中间夹 null：仅该占位符消失，前后占位符与字面文本不受影响。 */
	@Test
	public void midNullArgOnlyVanishesItsOwnPlaceholder() {
		assertEquals("a-", StringUtilx.fmt("{}-{}", "a", null));
	}

	/** 实参为任意对象时按 StringBuilder.append(Object) 的 String.valueOf 语义输出其 toString（record 默认 toString 为 Class[field=value, ...]）。 */
	@Test
	public void objectArgUsesToString() {
		record Point(int x, int y) {}
		assertEquals("p=Point[x=1, y=2]", StringUtilx.fmt("p={}", new Point(1, 2)));
	}

	// ==================================================================
	// 第二组：转义（\{ \}）——剥除反斜杠、豁免配对计数
	// ==================================================================

	/** \{ 与 \} 输出字面花括号，且不影响其后空占位符的替换。模板实际内容：a \{ literal\} b{} */
	@Test
	public void escapedBracesOutputLiteralBraces() {
		assertEquals("a { literal} b66", StringUtilx.fmt("a \\{ literal\\} b{}", 66));
	}

	/** 全转义模板（无占位符）：输出字面花括号，实参被忽略。模板实际内容：\{\} */
	@Test
	public void allEscapedTemplateNeedsNoArgs() {
		assertEquals("{}", StringUtilx.fmt("\\{\\}", "X"));
	}

	/** 转义括号与占位符混排：转义侧输出字面括号，占位符正常替换。模板实际内容：\{ {} \} */
	@Test
	public void escapedBracesMixedWithPlaceholder() {
		assertEquals("{ X }", StringUtilx.fmt("\\{ {} \\}", "X"));
	}

	/** 转义的 \{ 紧邻占位符 {\{} ：字面 { 与替换值连排。模板实际内容：x\{{} */
	@Test
	public void escapedBraceAdjacentToPlaceholder() {
		assertEquals("x{v", StringUtilx.fmt("x\\{{}", "v"));
	}

	/** 模板末尾的孤立转义同样生效。模板实际内容：\{ */
	@Test
	public void trailingEscapedBrace() {
		assertEquals("{", StringUtilx.fmt("\\{"));
	}

	/** 反斜杠只对紧随其后的花括号生效：\n（两个字符）原样保留，不吞反斜杠。模板实际内容：a\nb{}c */
	@Test
	public void plainBackslashUnrelatedToBracesIsKept() {
		assertEquals("a\\nbXc", StringUtilx.fmt("a\\nb{}c", "X"));
	}

	/**
	 * 【语义局限快照】反斜杠自身不可转义：模板实际内容 \\{}（双反斜杠+占位符）中，
	 * 第二个 '\' 使 '{' 被判定为转义，于是模板只剩未配对的 '}'，解析期即抛异常。
	 * 即不存在「输出字面反斜杠」的写法。模板实际内容：\\{}
	 */
	@Test
	public void doubleBackslashIsNotSelfEscapable() {
		RuntimeException ex = assertThrows(RuntimeException.class, () -> StringUtilx.fmt("\\\\{}", "X"));
		assertTrue(ex.getMessage().contains("unmatch"), "应因花括号奇偶不配而失败: " + ex.getMessage());
	}

	// ==================================================================
	// 第三组：解析期 fail-fast 异常
	// ==================================================================

	/** 连续 {{ 属于非法模板，解析期抛异常。 */
	@Test
	public void consecutiveOpenBracesThrows() {
		RuntimeException ex = assertThrows(RuntimeException.class, () -> StringUtilx.fmt("{{}}", "X"));
		assertTrue(ex.getMessage().contains("Unexpect"), ex.getMessage());
	}

	/** 连续 }} 属于非法模板，解析期抛异常。 */
	@Test
	public void consecutiveCloseBracesThrows() {
		RuntimeException ex = assertThrows(RuntimeException.class, () -> StringUtilx.fmt("a}}b{}", "X"));
		assertTrue(ex.getMessage().contains("Unexpect"), ex.getMessage());
	}

	/** 花括号数量奇偶不配（多一个 '{'）解析期抛异常。 */
	@Test
	public void unmatchedOpenBraceThrows() {
		RuntimeException ex = assertThrows(RuntimeException.class, () -> StringUtilx.fmt("a{}b{", "X"));
		assertTrue(ex.getMessage().contains("unmatch"), ex.getMessage());
	}

	/** 孤立的 '}' 同样奇偶不配，解析期抛异常。 */
	@Test
	public void strayCloseBraceThrows() {
		RuntimeException ex = assertThrows(RuntimeException.class, () -> StringUtilx.fmt("abc}", "X"));
		assertTrue(ex.getMessage().contains("unmatch"), ex.getMessage());
	}

	/** 非空占位符内层再出现花括号（如 {a{b}）触发连续同类符号校验，解析期抛异常。 */
	@Test
	public void braceInsidePlaceholderAreaThrows() {
		RuntimeException ex = assertThrows(RuntimeException.class, () -> StringUtilx.fmt("{a{b}"));
		assertTrue(ex.getMessage().contains("Unexpect"), ex.getMessage());
	}

	/** 解析异常的模板不会被毒化进缓存：此后任何模板（含再次解析同一坏模板）行为不变。 */
	@Test
	public void failingTemplateDoesNotPoisonCache() {
		assertThrows(RuntimeException.class, () -> StringUtilx.fmt("{{}}", "X"));
		// 同一坏模板再次调用仍然抛
		assertThrows(RuntimeException.class, () -> StringUtilx.fmt("{{}}", "X"));
		// 其他模板不受影响
		assertEquals("aXb", StringUtilx.fmt("a{}b", "X"));
	}

	/** 同一模板多次调用（缓存命中路径）结果稳定，不同实参互不串扰。 */
	@Test
	public void cachedTemplateStaysStableAcrossArgs() {
		assertEquals("n=1", StringUtilx.fmt("n={}", 1));
		assertEquals("n=a", StringUtilx.fmt("n={}", "a"));
		assertEquals("n=1", StringUtilx.fmt("n={}", 1));
	}

	// ==================================================================
	// 第四组：非规范用法（非空占位符 {x}）的行为锁定
	//
	// 非空占位符不在 fmt 支持范围内（见 fmt javadoc 的强制约束），此组锁定其行为：
	// 1. 实参充足：内层字符被静默丢弃（占位符替换语义：'{' 到 '}' 之间整体被跳过）；
	// 2. 实参不足：从第一个未消费的占位符起模板【按原样输出】——此为缺参分支修复
	//    （起点 ly-1 改为 lx）后的期望行为，由本组回归守护。
	// 新代码请勿依赖本组任何行为。
	// ==================================================================

	/**
	 * 实参充足时，非空占位符 {x} 的内层字符被【静默丢弃】——
	 * 解析器只记录 { } 的位置，替换后 '{' 到 '}' 之间整体被跳过。
	 */
	@Test
	public void nonEmptyPlaceholderWithEnoughArgsDropsInnerChars() {
		assertEquals("aXb", StringUtilx.fmt("a{x}b", "X"));
	}

	/** 修复回归：实参不足 + 非空占位符——未消费的占位符（含内层字符）从 '{' 起按原样输出。 */
	@Test
	public void missingArgsOnNonEmptyPlaceholderKeptVerbatim() {
		assertEquals("{x} abc", StringUtilx.fmt("{x} abc"));
	}

	/** 修复回归：空占位符正常替换后，后续缺参的非空占位符同样从 '{' 起原样输出。 */
	@Test
	public void missingArgsOnLaterNonEmptyPlaceholderKeptVerbatim() {
		assertEquals("aXb{x}c", StringUtilx.fmt("a{}b{x}c", "X"));
	}

	/** 修复回归：内层多字符的占位符在缺参时同样完整原样输出。 */
	@Test
	public void multiCharInnerPlaceholderKeptVerbatimWhenArgsMissing() {
		assertEquals("{ab}Z", StringUtilx.fmt("{ab}Z"));
	}

	/** 修复回归：零实参（varargs 空数组）走缺参分支，非空占位符按原样输出。 */
	@Test
	public void zeroArgsOnNonEmptyPlaceholderKeptVerbatim() {
		assertEquals("a{x}b", StringUtilx.fmt("a{x}b"));
	}

	/**
	 * 修复回归：转义与缺参混排——转义照常剥反斜杠，未消费的非空占位符原样输出。
	 * 模板实际内容：\{ a{x}
	 */
	@Test
	public void missingArgsWithEscapePrefixKeptVerbatim() {
		assertEquals("{ a{x}", StringUtilx.fmt("\\{ a{x}"));
	}

	/**
	 * 【当前语义快照】实参数组为 null 时被替换为 emptyArgs={null}，走「null 实参」路径
	 * （非缺参分支），占位符【整体静默消失】。与缺参「原样输出」是两条不同路径，
	 * null 实参语义不在本次修复范围，此处仅作对照记录。
	 */
	@Test
	public void snapshot_nullArgsArrayVanishesWholePlaceholder() {
		assertEquals("ab", StringUtilx.fmt("a{x}b", (Object[]) null));
	}

}
