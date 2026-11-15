package cn.tianlong.tlobject.aiagent.webui;

import cn.tianlong.tlobject.aiagent.TLPathSafety;
import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 会话置顶的服务端存储（跨设备共享）。
 *
 * <p>落盘：{@code {baseDir}/{safeUserId}/webui_pinned.json}，内容为 sessionId 的 JSON 数组。
 * 写入用「临时文件 + 原子替换」；同一实例内同步，避免同一用户并发置顶互相覆盖。
 * 文件缺失/损坏按空集合处理（置顶是偏好，不因存储问题影响会话列表）。</p>
 */
public final class TLSessionPinStore {

    private static final Gson GSON = new Gson();
    private final Path baseDir;

    public TLSessionPinStore(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    /** 读取用户置顶集合（文件不存在/损坏返回空集合）。 */
    public Set<String> load(String userId) {
        Set<String> out = new LinkedHashSet<>();
        Path f = fileFor(userId);
        try {
            if (!Files.isRegularFile(f)) return out;
            String[] arr = GSON.fromJson(
                    new String(Files.readAllBytes(f), StandardCharsets.UTF_8), String[].class);
            if (arr != null)
                for (String s : arr)
                    if (s != null && !s.trim().isEmpty()) out.add(s);
        } catch (Exception ignored) {
            // 损坏/无权限：当作没有置顶，不影响会话列表
        }
        return out;
    }

    /** 置顶/取消置顶；返回是否保存成功。 */
    public boolean setPinned(String userId, String sessionId, boolean pinned) {
        if (sessionId == null || sessionId.trim().isEmpty()) return false;
        synchronized (this) {
            Set<String> pins = load(userId);
            boolean changed = pinned ? pins.add(sessionId) : pins.remove(sessionId);
            if (!changed && Files.isRegularFile(fileFor(userId))) return true;   // 已是目标状态
            return save(userId, pins);
        }
    }

    private boolean save(String userId, Set<String> pins) {
        Path f = fileFor(userId);
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName().toString() + ".tmp");
            Files.write(tmp, GSON.toJson(pins).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 文件路径（包内可见：测试与诊断用）；userId 经 safeSegment 防路径穿越。 */
    Path fileFor(String userId) {
        return baseDir.resolve(TLPathSafety.safeSegment(userId, "default"))
                .resolve("webui_pinned.json");
    }
}
