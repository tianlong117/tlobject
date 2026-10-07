package cn.tianlong.tlobject.aiagent.word;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Word 文档处理 Skill（Java 版）。函数名默认 word。
 * 读/写/改 .docx：正文、标题层级、表格、模板占位符填充。仅支持 .docx（.doc 请先另存）。
 *
 * 配置参数（XML params）：
 *   allowedRootPath  允许操作的根目录（默认 "."，按进程 CWD 解析）
 *   workDir          裸文件名落到的工作目录（默认 data/documents）
 *
 * 创建日期：2026/10/07 作者:tianlong
 */
public class TLWordJavaSkill extends TLBaseSkill {

    private String allowedRootPath = ".";
    private String workDir = "data/documents";

    public TLWordJavaSkill() { super(); }
    public TLWordJavaSkill(String name) { super(name); }
    public TLWordJavaSkill(String name, TLObjectFactory factory) { super(name, factory); }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("allowedRootPath") != null) allowedRootPath = params.get("allowedRootPath");
            if (params.get("workDir") != null) workDir = params.get("workDir");
        }
        super.setModuleParams();

        if (skillName == null || skillName.isEmpty()) skillName = "word";
        if (skillDescription == null || skillDescription.isEmpty())
            skillDescription = "Read, create and modify Microsoft Word .docx documents. "
                    + "Actions: read (structured text with paragraph numbers and styles), outline "
                    + "(headings only — use this FIRST on a long document), tables, info, "
                    + "create/append (content is Markdown: # headings, - bullets, |a|b| tables, "
                    + "**bold**, --- page break), replace (find/replace, preserves formatting by "
                    + "default; mode=rewrite if a paragraph is skipped), set_paragraph, "
                    + "insert_paragraph, delete_paragraph, set_table_cell, add_table_row, "
                    + "fill_template (replaces ${key}/{{key}} placeholders in body and tables). "
                    + "ONLY .docx is supported — .doc must be re-saved as .docx first. "
                    + "WORKFLOW: call outline or read first to get PARAGRAPH NUMBERS, then use "
                    + "those numbers with the modify actions — never guess. Paragraph numbers "
                    + "SHIFT after insert_paragraph/delete_paragraph, so re-read before the next "
                    + "edit. replace reports \"skipped\" when a paragraph contains hyperlinks or "
                    + "field codes (those cannot be edited in place without damage) — if skipped>0, "
                    + "retry that edit with mode=rewrite.";

        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            parameterSchema.put("action", prop("string",
                    "read(from?,to?) | outline | tables(index?) | info | create(path,content,overwrite?) | "
                    + "append(path,content) | replace(path,find,replace,index?,mode?) | "
                    + "set_paragraph(path,index,text) | insert_paragraph(path,index,text,style?,position?) | "
                    + "delete_paragraph(path,index) | set_table_cell(path,table,row,col,text) | "
                    + "add_table_row(path,table,values?) | fill_template(path,data,output?)", true));
            parameterSchema.put("path", prop("string", "File path: bare name → work dir; relative → allowed root; absolute must be inside allowed root"));
            parameterSchema.put("content", prop("string", "Markdown content for create/append"));
            parameterSchema.put("find", prop("string", "Text to find (literal) for replace"));
            parameterSchema.put("replace", prop("string", "Replacement text"));
            parameterSchema.put("index", prop("number", "Paragraph number from read/outline; for `tables` = table number (-1 = all)"));
            parameterSchema.put("mode", prop("string", "replace mode: preserve (default, keeps formatting) | rewrite"));
            parameterSchema.put("text", prop("string", "Text for set_paragraph / insert_paragraph / set_table_cell"));
            parameterSchema.put("style", prop("string", "Paragraph style for insert_paragraph, e.g. Heading1"));
            parameterSchema.put("position", prop("string", "insert_paragraph: before (default) | after"));
            parameterSchema.put("table", prop("number", "Table number from read/tables"));
            parameterSchema.put("row", prop("number", "0-based row index"));
            parameterSchema.put("col", prop("number", "0-based column index"));
            parameterSchema.put("values", prop("string", "Comma-separated cell values for add_table_row"));
            parameterSchema.put("data", prop("object", "Key→value map for fill_template placeholders"));
            parameterSchema.put("output", prop("string", "fill_template output path (default: overwrite input)"));
            parameterSchema.put("overwrite", prop("boolean", "create: allow overwriting an existing file"));
            parameterSchema.put("from", prop("number", "read: first paragraph number (default 0)"));
            parameterSchema.put("to", prop("number", "read: last paragraph number (-1 = end)"));
        }
    }

    private Map<String, Object> prop(String type, String desc) { return prop(type, desc, false); }

    private Map<String, Object> prop(String type, String desc, boolean required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", desc);
        if (required) m.put("required", true);
        return m;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        Map<String, Object> input = (Map<String, Object>) msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
        if (input.isEmpty()) {
            input = new LinkedHashMap<>();
            for (String k : new String[]{"action", "path", "content", "find", "replace", "index",
                    "mode", "text", "style", "position", "table", "row", "col", "values", "data",
                    "output", "overwrite", "from", "to"}) {
                if (msg.containsParam(k)) input.put(k, msg.getParam(k));
            }
        }
        String action = input.get("action") != null ? String.valueOf(input.get("action")) : "";
        if (action.isEmpty())
            return createMsg().setParam(RESULT, false).setParam(AI_P_SKILLOUTPUT,
                    "Error: action is required");

        try {
            // ensureEngine 在 try 内：classpath 手接线缺失时引擎构造抛的是 LinkageError，
            // 框架工具执行器不兜 Error——放 try 外工具结果会凭空消失（与 browser-java/desktop-java 同款防护）
            JavaWordEngine eng = ensureEngine(msg);
            JavaWordEngine.Result r = eng.execute(action.toLowerCase(), input);
            if (r.textMode)
                return createMsg().setParam(RESULT, r.ok).setParam(AI_P_SKILLOUTPUT, r.text);
            return createMsg().setParam(RESULT, r.ok).setParam(AI_P_SKILLOUTPUT, GSON.toJson(r.json));
        } catch (Exception e) {
            putLog("Word action failed: " + e, LogLevel.WARN);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e)) + "\"}");
        } catch (LinkageError e) {
            putLog("Word action failed (linkage): " + e, LogLevel.WARN);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "{\"ok\":false,\"error\":\"missing classes (POI not on classpath?): "
                            + esc(String.valueOf(e)) + "\"}");
        }
    }

    /** 每用户独立工作目录。userId 三通道取：args → systemArgs → sessionId 兜底
     *  （与 TLScheduleTaskSkill 同款约定：执行器把 userId 放在 systemArgs，只读 args 会漏） */
    private JavaWordEngine ensureEngine(TLMsg msg) {
        JavaWordEngine.Config cfg = new JavaWordEngine.Config();
        cfg.allowedRoot = Paths.get(allowedRootPath);
        String userId = msg.getStringParam(AI_P_USERID, null);
        if (userId == null || userId.isEmpty()) {
            Object u = msg.getSystemParam(AI_P_USERID, null);
            userId = (u == null || String.valueOf(u).isEmpty())
                    ? String.valueOf(msg.getSystemParam(AI_P_SESSIONID, "default"))
                    : String.valueOf(u);
        }
        cfg.workDir = Paths.get(workDir).resolve(userId);
        return new JavaWordEngine(cfg);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 与 browser-java/desktop-java 同款：base64 的 "=" 不能被 HTML 转义 */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
}
