package cn.tianlong.java.demo.aiagent;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLAppStartUp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测门禁 CLI：无控制台跑"测试层 → 评测层"，用退出码表达结果。
 *
 * <pre>
 * EvalGateCli -d &lt;配置目录&gt; [-m &lt;工厂配置&gt;] [--timeout &lt;总超时秒数，默认 900&gt;]
 * </pre>
 *
 * 退出码：
 *   0 = 两层都通过
 *   1 = 门禁不通过（测试层有失败，或评测门禁没过）
 *   2 = 启动或执行出错（跑不起来、门禁未配置、消息无响应、超时）
 *
 * 1 与 2 分开是刻意的：脚本需要区分"代码坏了"和"质量退化了"，处置方式不同。
 *
 * --timeout 是整条命令的总预算，启动即武装；进入关闭阶段会改写成一个独立的 60 秒预算
 * （关闭本来就该很快），此时超时用已判定的退出码退出。
 *
 * 顺序刻意是"先测试层（mock、免费）再评测层（真实 LLM、花钱）"——管线断了就没必要花 token。
 *
 * 创建日期：2026/9/19
 * 作者:tianlong
 */
public class EvalGateCli extends TLAppStartUp {

    private static final int EXIT_PASS = 0;
    private static final int EXIT_GATE_FAIL = 1;
    private static final int EXIT_ERROR = 2;

    public EvalGateCli(String name) { super(name); }

    public static void main(String[] args) {
        Map<String, String> opts = parseCliArgs(args);
        if (opts.containsKey("help") || !opts.containsKey("d")) {
            printUsage();
            System.exit(opts.containsKey("help") ? EXIT_PASS : EXIT_ERROR);
            return;
        }
        long timeoutSec = parseTimeout(opts.get("timeout"));

        EvalGateCli app = new EvalGateCli("evalGateCli");
        try {
            HashMap<String, String> argsMap = new HashMap<>();
            argsMap.put("appName", "evalGateCli");
            argsMap.put("configPath", opts.get("d"));
            argsMap.put("factoryConfigFile", opts.getOrDefault("m", "moduleFactory_config.xml"));
            TLObjectFactory factory = app.startup(argsMap);
            // 总超时：评测层（真实 LLM）卡住时不能让进程一直挂着
            armWatchdog(timeoutSec);
            int code = runGate(app, factory);
            // 进入关闭阶段：换成较短的独立预算，且用已判定的退出码
            pendingCode.set(code);
            deadline.set(System.currentTimeMillis() + SHUTDOWN_GRACE_SEC * 1000);
            factory.shutdown(code);   // 必须用带参的那个：无参的硬编码 System.exit(0)
        } catch (Throwable t) {
            System.err.println("✗ 启动或执行出错: " + t);
            System.exit(EXIT_ERROR);
        }
    }

    /** 跑两层并给出退出码 */
    private static int runGate(EvalGateCli app, TLObjectFactory factory) {
        // ② 测试层：mock 驱动，免费。管线断了就没必要花 token 跑质量评测
        TLMsg testMsg = app.createMsg().setAction("test").setParam("caseName", "all");
        // 目标模块不存在时让 putMsg 返回空消息，而不是走它默认的 moduleFactory.shutdown(-1)
        // —— 配错一个模块名不该把 CLI 自己带走
        // 注意：必须用框架常量（值 "ignoreModuleIsNull"），systemArgs 是大小写敏感的直接查表，
        // 写成全大写字面量会静默查不到 → setSystemParam 白设 → shutdown(-1) 照样把 CLI 带走
        testMsg.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg testResult = factory.putMsg("agentService", testMsg);
        if (testResult == null || !testResult.containsParam("success")) {
            System.err.println("✗ 测试层无响应（agentService 未配置或未启动？）");
            return EXIT_ERROR;
        }
        if (!testResult.parseBoolean("success", false)) {
            System.out.println("✗ 测试层未通过: " + testResult.getStringParam("error", ""));
            return EXIT_GATE_FAIL;
        }
        System.out.println("✓ 测试层通过: " + testResult.getStringParam("message", ""));

        // ③ 评测层
        TLMsg evalMsg = app.createMsg().setAction("eval").setParam("subAction", "suite");
        evalMsg.setSystemParam(IGNOREMODULEISNULL, true);
        TLMsg evalResult = factory.putMsg("agentService", evalMsg);
        if (evalResult == null || !evalResult.containsParam("success")) {
            System.err.println("✗ 评测层无响应（agentService 未配置或未启动？）");
            return EXIT_ERROR;
        }
        if (!evalResult.parseBoolean("success", false)) {
            // 套件没跑起来（用例目录空、抛异常等）——算执行出错，不算门禁不通过
            System.err.println("✗ 评测未完成: " + evalResult.getStringParam("error", ""));
            return EXIT_ERROR;
        }
        System.out.println("✓ " + evalResult.getStringParam("message", ""));

        Object dataObj = evalResult.getParam("data");
        if (!(dataObj instanceof Map)) {
            System.err.println("✗ 评测没有返回门禁信息，无法判定");
            return EXIT_ERROR;
        }
        Map<?, ?> data = (Map<?, ?>) dataObj;

        // 门禁没配 → 判不了就是失败。只读 gatePassed 的话，没配门禁时它恒为 true，
        // 用例全挂也会退 0 —— 一个本该说真话的工具在说谎
        if (!"true".equals(String.valueOf(data.get("gateEnabled")))) {
            System.err.println("✗ 门禁未配置，本次无法判定能不能上。");
            System.err.println("  请在该应用的 evals_config.xml 里配置 gatePassRate / gateMaxRegressions 后重跑。");
            return EXIT_ERROR;
        }

        if ("true".equals(String.valueOf(data.get("gatePassed")))) {
            System.out.println("✓ 门禁通过");
            return EXIT_PASS;
        }
        System.out.println("✗ 门禁不通过:");
        Object failures = data.get("gateFailures");
        if (failures instanceof List) {
            for (Object f : (List<?>) failures) System.out.println("    - " + f);
        } else {
            System.out.println("    （没有给出原因，请查看评测报告）");
        }
        Object reportPath = data.get("reportPath");
        if (reportPath != null && !String.valueOf(reportPath).isEmpty()) {
            System.out.println("  报告: " + reportPath);
        }
        return EXIT_GATE_FAIL;
    }

