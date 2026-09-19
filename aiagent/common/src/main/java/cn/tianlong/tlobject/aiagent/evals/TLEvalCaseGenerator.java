package cn.tianlong.tlobject.aiagent.evals;

import cn.tianlong.tlobject.aiagent.*;
import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测用例生成器：给 agent 名 + 一句要求，自动产出用例、落盘、并立即跑一遍。
 *
 * 流程：收集材料（md 全文 + 子模块清单 + 目标存在性）→ LLM 出能力点清单 →
 * LLM 出用例 JSON → 程序化校验 → 落盘到评测用例目录 → 调评测模块只跑这一批。
 *
 * 为什么不做成 agent：这条链路是单向流水线，不需要自主决策循环；做成 agent 要多一层
 * 配置和 LLM 循环，换来的自主性这里用不上。
 *
 * 创建日期：2026/9/19
 * 作者:tianlong
 */
public class TLEvalCaseGenerator extends TLBaseModule implements TLAiAgentParamString {

    /** 生成用的 Provider；留空则回落到评测模块的 judgeProvider */
    private String generatorProvider = "";
    /** 用例落盘目录；留空则问评测模块要（避免两处配置漂移，见 getGenDefaults） */
    private String evalCaseDir = "";
    /** 单次生成的用例数上限（提示词里也写了，这里再兜一道） */
    private int maxCases = 12;
    /** 分析阶段输出的 token 上限 */
    private int analysisMaxTokens = 1024;
    /** 出用例阶段的 token 上限（用例 JSON 比清单长得多） */
    private int casesMaxTokens = 4096;

    public TLEvalCaseGenerator() { super(); }
    public TLEvalCaseGenerator(String name) { super(name); }
    public TLEvalCaseGenerator(String name, TLObjectFactory factory) { super(name, factory); }

