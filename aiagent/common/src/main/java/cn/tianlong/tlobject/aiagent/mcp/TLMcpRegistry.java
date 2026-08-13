package cn.tianlong.tlobject.aiagent.mcp;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * MCP server registry — resolves package names to launch configurations.
 *
 * Resolution strategy (layered):
 *   1. Curated local index ({@code mcp-registry.json}) — fast, offline, trustworthy
 *   2. npm registry search — live query for community MCP servers
 *   3. Auto-detection heuristics — best-guess fallback for unknown packages
 *
 * @author tianlong
 * @date 2026/8/3
 */
public class TLMcpRegistry {

    private static final String REGISTRY_RESOURCE = "cn/tianlong/tlobject/aiagent/mcp/mcp-registry.json";
    private static final String NPM_SEARCH_URL = "https://registry.npmjs.org/-/v1/search";
    private static final int NPM_SEARCH_SIZE = 30;
    private static final int NPM_TIMEOUT_MS = 8000;

    /** In-memory index: packageName → TLMcpRegistryEntry */
    private final Map<String, TLMcpRegistryEntry> index = new LinkedHashMap<>();

    /** npm 搜索结果缓存：keyword → entries（5 分钟有效） */
    private final Map<String, List<TLMcpRegistryEntry>> npmCache = new LinkedHashMap<>();
    private final Map<String, Long> npmCacheTime = new LinkedHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;

    private final Gson gson = new Gson();

    /** 日志宿主（调用方模块，统一走 putLog 而非 System.out） */
    private final TLBaseModule logOwner;

    public TLMcpRegistry(TLBaseModule logOwner) {
        this.logOwner = logOwner;
        loadBuiltinIndex();
    }

    /** 统一日志出口 */
    private void log(String msg, LogLevel level) {
        if (logOwner != null) logOwner.putLog(msg, level, "mcpRegistry");
    }

    // ======================== Public API ========================

    /**
     * Search the registry by keyword.
     * Local index first, then npm registry for community servers.
     *
     * @param keyword search term (case-insensitive)
     * @return matching entries, local first, sorted by relevance
     */
    public List<TLMcpRegistryEntry> search(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return new ArrayList<>(index.values());
        }
        String kw = keyword.toLowerCase().trim();
        List<TLMcpRegistryEntry> results = new ArrayList<>();
        List<TLMcpRegistryEntry> exactMatches = new ArrayList<>();

        for (TLMcpRegistryEntry e : index.values()) {
            String pkg = e.getPackageName().toLowerCase();
            if (pkg.equals(kw)) {
                exactMatches.add(e);
            } else if (pkg.contains(kw)
                    || (e.getDisplayName() != null && e.getDisplayName().toLowerCase().contains(kw))
                    || (e.getDescription() != null && e.getDescription().toLowerCase().contains(kw))
                    || (e.getCategory() != null && e.getCategory().toLowerCase().contains(kw))) {
                results.add(e);
            }
        }

        // Exact matches first, then fuzzy matches
        exactMatches.addAll(results);

        // 追加 npm 搜索结果（去重）
        Set<String> seen = new HashSet<>();
        for (TLMcpRegistryEntry e : exactMatches) {
            seen.add(e.getPackageName());
        }
        List<TLMcpRegistryEntry> npmResults = searchNpm(kw);
        for (TLMcpRegistryEntry e : npmResults) {
            if (!seen.contains(e.getPackageName())) {
                exactMatches.add(e);
            }
        }

