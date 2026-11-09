package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * TLAttachmentStore 上传件自测（runnable main，无 JUnit——仓库惯例）：
 * 启动迁移（保结构/幂等/冲突不覆盖）、register 边界（workspace+data）、cleanup 不碰上传件。
 * 失败退出码 1。
 */
public class TLAttachmentStoreUploadsSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    /** 静默日志 + 跳过 params 注入，字段由测试反射设置 */
    static class Store extends TLAttachmentStore {
        Store(String name) { super(name); }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }
        @Override protected void setModuleParams() { }
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("tlattach-uploads");
        Path dataRoot = tmp.resolve("data");
        Path wsRoot = tmp.resolve("workspace");
        String old = System.getProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
        try {
            System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, wsRoot.toString());
            Store store = new Store("attachUploadsSelfTest");
            setField(store, "dataBasePath", dataRoot.toString() + File.separator);

            // 1) 迁移：保结构、删空目录、幂等
            write(dataRoot.resolve("alice/uploads/a.txt"), "A");
            write(dataRoot.resolve("alice/uploads/sub/b.txt"), "B");
            store.runStartMsg();
            check("迁移 a.txt", Files.isRegularFile(wsRoot.resolve("alice/uploads/a.txt")));
            check("迁移子目录 b.txt", Files.isRegularFile(wsRoot.resolve("alice/uploads/sub/b.txt")));
            check("旧目录已清理", !Files.exists(dataRoot.resolve("alice/uploads")));
            store.runStartMsg();
            check("重复启动幂等", Files.isRegularFile(wsRoot.resolve("alice/uploads/a.txt")));

            // 2) 冲突：目标已存在时不覆盖，源保留
            write(dataRoot.resolve("bob/uploads/c.txt"), "OLD");
            write(wsRoot.resolve("bob/uploads/c.txt"), "NEW");
            store.runStartMsg();
            check("冲突保留目标", "NEW".equals(read(wsRoot.resolve("bob/uploads/c.txt"))));
            check("冲突保留源", Files.isRegularFile(dataRoot.resolve("bob/uploads/c.txt")));

            // 3) register 边界：workspace 接受、他人 workspace 拒绝、旧 data 接受
            Path wsFile = wsRoot.resolve("alice/uploads/a.txt");
            TLMsg ok = store.getMsg(null, new TLMsg().setAction(ATTACH_REGISTER)
                    .setParam("config", Map.of("path", wsFile.toString(), "name", "a.txt"))
                    .setParam(AI_P_USERID, "alice"));
            check("register 接受 workspace 上传件", Boolean.TRUE.equals(ok.getParam(RESULT)));

            Path bobFile = wsRoot.resolve("bob/uploads/c.txt");
            TLMsg cross = store.getMsg(null, new TLMsg().setAction(ATTACH_REGISTER)
                    .setParam("config", Map.of("path", bobFile.toString(), "name", "c.txt"))
                    .setParam(AI_P_USERID, "alice"));
            check("register 拒绝他人 workspace",
                    Boolean.FALSE.equals(cross.getParam(RESULT))
                            && String.valueOf(cross.getParam("error")).contains("越界"));

            Path legacyFile = dataRoot.resolve("carol/uploads/d.txt");
            write(legacyFile, "D");
            TLMsg legacy = store.getMsg(null, new TLMsg().setAction(ATTACH_REGISTER)
                    .setParam("config", Map.of("path", legacyFile.toString(), "name", "d.txt"))
                    .setParam(AI_P_USERID, "carol"));
            check("register 接受旧 data 上传件", Boolean.TRUE.equals(legacy.getParam(RESULT)));

            // 4) cleanup 不删除 workspace 上传件（retentionDays=-1 让所有 data 附件都过期）
            setField(store, "retentionDays", -1);
            store.getMsg(null, new TLMsg().setAction(ATTACH_CLEANUP).setParam(AI_P_USERID, "alice"));
            check("cleanup 不删除 workspace 上传件", Files.isRegularFile(wsFile));
        } finally {
            if (old == null) System.clearProperty(TLUserWorkspace.P_WORKSPACE_ROOT);
            else System.setProperty(TLUserWorkspace.P_WORKSPACE_ROOT, old);
            deleteRecursively(tmp);
        }
        System.out.println("TLAttachmentStoreUploadsSelfTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void write(Path p, String s) throws Exception {
        Files.createDirectories(p.getParent());
        Files.write(p, s.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }

    private static void setField(Object o, String name, Object value) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(o, value);
                return;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
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