    /** 看门狗截止时间（毫秒时间戳）。进入关闭阶段时会被改写成"关闭截止时间" */
    private static final java.util.concurrent.atomic.AtomicLong deadline = new java.util.concurrent.atomic.AtomicLong();
    /** 超时时用哪个退出码 */
    private static final java.util.concurrent.atomic.AtomicInteger pendingCode = new java.util.concurrent.atomic.AtomicInteger(EXIT_ERROR);
    /** 关闭阶段给的独立预算：它比主流程短，因为关闭本来就该很快 */
    private static final long SHUTDOWN_GRACE_SEC = 60;

    /**
     * 武装看门狗（只武装一次，之后靠改截止时间来切换阶段）。
     * 用 halt 而不是 exit —— exit 会去跑关闭钩子，而关不掉的原因恰恰可能是在钩子/销毁里自旋。
     */
    private static void armWatchdog(long seconds) {
        deadline.set(System.currentTimeMillis() + seconds * 1000);
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    return;
                }
                if (System.currentTimeMillis() >= deadline.get()) {
                    int code = pendingCode.get();
                    System.err.println("⚠ 超时，强制退出（退出码 " + code + "）");
                    Runtime.getRuntime().halt(code);
                }
            }
        }, "eval-gate-watchdog");
        t.setDaemon(true);
        t.start();
    }

    private static long parseTimeout(String v) {
        if (v == null || v.trim().isEmpty()) return 900;
        try {
            long n = Long.parseLong(v.trim());
            return n > 0 ? n : 900;
        } catch (NumberFormatException e) {
            System.err.println("--timeout 不是数字，用默认 900 秒");
            return 900;
        }
    }

    private static void printUsage() {
        System.out.println("用法: EvalGateCli -d <配置目录> [-m <工厂配置>] [--timeout <总超时秒数，默认 900>]");
        System.out.println("  顺序: 先跑测试层（mock，免费），过了再跑评测层（真实 LLM）");
        System.out.println("  退出码: 0=通过  1=门禁不通过  2=启动或执行出错（含超时、门禁未配置）");
    }

    /**
     * 不能叫 parseArgs：TLAppStartUp 已有 public static HashMap&lt;String,String&gt; parseArgs(String[])，
     * 子类同名方法既压不掉它的可见性、返回类型也不兼容（编译不过）。
     * 两者语义也不同——父类的那个是"位置参数 → 工厂启动参数"。
     */
    private static Map<String, String> parseCliArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("-h".equals(a) || "--help".equals(a)) m.put("help", "1");
            else if (("-d".equals(a) || "--config-dir".equals(a)) && i + 1 < args.length) m.put("d", args[++i]);
            else if (("-m".equals(a) || "--factory-config".equals(a)) && i + 1 < args.length) m.put("m", args[++i]);
            else if ("--timeout".equals(a) && i + 1 < args.length) m.put("timeout", args[++i]);
        }
        return m;
    }
}
