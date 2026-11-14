package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.aiagent.TLUserWorkspace;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * view_image 路径自测（runnable main，无 JUnit——仓库惯例）：
 * workspace 图片可读、旧 data 绝对路径迁移后回退、跨用户/外部拒绝。
 * 失败退出码 1。
 */
public class ViewImagePathSelfTest extends TLViewImageSkill implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public ViewImagePathSelfTest() { super(); }

    @Override public void putLog(String content, LogLevel logLevel) { }
    @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }

    static TLMsg call(ViewImagePathSelfTest s, String uid, String path) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("path", path);
        TLMsg m = new TLMsg().setParam(AI_P_SKILLINPUT, input)
                .setSystemParam(AI_P_USERID, uid)
                .setSystemParam(AI_P_SESSIONID, "s1");
        return s.execute(null, m);
    }

    static boolean ok(TLMsg r) { return r.parseBoolean(RESULT, false); }

    static String imagePath(TLMsg r) {
        try {
            JsonObject o = new JsonParser().parse(r.getStringParam(AI_P_SKILLOUTPUT, "")).getAsJsonObject();
            return o.get("image_path").getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("tlviewimg");
        String old = System.getProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
        try {
            System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, tmp.toString());
            ViewImagePathSelfTest s = new ViewImagePathSelfTest();

            Path wsImg = tmp.resolve("alice/uploads/pic.png");
            Files.createDirectories(wsImg.getParent());
            Files.write(wsImg, new byte[]{1});
            TLMsg okWs = call(s, "alice", wsImg.toString());
            check("workspace 图片可读", ok(okWs));
            check("返回调用方路径", wsImg.toString().equals(imagePath(okWs)));

            // 旧 data 绝对路径：文件已迁到 workspace，按文件名回退
            String legacyUid = "tlviewimg-" + System.nanoTime();
            Path legacyUser = Paths.get("data").toAbsolutePath().normalize().resolve(legacyUid);
            Path legacyFile = legacyUser.resolve("uploads/old.png");
            Files.createDirectories(legacyFile.getParent());
            Files.write(legacyFile, new byte[]{2});
            Path moved = tmp.resolve(legacyUid).resolve("uploads/old.png");
            Files.createDirectories(moved.getParent());
            Files.write(moved, new byte[]{3});
            Files.delete(legacyFile);
            try {
                TLMsg okLegacy = call(s, legacyUid, legacyFile.toString());
                check("旧绝对路径迁移后仍可读", ok(okLegacy));
                check("回退时保留调用方路径（附件白名单可比对）",
                        legacyFile.toString().equals(imagePath(okLegacy)));
            } finally {
                deleteRecursively(legacyUser);
            }

            Path other = tmp.resolve("bob/uploads/x.png");
            Files.createDirectories(other.getParent());
            Files.write(other, new byte[]{4});
            check("跨用户图片拒绝", !ok(call(s, "alice", other.toString())));

            Path outside = tmp.resolve("outside.png");
            Files.write(outside, new byte[]{5});
            check("外部图片拒绝", !ok(call(s, "alice", outside.toString())));

            // 裸文件名兜底：管理页可把图片传到任意子目录，用户/模型只报文件名
            Path deepImg = tmp.resolve("alice/docs/deep/only.png");
            Files.createDirectories(deepImg.getParent());
            Files.write(deepImg, new byte[]{6});
            TLMsg okName = call(s, "alice", "only.png");
            check("裸文件名唯一时按 workspace 搜索", ok(okName));
            check("回退时返回 workspace 相对路径",
                    "docs/deep/only.png".equals(imagePath(okName)));

            Files.write(tmp.resolve("alice/docs/dup.png"), new byte[]{7});
            Files.write(tmp.resolve("alice/docs/deep/dup.png"), new byte[]{8});
            TLMsg dup = call(s, "alice", "dup.png");
            check("多个同名文件拒绝并提示用相对路径", !ok(dup)
                    && dup.getStringParam(AI_P_SKILLOUTPUT, "").contains("multiple files"));
        } finally {
            if (old == null) System.clearProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
            else System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, old);
            deleteRecursively(tmp);
        }
        System.out.println("ViewImagePathSelfTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
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
