package cn.tianlong.tlobject.aiagent.workflow;

import java.util.*;

/**
 * 工作流表达式解析器——把逻辑表达式编译成 DAG 节点/边，喂给现有 TLWorkflowEngine。
 * 让用户/LLM 用逻辑关系描述工作流，无需手写 nodes/edges。
 *
 * <h3>语法</h3>
 * <pre>{@code
 * expr    := orExpr ( "->" orExpr )*        // 顺序链（优先级最松）
 * orExpr  := andExpr ( "||" andExpr )*      // 回退：左失败才跑右
 * andExpr := atom ( "&&" atom )*            // 并行：全部执行 + 汇聚
 * atom    := "(" expr ")" | ifClause | IDENT
 * ifClause:= "if" "(" cond ")" atom "else" atom   // else-if 用嵌套：if(c1) A else (if(c2) B else C)
 * cond    := IDENT "." IDENT op literal     // 单层 path，对齐 TLWorkflowContext.getValueByPath
 * op      := ">" | ">=" | "<" | "<=" | "==" | "!="
 * literal := 数字 | '引号串' | "引号串"        // 字符串仅对 == / != 有意义
 * }</pre>
 *
 * <h3>示例</h3>
 * <pre>{@code
 * A && B -> C                              // A、B 并行，都完成后 C（合并 A/B 产出）
 * (A || B) -> C                            // A 失败则回退 B，C 收活分支结果
 * A -> if (A.conf >= 0.5) B else C         // 值分支（条件自动等 A 产出）
 * }</pre>
 *
 * <h3>语义要点</h3>
 * <ul>
 *   <li>IDENT = 节点模块名，复用动态节点机制（工作流自身 &lt;modules&gt; 私有实例优先，工厂同名模块兜底）</li>
 *   <li>{@code ->} 数据流由引擎原生支持：下游 nodeInput 合并上游全部产出 args</li>
 *   <li>{@code ||} 左右子树的 AGENT 节点自动设 onFailure="skip"，失败放行路由</li>
 *   <li>if 条件引用的前缀节点（如 A.conf 的 A）若在图中会自动补依赖边；前缀在条件之后出现会构成环，
 *       由引擎拓扑排序检测报 CYCLE_DETECTED</li>
 *   <li>生成节点 id 形如 __e1（用户 IDENT 禁止 __ 前缀）</li>
 * </ul>
 *
 * 零外部依赖，纯静态工具，不接触工厂。
 *
 * @author tianlong
 * @since 2026/8/13
 */
public class TLWorkflowExprParser {

    /** 编译产物：DAG 节点 + 边（与 doWorkflow 动态参数同构） */
    public static class Compiled {
        public final Map<String, TLWorkflowNode> nodes = new LinkedHashMap<>();
        public final List<TLWorkflowEdge> edges = new ArrayList<>();
    }

    /** 表达式错误（携带位置信息） */
    public static class ExprParseException extends RuntimeException {
        public ExprParseException(String msg, int pos) {
            super("pos " + pos + ": " + msg);
        }
    }

    /** 保留字 */
    private static final Set<String> RESERVED = new HashSet<>(Arrays.asList(
            "if", "else", "true", "false", "and", "or", "not"));

    /** 子图形状：入口节点集 + 唯一出口节点 */
    private static class Shape {
        final List<String> entries;
        final String exit;
        Shape(List<String> entries, String exit) {
            this.entries = entries;
            this.exit = exit;
        }
    }

    // ======================== 扫描器状态 ========================

    private final String src;
    private int pos;

    // ======================== 图构建状态 ========================

    private final Compiled out = new Compiled();
    private int genSeq;
    private final Set<String> usedIdents = new HashSet<>();
    /** 按创建顺序记录的 AGENT 节点 id（|| 回退标记用） */
    private final List<String> agentIds = new ArrayList<>();
    /** 条件节点 id → cond 引用的前缀节点（后置补依赖边） */
    private final Map<String, String> condDeps = new LinkedHashMap<>();

    private TLWorkflowExprParser(String src) {
        this.src = src;
    }

    /**
     * 编译表达式为 DAG 节点/边。
     *
     * @param expr 表达式文本
     * @return 编译产物
     * @throws ExprParseException 语法错误（含位置信息）
     */
    public static Compiled compile(String expr) {
        if (expr == null || expr.trim().isEmpty()) {
            throw new ExprParseException("expression is empty", 0);
        }
        TLWorkflowExprParser p = new TLWorkflowExprParser(expr);
        p.parseExpr();
        p.skipWs();
        if (p.pos < p.src.length()) {
            throw new ExprParseException("unexpected token: '" + p.src.substring(p.pos) + "'", p.pos);
        }
        p.addCondDeps();
        return p.out;
    }

