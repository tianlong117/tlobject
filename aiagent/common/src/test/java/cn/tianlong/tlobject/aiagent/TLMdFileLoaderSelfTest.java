package cn.tianlong.tlobject.aiagent;

/**
 * SKILL.md / Agent md frontmatter 解析自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>背景：mermaid-flowchart/SKILL.md 使用 YAML 折叠块
 * {@code description: >-} + 后续缩进行；旧解析器只读 "description:" 同一行，
 * 返回空串，安装被误判"缺少 frontmatter description"。</p>
 */
public class TLMdFileLoaderSelfTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        testSingleLine();
        testFolded();
        testLiteral();
        testEmptyMarker();
        testCrlf();
        testMissing();
        System.out.println("\n结果: " + pass + " 通过, " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }

    static void testSingleLine() {
        check("单行 description",
                "hello".equals(TLMdFileLoader.parseFrontmatterDescription(
                        "---\ndescription: hello\n---\nbody")));
        check("引号包裹",
                "hello".equals(TLMdFileLoader.parseFrontmatterDescription(
                        "---\ndescription: \"hello\"\n---\n")));
        check("单引号包裹",
                "hello".equals(TLMdFileLoader.parseFrontmatterDescription(
                        "---\ndescription: 'hello'\n---\n")));
    }

    static void testFolded() {
        String md = "---\n"
                + "name: mermaid-flowchart\n"
                + "description: >-\n"
                + "  根据内容生成流程图。\n"
                + "  通过 mermaid-flowchart 工具调用。\n"
                + "---\n\n# body\n";
        String desc = TLMdFileLoader.parseFrontmatterDescription(md);
        check("折叠块 >- 非空", desc != null && !desc.isEmpty());
        check("折叠块合并后续缩进行",
                desc != null && desc.contains("根据内容生成流程图。")
                        && desc.contains("通过 mermaid-flowchart 工具调用。"));
        check("折叠块不包含正文", desc != null && !desc.contains("# body"));

        String gt = TLMdFileLoader.parseFrontmatterDescription(
                "---\ndescription: >\n  line1\n  line2\n---\n");
        check("折叠块 > 合并", "line1 line2".equals(gt));
    }

    static void testLiteral() {
        String desc = TLMdFileLoader.parseFrontmatterDescription(
                "---\ndescription: |\n  line1\n  line2\n---\n");
        check("字面量 | 保留换行", "line1\nline2".equals(desc));
    }

    static void testEmptyMarker() {
        String desc = TLMdFileLoader.parseFrontmatterDescription(
                "---\ndescription:\n  line1\n  line2\n---\n");
        check("description: 后直接缩进块", "line1 line2".equals(desc));
    }

    static void testCrlf() {
        String desc = TLMdFileLoader.parseFrontmatterDescription(
                "---\r\ndescription: >-\r\n  hello\r\n  world\r\n---\r\n");
        check("CRLF 折叠块", "hello world".equals(desc));
    }

    static void testMissing() {
        check("无 frontmatter", TLMdFileLoader.parseFrontmatterDescription("plain text") == null);
        check("无 description",
                TLMdFileLoader.parseFrontmatterDescription("---\nname: x\n---\n") == null);
        check("空 description 块",
                TLMdFileLoader.parseFrontmatterDescription("---\ndescription: >-\n---\n") == null);
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
}
