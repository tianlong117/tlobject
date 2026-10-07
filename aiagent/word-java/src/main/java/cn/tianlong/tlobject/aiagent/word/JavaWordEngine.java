package cn.tianlong.tlobject.aiagent.word;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Word .docx 引擎（纯 POI，不依赖框架）。
 * 技能壳 TLWordJavaSkill 负责把消息翻成 (action, input)，本类只做文档操作。
 *
 * 创建日期：2026/10/07 作者:tianlong
 */
public class JavaWordEngine {

    public static class Config {
        public Path allowedRoot;
        public Path workDir;
        public boolean backup;            // 改动前是否留 .bak.docx（默认关，原子写已够安全）
    }

    public static class Result {
        public boolean ok;
        public boolean textMode;          // true=读类，输出原始文本
        public String text;
        public Map<String, Object> json = new LinkedHashMap<>();

        public static Result text(String t) {
            Result r = new Result();
            r.ok = true; r.textMode = true; r.text = t;
            return r;
        }
        public static Result fail(String msg) {
            Result r = new Result();
            r.ok = false;
            r.json.put("ok", false);
            r.json.put("error", msg);
            return r;
        }
    }

    private final Config cfg;

    public JavaWordEngine(Config cfg) { this.cfg = cfg; }

    public Result execute(String action, Map<String, Object> input) {
        return Result.fail("not implemented: " + action);
    }
}