    // ======================== 图构建 ========================

    private String nextGenId() {
        return "__e" + (++genSeq);
    }

    private Shape addAgent(String ident) {
        if (usedIdents.contains(ident)) {
            throw new ExprParseException("duplicate node id: " + ident, pos);
        }
        usedIdents.add(ident);
        TLWorkflowNode n = new TLWorkflowNode();
        n.setId(ident);
        n.setType(TLWorkflowNodeType.AGENT);
        n.setModule(ident);
        n.setAction("chat");
        out.nodes.put(ident, n);
        agentIds.add(ident);
        return new Shape(Collections.singletonList(ident), ident);
    }

    /** 新建汇聚节点（JOIN），兼作结果载体 */
    private Shape addJoin() {
        String id = nextGenId();
        TLWorkflowNode n = new TLWorkflowNode();
        n.setId(id);
        n.setType(TLWorkflowNodeType.JOIN);
        out.nodes.put(id, n);
        return new Shape(Collections.singletonList(id), id);
    }

    /**
     * 新建条件节点（CONDITION）。
     * expression 为 null 时读上游合并结果的 RESULT（|| 回退路由依赖此语义）。
     */
    private Shape addCondition(String expression) {
        String id = nextGenId();
        TLWorkflowNode n = new TLWorkflowNode();
        n.setId(id);
        n.setType(TLWorkflowNodeType.CONDITION);
        if (expression != null) {
            Map<String, String> params = new HashMap<>();
            params.put("expression", expression);
            n.setParams(params);
        }
        out.nodes.put(id, n);
        return new Shape(Collections.singletonList(id), id);
    }

    private void addEdge(String from, String to, String condition) {
        TLWorkflowEdge e = new TLWorkflowEdge();
        e.setFrom(from);
        e.setTo(to);
        e.setCondition(condition);
        out.edges.add(e);
    }

    private boolean hasEdge(String from, String to) {
        for (TLWorkflowEdge e : out.edges) {
            if (from.equals(e.getFrom()) && to.equals(e.getTo())) return true;
        }
        return false;
    }

    /** 把 from 出口连到 entries 每个入口（无条件边） */
    private void link(String from, List<String> entries) {
        for (String to : entries) {
            addEdge(from, to, null);
        }
    }

    /** 标记 onFailure="skip"（|| 子树内失败放行路由） */
    private void setSkipOnFailure(String agentId) {
        TLWorkflowNode n = out.nodes.get(agentId);
        if (n != null) n.setOnFailure("skip");
    }

    // ======================== 递归下降解析 ========================

    /** expr := orExpr ( "->" orExpr )* */
    private Shape parseExpr() {
        Shape left = parseOr();
        skipWs();
        while (tryConsume("->")) {
            Shape right = parseOr();
            link(left.exit, right.entries);
            left = new Shape(left.entries, right.exit);
        }
        return left;
    }

    /** orExpr := andExpr ( "||" andExpr )* —— 回退：左失败才跑右 */
    private Shape parseOr() {
        int before = agentIds.size();
        Shape left = parseAnd();
        int afterLeft = agentIds.size();
        skipWs();
        while (tryConsume("||")) {
            Shape right = parseAnd();
            int afterRight = agentIds.size();
            // 左右子树内 AGENT 全部 skip-on-failure，保证路由一定收到判定
            for (int i = before; i < afterLeft; i++) setSkipOnFailure(agentIds.get(i));
            for (int i = afterLeft; i < afterRight; i++) setSkipOnFailure(agentIds.get(i));
            // 路由（读左出口 RESULT）+ 汇聚
            Shape route = addCondition(null);
            Shape sink = addJoin();
            addEdge(left.exit, route.exit, null);
            addEdge(route.exit, sink.exit, "true");
            for (String to : right.entries) {
                addEdge(route.exit, to, "false");
            }
            addEdge(right.exit, sink.exit, null);
            left = new Shape(left.entries, sink.exit);
            afterLeft = afterRight;
        }
        return left;
    }

    /** andExpr := atom ( "&&" atom )* —— 并行 + 汇聚 */
    private Shape parseAnd() {
        Shape left = parseAtom();
        skipWs();
        while (tryConsume("&&")) {
            Shape right = parseAtom();
            Shape sink = addJoin();
            addEdge(left.exit, sink.exit, null);
            addEdge(right.exit, sink.exit, null);
            List<String> entries = new ArrayList<>(left.entries);
            entries.addAll(right.entries);
            left = new Shape(entries, sink.exit);
        }
        return left;
    }

    /** atom := "(" expr ")" | ifClause | IDENT */
    private Shape parseAtom() {
        skipWs();
        if (tryConsume("(")) {
            Shape inner = parseExpr();
            skipWs();
            if (!tryConsume(")")) throw new ExprParseException("expected ')'", pos);
            return inner;
        }
        if (peekKeyword("if")) {
            return parseIf();
        }
        return addAgent(readIdent());
    }