    @Override
    protected TLBaseModule init() {
        putLog("TLEvalCaseGenerator 初始化完成。generatorProvider="
                + (generatorProvider.isEmpty() ? "(回落评测模块 judgeProvider)" : generatorProvider)
                + ", evalCaseDir=" + (evalCaseDir.isEmpty() ? "(回落评测模块 evalCaseDir)" : evalCaseDir)
                + ", maxCases=" + maxCases, LogLevel.INFO);
        return this;
    }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params == null) return;
        if (params.get("generatorProvider") != null) generatorProvider = params.get("generatorProvider").trim();
        if (params.get("evalCaseDir") != null) evalCaseDir = params.get("evalCaseDir").trim();
        if (params.get("maxCases") != null) {
            try { maxCases = Integer.parseInt(params.get("maxCases").trim()); }
            catch (NumberFormatException e) { putLog("maxCases 不是整数，用默认 12: " + params.get("maxCases"), LogLevel.WARN); }
        }
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case "generateCases": return generateCases(fromWho, msg);
            default: return null;
        }
    }

    protected TLMsg generateCases(Object fromWho, TLMsg msg) {
        String target = msg.getStringParam("agent", "").trim();
        if (target.isEmpty()) return fail("agent 参数必填（要评测哪个 agent）");
        String requirement = msg.getStringParam("requirement", "");

        // ① 目标不存在就别生成——否则产出一堆"目标模块不存在"的用例
        if (!targetExists(target)) return fail("目标不存在: " + target + "（先确认模块名/家族名）");

        Map<String, Object> defaults = genDefaults();
        String dir = nonEmpty(evalCaseDir, str(defaults.get("evalCaseDir")));
        if (dir.isEmpty()) return fail("拿不到评测用例目录（评测模块未配置？）");
        String provider = nonEmpty(generatorProvider, str(defaults.get("judgeProvider")));
        if (provider.isEmpty()) return fail("没有可用的 Provider（配 evalCaseGenerator_config.xml 的 generatorProvider，或评测模块的 judgeProvider）");

        // ② 收集材料 → LLM 出能力点清单
        String materials = collectMaterials(target);
        String analysis = callLlm(provider,
                TLEvalGenPrompts.buildAnalysisPrompt(target, materials, requirement), analysisMaxTokens);
        if (analysis == null || analysis.trim().isEmpty()) {
            return fail("分析阶段没有拿到 LLM 输出（检查 Provider 配置与连通性）");
        }
        putLog("===== 能力点清单 =====\n" + analysis, LogLevel.INFO);

        // ③ 能力点清单 → 用例 JSON
        String json = callLlm(provider,
                TLEvalGenPrompts.buildCasesPrompt(target, analysis, requirement), casesMaxTokens);
        if (json == null || json.trim().isEmpty()) {
            return fail("生成阶段没有拿到 LLM 输出");
        }
        List<TLEvalCase> raw = TLEvalGenSupport.parseCasesJson(json);
        if (raw.isEmpty()) {
            // 回显原文：解析失败时最该看到的就是它到底吐了什么
            return fail("LLM 输出解析不了（不是合法 JSON 数组），原文片段：\n" + clip(json, 800));
        }
        if (raw.size() > maxCases) {
            putLog("生成 " + raw.size() + " 条，超过上限 " + maxCases + "，截断", LogLevel.WARN);
            raw = new ArrayList<>(raw.subList(0, maxCases));
        }

        // ④ 校验（缺 judges / 缺 id / id 冲突的都丢掉，并记下原因）
        List<String> dropped = new ArrayList<>();
        List<TLEvalCase> cases = TLEvalGenSupport.validateAndFilter(raw, existingCaseIds(dir), dropped);
        // 评测对象由用户指定，不许 LLM 在用例里改
        TLEvalGenSupport.normalizeTarget(cases, target);
        if (cases.isEmpty()) {
            return fail("生成的用例全都不合格（" + String.join("；", dropped) + "）");
        }

        // ⑤ 落盘：一次生成一个文件
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm").format(new Date());
        String fileName = TLEvalGenSupport.buildFileName(target, stamp);
        File outFile = new File(dir, fileName);
        try {
            writeCases(outFile, cases);
        } catch (Exception e) {
            return fail("写用例文件失败: " + e);
        }
        putLog("用例已落盘: " + outFile.getAbsolutePath(), LogLevel.INFO);

        // ⑥ 只跑这一批（runEval 收绝对路径，不影响目录里原有的用例）
        TLMsg runMsg = createMsg().setAction("runEval").setParam("caseFile", outFile.getAbsolutePath());
        runMsg.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg runResult = putMsg("evals", runMsg);

        TLMsg out = createMsg().setParam(RESULT, true)
                .setParam("reportPath", outFile.getAbsolutePath())
                .setParam("caseCount", cases.size())
                .setParam("caseFile", fileName)
                .setParam("analysis", analysis);
        if (!dropped.isEmpty()) out.setParam("dropped", dropped);
        if (runResult != null) {
            out.setParam("passed", runResult.getIntParam("passed", 0))
               .setParam("failed", runResult.getIntParam("failed", 0))
               .setParam("passRate", runResult.getDoubleParam("passRate", 0.0))
               .setParam("evalReportPath", runResult.getStringParam("reportPath", ""));
        }
        return out;
    }

    // ======================== 材料与探测 ========================

    /** 目标是否存在：先查注册表（家族名），再按工厂模块名查 */
    private boolean targetExists(String target) {
        TLMsg getMsg = createMsg().setAction(REGISTRY_GET).setParam(REGISTRY_P_KEY, target);
        getMsg.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg r = putMsg(DEFAULTMODULEREGISTRY, getMsg);
        if (r != null && r.getParam(INSTANCE) instanceof IObject) return true;
        if (target.contains(":")) return false;   // 家族名不在注册表里就是没有
        try {
            TLObjectFactory factory = getFactory();
            return factory != null && factory.getModule(target) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 材料 = agent 的 md 全文（正文比 description 一行有用得多）+ 注册表里的子模块清单 */
    private String collectMaterials(String target) {
        StringBuilder sb = new StringBuilder();

        String shortName = target.contains(":")
                ? target.substring(target.lastIndexOf(':') + 1) : target;
        String md = null;
        try {
            TLObjectFactory factory = getFactory();
            if (factory != null) {
                md = TLMdFileLoader.readFileOrResource(factory.getConfigDir() + "md/" + shortName + ".md",
                        this.getClass());
            }
        } catch (Exception e) {
            putLog("读 md 失败（不致命，继续）: " + e, LogLevel.DEBUG);
        }
        if (md != null && !md.trim().isEmpty()) {
            sb.append("【说明文档】\n").append(clip(md, 6000)).append("\n\n");
        } else {
            sb.append("【说明文档】\n（没有找到 ").append(shortName).append(".md）\n\n");
        }

        try {
            TLMsg listMsg = createMsg().setAction(REGISTRY_LIST).setParam(REGISTRY_P_OWNERNAME, target);
            listMsg.setSystemParam(IGNOREMODULEISNULL, true);
            TLMsg r = putMsg(DEFAULTMODULEREGISTRY, listMsg);
            List<Map<String, Object>> children =
                    r == null ? null : (List<Map<String, Object>>) r.getParam(RESULT);
            if (children != null && !children.isEmpty()) {
                sb.append("【子模块】\n");
                for (Map<String, Object> c : children) {
                    Object key = c.get(REGISTRY_P_KEY);
                    if (key != null) sb.append("- ").append(key).append("\n");
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            putLog("枚举子模块失败（不致命，继续）: " + e, LogLevel.DEBUG);
        }
        return sb.toString();
    }

    /** 用例目录里已有的 id（用于查冲突） */
    private List<String> existingCaseIds(String dir) {
        List<String> ids = new ArrayList<>();
        TLMsg m = createMsg().setAction("listEvalCases");
        m.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg r = putMsg("evals", m);
        if (r == null) return ids;
        Object list = r.getParam("caseList");
        if (!(list instanceof List)) return ids;
        for (Object line : (List<?>) list) {
            String s = String.valueOf(line);
            int lb = s.indexOf('[');
            int rb = s.indexOf(']');
            if (lb >= 0 && rb > lb) ids.add(s.substring(lb + 1, rb));
        }
        return ids;
    }

    /** 向评测模块要默认值（用例目录、judgeProvider）——避免两处配置各写一份、漂移了没人发现 */
    private Map<String, Object> genDefaults() {
        TLMsg m = createMsg().setAction("getGenDefaults");
        m.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg r = putMsg("evals", m);
        if (r == null) return new LinkedHashMap<>();
        Object data = r.getParam("data");
        if (data instanceof Map) return (Map<String, Object>) data;
        return new LinkedHashMap<>();
    }

    // ======================== LLM 调用 ========================

    /** 调一次 Provider；失败返回 null（调用方报错，不吞） */
    private String callLlm(String provider, String prompt, int maxTokens) {
        List<TLConversationHistory> history = new ArrayList<>();
        history.add(new TLConversationHistory(TLConversationHistory.Role.user, prompt));
        TLMsg llmMsg = createMsg()
                .setAction(LLM_COMPLETION)
                .setParam(AI_P_MESSAGEHISTORY, history)   // Provider 只认这个，不认 AI_P_USERMESSAGE
                .setParam(AI_P_TEMPERATURE, 0.3)
                .setParam(AI_P_MAXTOKENS, maxTokens);
        // 配错 provider 时只让这次调用失败，而不是走 putMsg 默认的 shutdown(-1) 关掉应用
        llmMsg.setSystemParam(IGNOREMODULEISNULL, true);
        try {
            TLMsg r = putMsg(provider, llmMsg);
            if (r == null) return null;
            // Provider 用 RESULT 表示成败（同 TLLlmJudge / checkProvider），而失败时它照样会把
            // 错误文案塞进 AI_P_RESPONSE——不判 RESULT 就会把"调用失败：xxx"当成 LLM 输出，
            // 一路带到下一段提示词里，最后报成"输出解析不了"，把真正的原因埋掉
            if (!r.parseBoolean(RESULT, false)) {
                putLog("Provider 调用失败: "
                        + r.getStringParam("error", r.getStringParam(AI_P_RESPONSE, "")), LogLevel.ERROR);
                return null;
            }
            String text = r.getStringParam(AI_P_RESPONSE, "");
            return text.isEmpty() ? null : text;
        } catch (Exception e) {
            putLog("调 Provider 失败: " + e, LogLevel.ERROR);
            return null;
        }
    }

    // ======================== 落盘 ========================

    private void writeCases(File file, List<TLEvalCase> cases) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (Writer w = new OutputStreamWriter(new java.io.FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(gson.toJson(cases));
        }
    }

    // ======================== 工具 ========================

    private TLMsg fail(String error) {
        putLog("生成用例失败: " + error, LogLevel.ERROR);
        return createMsg().setParam(RESULT, false).setParam("error", error);
    }

    private static String nonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : (b == null ? "" : b);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…(已截断)" : s;
    }
}
