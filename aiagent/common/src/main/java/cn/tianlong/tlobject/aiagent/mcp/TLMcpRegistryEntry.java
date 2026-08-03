package cn.tianlong.tlobject.aiagent.mcp;

import java.util.Arrays;
import java.util.List;

/**
 * MCP Server registry entry — a resolved installation configuration.
 *
 * Holds everything needed to generate an XML &lt;agent&gt; block for TLMcpAgent.
 * Supports both curated index entries and auto-detected fallback entries.
 *
 * @author tianlong
 * @date 2026/8/3
 */
public class TLMcpRegistryEntry {

    private String packageName;      // e.g. "@modelcontextprotocol/server-filesystem"
    private String displayName;      // e.g. "Filesystem"
    private String description;      // e.g. "Secure file system access..."
    private String category;         // e.g. "developer-tools"
    private String runtime;          // "node", "python", "docker"
    private String command;          // e.g. "npx" or "uvx" or "docker"
    private List<String> args;       // e.g. ["-y", "@modelcontextprotocol/server-filesystem"]
    private String transport;        // "stdio" (default) or "sse"
    private String url;              // for SSE transport
    private String env;              // optional env vars hint (key=value)
    private List<String> tools;      // optional list of tool descriptions (e.g. "read_file - 读取文件")

    public TLMcpRegistryEntry() {
        this.transport = "stdio";
    }

    // ======================== Getters ========================

    public String getPackageName() { return packageName; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public String getRuntime() { return runtime; }
    public String getCommand() { return command; }
    public List<String> getArgs() { return args; }
    public String getTransport() { return transport; }
    public String getUrl() { return url; }
    public String getEnv() { return env; }
    public List<String> getTools() { return tools; }

    // ======================== Setters (fluent) ========================

    public TLMcpRegistryEntry setPackageName(String v) { this.packageName = v; return this; }
    public TLMcpRegistryEntry setDisplayName(String v) { this.displayName = v; return this; }
    public TLMcpRegistryEntry setDescription(String v) { this.description = v; return this; }
    public TLMcpRegistryEntry setCategory(String v) { this.category = v; return this; }
    public TLMcpRegistryEntry setRuntime(String v) { this.runtime = v; return this; }
    public TLMcpRegistryEntry setCommand(String v) { this.command = v; return this; }
    public TLMcpRegistryEntry setArgs(List<String> v) { this.args = v; return this; }
    public TLMcpRegistryEntry setTransport(String v) { this.transport = v; return this; }
    public TLMcpRegistryEntry setUrl(String v) { this.url = v; return this; }
    public TLMcpRegistryEntry setEnv(String v) { this.env = v; return this; }
    public TLMcpRegistryEntry setTools(List<String> v) { this.tools = v; return this; }

    // ======================== Factory methods ========================

    /**
     * Create an entry for an npm MCP server (npx-based).
     */
    public static TLMcpRegistryEntry npm(String packageName, String displayName,
                                          String description, String category) {
        TLMcpRegistryEntry e = new TLMcpRegistryEntry();
        e.packageName = packageName;
        e.displayName = displayName;
        e.description = description;
        e.category = category;
        e.runtime = "node";
        e.command = "npx";
        e.args = Arrays.asList("-y", packageName);
        e.transport = "stdio";
        return e;
    }

    /**
     * Create an entry for a Python MCP server (uvx-based).
     */
    public static TLMcpRegistryEntry python(String packageName, String displayName,
                                             String description, String category) {
        TLMcpRegistryEntry e = new TLMcpRegistryEntry();
        e.packageName = packageName;
        e.displayName = displayName;
        e.description = description;
        e.category = category;
        e.runtime = "python";
        e.command = "uvx";
        e.args = Arrays.asList(packageName);
        e.transport = "stdio";
        return e;
    }

    /**
     * Create an entry from auto-detection (best-guess fallback).
     */
    public static TLMcpRegistryEntry detected(String packageName, String command,
                                               List<String> args, String transport) {
        TLMcpRegistryEntry e = new TLMcpRegistryEntry();
        e.packageName = packageName;
        e.displayName = packageName; // best guess
        e.description = "Auto-detected MCP server: " + packageName;
        e.category = "auto-detected";
        e.runtime = command.contains("uvx") || command.contains("python") ? "python"
                   : command.contains("docker") ? "docker" : "node";
        e.command = command;
        e.args = args;
        e.transport = transport;
        return e;
    }
}