    /** ifClause := "if" "(" cond ")" atom "else" atom */
    private Shape parseIf() {
        consumeKeyword("if");
        skipWs();
        if (!tryConsume("(")) throw new ExprParseException("expected '(' after if", pos);
        String cond = parseCond();
        skipWs();
        if (!tryConsume(")")) throw new ExprParseException("expected ')' after condition", pos);
        Shape thenShape = parseAtom();
        skipWs();
        consumeKeyword("else");
        Shape elseShape = parseAtom();

        Shape condNode = addCondition(cond);
        for (String to : thenShape.entries) addEdge(condNode.exit, to, "true");
        for (String to : elseShape.entries) addEdge(condNode.exit, to, "false");
        // 重建合流点（依赖引擎 join-aware 跳过传播）
        Shape sink = addJoin();
        addEdge(thenShape.exit, sink.exit, null);
        addEdge(elseShape.exit, sink.exit, null);

        // 条件数据依赖：cond 的前缀节点（A.conf → A）在图中则补边
        String prefix = cond.substring(0, cond.indexOf('.'));
        if (!prefix.equals(condNode.exit)) {
            condDeps.put(condNode.exit, prefix);
        }
        return new Shape(condNode.entries, sink.exit);
    }

    /** cond := IDENT "." IDENT op literal —— 规范化为单空格连接（对齐 evalCondition） */
    private String parseCond() {
        skipWs();
        String prefix = readIdent();
        if (!tryConsume(".")) throw new ExprParseException("expected '.' in condition path", pos);
        String field = readIdent();
        skipWs();
        String op = readOp();
        skipWs();
        String literal = readLiteral();
        return prefix + "." + field + " " + op + " " + literal;
    }

    // ======================== 后置处理 ========================

    /** 为条件节点补数据依赖边（前缀节点在图中且边不存在时） */
    private void addCondDeps() {
        for (Map.Entry<String, String> e : condDeps.entrySet()) {
            String condId = e.getKey();
            String prefix = e.getValue();
            if (out.nodes.containsKey(prefix) && !prefix.equals(condId) && !hasEdge(prefix, condId)) {
                addEdge(prefix, condId, null);
            }
        }
    }

    // ======================== 词法 ========================

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private boolean tryConsume(String tok) {
        if (src.startsWith(tok, pos)) {
            pos += tok.length();
            return true;
        }
        return false;
    }

    private boolean peekKeyword(String kw) {
        if (!src.startsWith(kw, pos)) return false;
        int end = pos + kw.length();
        return end >= src.length() || !isIdentChar(src.charAt(end));
    }

    private void consumeKeyword(String kw) {
        if (!peekKeyword(kw)) throw new ExprParseException("expected '" + kw + "'", pos);
        pos += kw.length();
    }

    /** IDENT：[A-Za-z_][A-Za-z0-9_]*，排除保留字与 __ 前缀（'-' 与 "->" 冲突，不允许） */
    private String readIdent() {
        skipWs();
        int start = pos;
        while (pos < src.length() && isIdentChar(src.charAt(pos))) pos++;
        if (start == pos) throw new ExprParseException("expected IDENT", pos);
        String id = src.substring(start, pos);
        if (RESERVED.contains(id)) throw new ExprParseException("reserved word: " + id, start);
        if (id.startsWith("__")) throw new ExprParseException("ident must not start with '__': " + id, start);
        return id;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private String readOp() {
        skipWs();
        if (tryConsume(">=")) return ">=";
        if (tryConsume("<=")) return "<=";
        if (tryConsume("==")) return "==";
        if (tryConsume("!=")) return "!=";
        if (tryConsume(">")) return ">";
        if (tryConsume("<")) return "<";
        throw new ExprParseException("expected comparison operator", pos);
    }

    /** literal := 数字（可带小数点/负号）| '串' | "串"（原样返回含引号，evalCondition 识别） */
    private String readLiteral() {
        skipWs();
        if (pos >= src.length()) throw new ExprParseException("expected literal", pos);
        int start = pos;
        char c = src.charAt(pos);
        if (c == '\'' || c == '"') {
            char quote = c;
            pos++;
            while (pos < src.length() && src.charAt(pos) != quote) pos++;
            if (pos >= src.length()) throw new ExprParseException("unterminated string", start);
            pos++;  // 收引号
            return src.substring(start, pos);
        }
        if (c == '-' || Character.isDigit(c)) {
            pos++;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
            return src.substring(start, pos);
        }
        throw new ExprParseException("expected literal (number or quoted string)", pos);
    }
}