        return exactMatches;
    }

    /**
     * Resolve a package identifier to its install configuration.
     *
     * Tries curated index first. Falls back to auto-detection for unknown packages.
     *
     * @param packageName e.g. "@modelcontextprotocol/server-filesystem" or "mcp-server-time"
     * @param extraArgs   optional extra arguments appended after the base args
     * @return resolved entry, or null if unresolvable
     */
    public TLMcpRegistryEntry resolve(String packageName, String... extraArgs) {
        if (packageName == null || packageName.trim().isEmpty()) return null;
        String pkg = packageName.trim();

        // 1. Look up in curated index
        TLMcpRegistryEntry entry = index.get(pkg);
        if (entry != null) {
            if (extraArgs != null && extraArgs.length > 0) {
                return mergeExtraArgs(entry, extraArgs);
            }
            return entry;
        }

        // 2. Fallback: auto-detect
        return autoDetect(pkg, extraArgs);
    }

    /**
     * List all entries in the curated index.
     */
    public Collection<TLMcpRegistryEntry> listAll() {
        return Collections.unmodifiableCollection(index.values());
    }

    /**
     * Get the number of entries in the curated index.
     */
    public int size() {
        return index.size();
    }

    /**
     * Fetch detailed info for a package from npm registry.
     *
     * @param packageName npm package name
     * @return map with keys: description, version, keywords, homepage, repository, readmeExcerpt
     */
    public Map<String, Object> fetchPackageDetail(String packageName) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 先检查精选索引（补充 tools 等信息）
        TLMcpRegistryEntry curated = index.get(packageName);
        if (curated != null) {
            if (curated.getDescription() != null) {
                result.put("description", curated.getDescription());
            }
            if (curated.getTools() != null && !curated.getTools().isEmpty()) {
                result.put("tools", curated.getTools());
            }
            if (curated.getEnv() != null) {
                result.put("env", curated.getEnv());
            }
        }

        // 从 npm registry 获取详情
        try {
            String urlStr = "https://registry.npmjs.org/" + packageName.replace("/", "%2F");
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(NPM_TIMEOUT_MS);
            conn.setReadTimeout(NPM_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "tlobject-mcp-registry/1.0");

            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return result.isEmpty() ? null : result;
            }

            try (Reader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> root = gson.fromJson(reader, Map.class);

                // 提取最新版本号（"dist-tags" is a map like {"latest": "1.0.0"}）
                String latestVersion = null;
                @SuppressWarnings("unchecked")
                Map<String, Object> distTags = (Map<String, Object>) root.get("dist-tags");
                if (distTags != null) {
                    latestVersion = (String) distTags.get("latest");
                    if (latestVersion != null) {
                        result.put("version", latestVersion);
                    }
                }

                // versions → latest → description / keywords / homepage / repository
                if (latestVersion != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> versions = (Map<String, Object>) root.get("versions");
                    if (versions != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> versionInfo = (Map<String, Object>) versions.get(latestVersion);
                        if (versionInfo != null) {
                            if (!result.containsKey("description")) {
                                String desc = (String) versionInfo.get("description");
                                if (desc != null) result.put("description", desc);
                            }
                            // keywords
                            @SuppressWarnings("unchecked")
                            List<String> kwList = (List<String>) versionInfo.get("keywords");
                            if (kwList != null && !kwList.isEmpty()) {
                                result.put("keywords", String.join(", ", kwList));
                            }
                            if (versionInfo.get("homepage") != null) {
                                result.put("homepage", versionInfo.get("homepage"));
                            }
                            // repository
                            @SuppressWarnings("unchecked")
                            Map<String, Object> repo = (Map<String, Object>) versionInfo.get("repository");
                            if (repo != null && repo.get("url") != null) {
                                String repoUrl = repo.get("url").toString()
                                        .replace("git+", "")
                                        .replace(".git", "");
                                result.put("repository", repoUrl);
                            }
                        }
                    }
                }

                // README (from root, not version-specific)
                String readme = (String) root.get("readme");
                if (readme != null && !readme.isEmpty()) {
                    result.put("readmeExcerpt", extractReadmeExcerpt(readme));
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            log("fetch detail failed for " + packageName + ": " + e.getMessage(), LogLevel.ERROR);
        }

        return result.isEmpty() ? null : result;
    }

    /**
     * Extract a brief excerpt from README content (first meaningful paragraphs).
     */
    private String extractReadmeExcerpt(String readme) {
        if (readme == null || readme.isEmpty()) return null;
        // 取前 8 行非空、非标题行（去 # 标记）
        StringBuilder sb = new StringBuilder();
        int lineCount = 0;
        for (String line : readme.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (sb.length() > 0 && !sb.toString().endsWith("\n")) {
                    // skip empty lines between paragraphs
                }
                continue;
            }
            // 跳过徽章行和纯标题行
            if (trimmed.startsWith("#") || trimmed.startsWith("[!")
                    || trimmed.startsWith("<img") || trimmed.startsWith("<p align")
                    || trimmed.startsWith("---") || trimmed.startsWith("===")) {
                continue;
            }
            // 去掉 markdown 链接格式但保留文字
            trimmed = trimmed.replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1");
            // 去掉 markdown 加粗/斜体
            trimmed = trimmed.replaceAll("[*_]{1,3}([^*_]+)[*_]{1,3}", "$1");
            sb.append(trimmed).append("\n");
            lineCount++;
            if (lineCount >= 8) break;
        }
        return sb.toString().trim();
    }

    // ======================== npm Search ========================

    /**
     * Search npm registry for MCP servers.
     * Queries npm with combined keywords: "mcp" + user keyword.
     * Results are auto-detected into TLMcpRegistryEntry (not curated).
     */
    private List<TLMcpRegistryEntry> searchNpm(String keyword) {
        // Check cache
        if (npmCache.containsKey(keyword)) {
            long age = System.currentTimeMillis() - npmCacheTime.getOrDefault(keyword, 0L);
            if (age < CACHE_TTL_MS) {
                return npmCache.get(keyword);
            }
        }

        List<TLMcpRegistryEntry> results = new ArrayList<>();

        try {
            // 构造搜索词: "mcp <keyword>" → 优先搜到 MCP 相关包
            String query = keyword.contains("mcp") ? keyword : "mcp " + keyword;
            String urlStr = NPM_SEARCH_URL + "?text=" + java.net.URLEncoder.encode(query, "UTF-8")
                    + "&size=" + NPM_SEARCH_SIZE;
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(NPM_TIMEOUT_MS);
            conn.setReadTimeout(NPM_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            // npm 搜索 API 也可能限制未设置 User-Agent 的请求
            conn.setRequestProperty("User-Agent", "tlobject-mcp-registry/1.0");

            int code = conn.getResponseCode();
            if (code != 200) {
                log("npm search returned HTTP " + code, LogLevel.WARN);
                conn.disconnect();
                return results;
            }

            try (Reader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> root = gson.fromJson(reader, Map.class);
                List<Map<String, Object>> objects = (List<Map<String, Object>>) root.get("objects");
                if (objects != null) {
                    for (Map<String, Object> obj : objects) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> pkg = (Map<String, Object>) obj.get("package");
                        if (pkg == null) continue;

                        String name = (String) pkg.get("name");
                        String description = (String) pkg.get("description");
                        String version = (String) pkg.get("version");

                        // 只保留 MCP 相关的包（名或描述含 mcp）
                        if (name == null) continue;
                        String lowerName = name.toLowerCase();
                        String lowerDesc = description != null ? description.toLowerCase() : "";
                        boolean isMcp = lowerName.contains("mcp") || lowerDesc.contains("mcp");
                        if (!isMcp) continue;

                        // 过滤掉明显不是 MCP server 的包（如 mcp-client-only, mcp-inspector 等）
                        if (lowerName.contains("client") || lowerName.contains("inspector")
                                || lowerName.contains("proxy") || lowerName.contains("gateway")) {
                            continue;
                        }

                        // 用 autoDetect 生成配置
                        TLMcpRegistryEntry entry = autoDetect(name);
                        // 用 npm 的描述覆盖默认描述
                        if (description != null && !description.isEmpty()) {
                            entry.setDescription(description);
                        }
                        entry.setCategory("npm-community");
                        results.add(entry);
                    }
                }
            }
            conn.disconnect();

            log("npm search '" + keyword + "' returned " + results.size() + " results", LogLevel.INFO);
        } catch (Exception e) {
            log("npm search failed: " + e.getMessage(), LogLevel.ERROR);
            // 网络不可用时静默降级，只返回本地结果
        }

        // 缓存结果
        npmCache.put(keyword, results);
        npmCacheTime.put(keyword, System.currentTimeMillis());

        return results;
    }

    // ======================== Private ========================

    /**
     * Load the built-in curated index from classpath JSON resource.
     */
    private void loadBuiltinIndex() {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = TLMcpRegistry.class.getClassLoader();
            InputStream is = cl.getResourceAsStream(REGISTRY_RESOURCE);
            if (is == null) {
                log("registry resource not found: " + REGISTRY_RESOURCE, LogLevel.WARN);
                return;
            }
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                Type type = new TypeToken<Map<String, List<TLMcpRegistryEntry>>>() {}.getType();
                Map<String, List<TLMcpRegistryEntry>> root = gson.fromJson(reader, type);
                List<TLMcpRegistryEntry> servers = root.get("servers");
                if (servers != null) {
                    for (TLMcpRegistryEntry entry : servers) {
                        if (entry.getPackageName() != null) {
                            if (entry.getArgs() != null && !(entry.getArgs() instanceof ArrayList)) {
                                entry.setArgs(new ArrayList<>(entry.getArgs()));
                            }
                            index.put(entry.getPackageName(), entry);
                        }
                    }
                }
            }
            log("Loaded " + index.size() + " MCP servers from curated index", LogLevel.INFO);
        } catch (Exception e) {
            log("ERROR loading registry: " + e, LogLevel.ERROR);
        }
    }

    /**
     * Merge extra args into a curated entry (returns a new entry, does not mutate the original).
     */
    private TLMcpRegistryEntry mergeExtraArgs(TLMcpRegistryEntry base, String... extraArgs) {
        TLMcpRegistryEntry merged = new TLMcpRegistryEntry()
                .setPackageName(base.getPackageName())
                .setDisplayName(base.getDisplayName())
                .setDescription(base.getDescription())
                .setCategory(base.getCategory())
                .setRuntime(base.getRuntime())
                .setCommand(base.getCommand())
                .setTransport(base.getTransport())
                .setUrl(base.getUrl())
                .setEnv(base.getEnv())
                .setTools(base.getTools());

        List<String> mergedArgs = new ArrayList<>(base.getArgs());
        mergedArgs.addAll(Arrays.asList(extraArgs));
        merged.setArgs(mergedArgs);
        return merged;
    }

    /**
     * Auto-detect a package's runtime and generate a best-guess config.
     *
     * Heuristics:
     *   - Starts with "@" or contains "/" but not ".py"/".whl": assume npm, command=npx
     *   - Starts with "mcp-server-" and no "@": assume python, command=uvx
     *   - Contains "ghcr.io" or "docker": assume docker
     *   - Default: try npx
     */
    private TLMcpRegistryEntry autoDetect(String packageName, String... extraArgs) {
        String pkg = packageName.trim();
        String command;
        String runtime;
        List<String> args = new ArrayList<>();

        if (pkg.contains("ghcr.io") || pkg.toLowerCase().startsWith("docker")) {
            command = "docker";
            runtime = "docker";
            args.add("run");
            args.add("-i");
            args.add("--rm");
            args.add(pkg);
        } else if ((pkg.startsWith("mcp-server-") || pkg.startsWith("mcp_"))
                && !pkg.startsWith("@")) {
            command = "uvx";
            runtime = "python";
            args.add(pkg);
        } else if (pkg.startsWith("@") || (pkg.contains("/") && !pkg.endsWith(".py") && !pkg.endsWith(".whl"))) {
            command = "npx";
            runtime = "node";
            args.add("-y");
            args.add(pkg);
        } else {
            command = "npx";
            runtime = "node";
            args.add("-y");
            args.add(pkg);
        }

        if (extraArgs != null && extraArgs.length > 0) {
            args.addAll(Arrays.asList(extraArgs));
        }

        return TLMcpRegistryEntry.detected(pkg, command, args, "stdio");
    }
}
