package org.agent.rag.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 代码内容嗅探（入库兜底）：识别 markdown 围栏之外的疑似代码行。
 * <p>
 * 与 {@link MarkdownChunker#containsFencedCode} 互补：围栏检测只认 ``` / ~~~ 包裹的块，
 * 本文档按"行"启发式识别裸代码（SQL 语句、编程语法行、行尾分号/花括号等）。
 * 偏向 recall（宁多勿漏）：命中即把 chunk 标为 is_code=true，弥补"作者忘声明 + 代码未用
 * 围栏"时的漏标泄漏。检索侧 {@link org.agent.rag.retrieve.CodeRedactor} 只脱敏围栏块，裸代码 chunk 对低密级用户
 * 因定位不到围栏而整块丢弃（fail-closed，宁缺毋滥）。
 */
@Component
public class CodeSniffer {

    /** 单行代码信号（按行、去首尾空白后匹配；任一命中即认为该行疑似代码） */
    private static final Pattern[] CODE_LINE_PATTERNS = {
            // ① SQL 语句：以子句开头的行（结构化，避开普通文本里孤立的关键词）
            Pattern.compile("(?i)^(select\\b.*\\bfrom|insert\\s+into\\b|update\\b.*\\bset\\b|delete\\s+from\\b|"
                    + "create\\s+(table|index|view|procedure|function|sequence|schema)\\b|alter\\s+table\\b|"
                    + "drop\\s+(table|index|view|procedure|function)\\b|grant\\b|revoke\\b|truncate\\s+table\\b|"
                    + "merge\\s+into\\b|begin\\s+transaction\\b|commit\\b|rollback\\b)"),
            // ② SQL 子句续行（多行 SQL 的 FROM/WHERE/JOIN 等；要求带比较符或列名，降低误报）
            Pattern.compile("(?i)^(from\\s+[\\w.]+|where\\b.*[=<>]|group\\s+by\\b|order\\s+by\\b|having\\b|"
                    + "join\\b.*\\bon\\b|left\\s+join\\b|right\\s+join\\b|inner\\s+join\\b|on\\s+[\\w.]+\\s*[=<>]|"
                    + "and\\s+[\\w.]+\\s*[=<>]|or\\s+[\\w.]+\\s*[=<>]|union\\b)"),
            // ③ 编程关键字行首（可见性/声明/控制流/函数定义）
            Pattern.compile("(?i)^(public|private|protected|static|final|abstract|synchronized|class\\b|interface\\b|"
                    + "enum\\b|record\\b|void\\b|def\\b|function\\b|const\\b|let\\b|var\\b|import\\b|package\\b|"
                    + "extends\\b|implements\\b|return\\b|throw\\b|throws\\b|try\\s*\\{|catch\\s*\\(|finally\\s*\\{|"
                    + "if\\s*\\(|for\\s*\\(|while\\s*\\(|switch\\s*\\(|do\\s*\\{|else\\s*\\{)"),
            // ④ switch 的 case 分支（要求 ASCII 冒号，避开中文全角"case 1："）
            Pattern.compile("(?i)^case\\s+[\\w.'\"\\[\\]-]+\\s*:"),
            // ⑤ 行尾分号/花括号（强特征：正文几乎不会以 ASCII ; { } 结尾）
            Pattern.compile(".*[;{}]\\s*$"),
            // ⑥ 构造/赋值：= new Xxx(（Java/.NET 常见，正文极少）
            Pattern.compile("(?i)\\bnew\\s+[A-Z]\\w*\\s*\\("),
            // ⑦ 打印/日志调用
            Pattern.compile("(?i)\\b(System\\.out|console\\.log|System\\.err|println)\\b"),
            // ⑧ 代码箭头/作用域解析（要求留白，避开 "A->B" 流程写法）
            Pattern.compile("\\s*(=>|::)\\s*"),
            // ⑨ 泛型/类型参数：List<String>、Map<String,Object>
            Pattern.compile("\\b[A-Z]\\w*\\s*<\\s*[A-Za-z_]\\w*\\s*>"),
    };

    /** document 级闸门：整篇正文疑似代码行达到阈值 → 需要对各 chunk 精确定位 */
    private final int docThreshold;
    /** chunk 级精确定位：chunk 内疑似代码行达到阈值 → 标为代码 chunk */
    private final int chunkThreshold;

    public CodeSniffer(@Value("${rag.ingest.code-sniff.doc-threshold:1}") int docThreshold,
                       @Value("${rag.ingest.code-sniff.chunk-threshold:1}") int chunkThreshold) {
        this.docThreshold = docThreshold;
        this.chunkThreshold = chunkThreshold;
    }

    /** 测试便捷构造：默认阈值 */
    public CodeSniffer() {
        this(1, 1);
    }

    /** 单行是否疑似代码（去首尾空白后按行判；入库打标与检索脱敏共用同一判据） */
    public static boolean isCodeLine(String line) {
        if (line == null) {
            return false;
        }
        String t = line.strip();
        if (t.isEmpty()) {
            return false;
        }
        for (Pattern p : CODE_LINE_PATTERNS) {
            if (p.matcher(t).find()) {
                return true;
            }
        }
        return false;
    }

    /** 文本中疑似代码的行数 */
    public static int codeLineCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int n = 0;
        for (String line : text.split("\n", -1)) {
            if (isCodeLine(line)) {
                n++;
            }
        }
        return n;
    }

    /** document 级筛查（闸门）：整篇正文疑似代码行达到阈值 */
    public boolean docLooksLikeCode(String body) {
        return codeLineCount(body) >= docThreshold;
    }

    /** chunk 级精确定位：该 chunk 内疑似代码行达到阈值 → 代码 chunk */
    public boolean chunkLooksLikeCode(String text) {
        return codeLineCount(text) >= chunkThreshold;
    }
}
