package cn.tianlong.tlobject.aiagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 技能可用性校验自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>覆盖：scripts 下至少一个受支持脚本、interpreter 按扩展名推断、
 * 描述来源优先级（SKILL.md → &lt;目录名&gt;.md → &lt;skillName&gt;.md → md/&lt;目录名&gt;.md）
 * 及 frontmatter description 必填。</p>
 */
public class TLToolManagerSkillUsabilitySelfTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("tlskill_usage");
        try {
            Path config = root;
            Path skills = root.resolve("skills");
            Path md = root.resolve("md");
            Files.createDirectories(md);

            // good：SKILL.md + py 脚本 + 干扰文件
            Path good = mkSkill(skills, "good");
            write(good.resolve("SKILL.md"), "---\ndescription: good skill\n---\n");
            write(good.resolve("scripts/run.py"), "print(1)\n");
            write(good.resolve("scripts/notes.txt"), "not a script\n");
            Files.createDirectories(good.resolve("scripts/sub"));

            // namedmd：只有 <目录名>.md
            Path named = mkSkill(skills, "namedmd");
            write(named.resolve("namedmd.md"), "---\ndescription: named md\n---\n");
            write(named.resolve("scripts/tool.js"), "console.log(1)\n");

            // mddir：只有 {configDir}/md/<目录名>.md
            Path mddir = mkSkill(skills, "mddir");
            write(md.resolve("mddir.md"), "---\ndescription: md dir\n---\n");
            write(mddir.resolve("scripts/run.sh"), "echo 1\n");

            // fnmd：只有 <skillName>.md
            Path fn = mkSkill(skills, "fnmd");
            write(fn.resolve("custom_fn.md"), "---\ndescription: fn md\n---\n");
            write(fn.resolve("scripts/run.py"), "print(1)\n");

            // badmd：SKILL.md 无 frontmatter description（不能回退到 namedmd.md）
            Path bad = mkSkill(skills, "badmd");
            write(bad.resolve("SKILL.md"), "# no frontmatter\n");
            write(bad.resolve("badmd.md"), "---\ndescription: fallback\n---\n");
            write(bad.resolve("scripts/run.py"), "print(1)\n");

            // empty：有脚本但无任何描述文件
            Path empty = mkSkill(skills, "empty");
            write(empty.resolve("scripts/run.py"), "print(1)\n");

            // noscripts：有 scripts 目录但里面没有脚本
            Path noscripts = mkSkill(skills, "noscripts");

            testScripts(good, noscripts);
            testInterpreter();
            testDescriptions(config, good, named, mddir, fn, bad, empty);

            System.out.println("\n结果: " + pass + " 通过, " + fail + " 失败");
            if (fail > 0) System.exit(1);
        } finally {
            deleteRecursively(root);
        }
    }

    static void testScripts(Path good, Path empty) throws Exception {
        List<Path> scripts = TLToolManager.findUsableScripts(good.resolve("scripts"));
        check("只列受支持脚本（py 1 个，txt/子目录忽略）",
                scripts.size() == 1 && scripts.get(0).getFileName().toString().equals("run.py"));
        check("空 scripts 目录无可用脚本",
                TLToolManager.findUsableScripts(empty.resolve("scripts")).isEmpty());
        check("scripts 目录不存在无可用脚本",
                TLToolManager.findUsableScripts(empty.resolve("nope")).isEmpty());
    }

    static void testInterpreter() {
        check("py → python", "python".equals(TLToolManager.interpreterFor(Path.of("a.py"))));
        check("sh → bash", "bash".equals(TLToolManager.interpreterFor(Path.of("a.sh"))));
        check("mjs → node", "node".equals(TLToolManager.interpreterFor(Path.of("a.mjs"))));
        check("rb → ruby", "ruby".equals(TLToolManager.interpreterFor(Path.of("a.rb"))));
        check("未知扩展名 → python", "python".equals(TLToolManager.interpreterFor(Path.of("a.unknown"))));
        check("null → python", "python".equals(TLToolManager.interpreterFor(null)));
    }

    static void testDescriptions(Path config, Path good, Path named, Path mddir,
                                 Path fn, Path bad, Path empty) {
        check("SKILL.md 通过",
                TLToolManager.validateSkillDescription(good, config, "script_execution", null) == null);
        check("<目录名>.md 通过",
                TLToolManager.validateSkillDescription(named, config, "script_execution", null) == null);
        check("md/<目录名>.md 通过",
                TLToolManager.validateSkillDescription(mddir, config, "script_execution", null) == null);
        check("<skillName>.md 通过",
                TLToolManager.validateSkillDescription(fn, config, "custom_fn", null) == null);
        check("显式 skillDescription 直接通过",
                TLToolManager.validateSkillDescription(empty, config, "script_execution", "explicit desc") == null);

        String badErr = TLToolManager.validateSkillDescription(bad, config, "script_execution", null);
        check("SKILL.md 无 description 报错（不回退同名 md）",
                badErr != null && badErr.contains("frontmatter description"));
        String emptyErr = TLToolManager.validateSkillDescription(empty, config, "script_execution", null);
        check("无描述文件报错",
                emptyErr != null && emptyErr.contains("缺少描述文件"));
        check("技能目录为空报错",
                TLToolManager.validateSkillDescription(null, config, "x", null) != null);
    }

    static Path mkSkill(Path skills, String name) throws IOException {
        Path dir = skills.resolve(name);
        Files.createDirectories(dir.resolve("scripts"));
        return dir;
    }

    static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }

    static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name);
        }
    }

    static void deleteRecursively(Path p) throws IOException {
        if (p == null || !Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
