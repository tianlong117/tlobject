package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 图片注入白名单自测（runnable main，无 JUnit——仓库惯例）：
 * view_image 的 image_path 不再只认"本会话已登记附件"——本用户 workspace 内的既存文件
 * 也放行（管理页上传/工具产物）；data 等其他位置仍必须已登记。
 * 失败退出码 1。
 */
public class AttachmentWorkspaceImageSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    /** 只覆盖 putMsg/putLog：模拟 attachmentStore.register 返回 ref */
    static class TestAgent extends TLAiAgent {
        String refPath;
        TestAgent() {
            super("attachWsImgSelfTest");
            gson = new Gson();
        }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }
        @Override public TLMsg putMsg(String moduleName, TLMsg msg) {
            if (refPath == null) return null;   // 模拟模块缺失 → 不注入
            TLMsg r = createMsg().setParam(RESULT, true);
            r.setParam("ref", TLAttachmentRef.forImage(refPath, "pic.png", "image/png", "tool"));
            return r;
        }
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("tlattach-wsimg");
        Path wsRoot = tmp.resolve("workspace");
        String old = System.getProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
        try {
            System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, wsRoot.toString());
            Path wsImg = wsRoot.resolve("alice/uploads/pic.png");
            write(wsImg, "IMG");
            Path outside = tmp.resolve("outside.png");
            write(outside, "OUT");
            Path dataImg = tmp.resolve("data/alice/attachments/auto/aa/aa.png");
            write(dataImg, "DATA");

            List<TLConversationHistory> empty = new ArrayList<>();
            check("workspace 相对路径放行（管理页上传件）",
                    AttachmentPipeline.isAllowedImagePath(empty, "uploads/pic.png", "alice"));
            check("workspace 绝对路径放行",
                    AttachmentPipeline.isAllowedImagePath(empty, wsImg.toString(), "alice"));
            check("workspace 外路径拒绝",
                    !AttachmentPipeline.isAllowedImagePath(empty, outside.toString(), "alice"));
            check("workspace 内不存在的文件拒绝",
                    !AttachmentPipeline.isAllowedImagePath(empty, "uploads/missing.png", "alice"));
            check("../ 越界拒绝",
                    !AttachmentPipeline.isAllowedImagePath(empty, "../bob/uploads/pic.png", "alice"));
            check("data 下未登记附件拒绝",
                    !AttachmentPipeline.isAllowedImagePath(empty, dataImg.toString(), "alice"));

            List<TLConversationHistory> history = new ArrayList<>();
            TLConversationHistory h = new TLConversationHistory(TLConversationHistory.Role.user, "hi");
            h.setAttachments(new ArrayList<>(List.of(
                    TLAttachmentRef.forImage(dataImg.toString(), "aa.png", "image/png", "user"))));
            history.add(h);
            check("data 下已登记附件放行",
                    AttachmentPipeline.isAllowedImagePath(history, dataImg.toString(), "alice"));

            // 端到端：extractDeclaredImages 的注入链路（旧实现会因白名单拒绝返回 null）
            TestAgent agent = new TestAgent();
            agent.refPath = wsImg.toAbsolutePath().toString();
            AttachmentPipeline pipeline = new AttachmentPipeline(agent);
            String output = "{\"image_for_model\":true,\"image_path\":\"uploads/pic.png\",\"ok\":true}";
            List<TLAttachmentRef> refs = pipeline.extractDeclaredImages(output, "alice", empty);
            check("view_image 的 workspace 图片可注入模型",
                    refs != null && refs.size() == 1
                            && wsImg.toAbsolutePath().toString().equals(refs.get(0).getPath()));

            String outsideOut = "{\"image_for_model\":true,\"image_path\":\""
                    + outside.toString().replace("\\", "\\\\") + "\",\"ok\":true}";
            check("workspace 外的图片仍被拒绝注入",
                    pipeline.extractDeclaredImages(outsideOut, "alice", empty) == null);
        } finally {
            if (old == null) System.clearProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
            else System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, old);
            deleteRecursively(tmp);
        }
        System.out.println("AttachmentWorkspaceImageSelfTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void write(Path p, String s) throws Exception {
        Files.createDirectories(p.getParent());
        Files.write(p, s.getBytes(StandardCharsets.UTF_8));
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }

    private static void deleteRecursively(Path p) {
        try {
            if (Files.isDirectory(p)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                    for (Path c : ds) deleteRecursively(c);
                }
            }
            Files.deleteIfExists(p);
        } catch (Exception ignored) { }
    }
}
