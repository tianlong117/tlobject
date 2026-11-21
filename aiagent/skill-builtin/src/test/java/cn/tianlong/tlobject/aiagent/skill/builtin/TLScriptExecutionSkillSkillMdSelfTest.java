package cn.tianlong.tlobject.aiagent.skill.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 脚本技能 SKILL.md 自动发现自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>背景：installer 生成 skills/&lt;dir&gt;/SKILL.md，但旧 loader 只查
 * &lt;skillName&gt;.md，SKILL.md 从来不会进入 skillDescription。
 * 现在的优先级：SKILL.md → &lt;模块名&gt;.md → &lt;skillName&gt;.md。</p>
 */
public class TLScriptExecutionSkillSkillMdSelfTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("tlskillmd");
        try {
            Path scripts = root.resolve("skills/testskill/scripts");
            Files.createDirectories(scripts);
            Path skillDir = root.resolve("skills/testskill");

            write(skillDir.resolve("SKILL.md"), "---\ndescription: skill md\n---\n");
            write(skillDir.resolve("testskill.md"), "---\ndescription: named md\n---\n");
            write(skillDir.resolve("script_execution.md"), "---\ndescription: fn md\n---\n");
            check("SKILL.md 优先", "SKILL.md".equals(fileName(
                    TLScriptExecutionSkill.discoverSkillMd(scripts, "testskill", "script_execution"))));

            Files.delete(skillDir.resolve("SKILL.md"));
            check("<模块名>.md 次之", "testskill.md".equals(fileName(
                    TLScriptExecutionSkill.discoverSkillMd(scripts, "testskill", "script_execution"))));

            Files.delete(skillDir.resolve("testskill.md"));
            check("<skillName>.md 兜底", "script_execution.md".equals(fileName(
                    TLScriptExecutionSkill.discoverSkillMd(scripts, "testskill", "script_execution"))));

            Files.delete(skillDir.resolve("script_execution.md"));
            check("都没有返回 null",
                    TLScriptExecutionSkill.discoverSkillMd(scripts, "testskill", "script_execution") == null);
            check("scriptsDir 为 null 返回 null",
                    TLScriptExecutionSkill.discoverSkillMd(null, "testskill", "script_execution") == null);

            System.out.println("\n结果: " + pass + " 通过, " + fail + " 失败");
            if (fail > 0) System.exit(1);
        } finally {
            deleteRecursively(root);
        }
    }

    static String fileName(Path p) {
        return p == null || p.getFileName() == null ? "" : p.getFileName().toString();
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
