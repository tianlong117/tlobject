package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能入参 schema 校验自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>覆盖 required/type/enum/integer/range/array/boolean；string 兼容 Map/List
 * （board 等技能接受 JSON 对象）；可选字段缺失、null 输入；合法输入返回 null。</p>
 */
public class TLBaseSkillValidateInputSelfTest extends TLBaseSkill {

    private static int passed = 0, failed = 0;

    public TLBaseSkillValidateInputSelfTest() { super("schemaValidateTest"); }

    @Override protected TLMsg execute(Object fromWho, TLMsg msg) {
        return createMsg().setParam(RESULT, true);
    }

    public static void main(String[] args) {
        TLBaseSkillValidateInputSelfTest s = new TLBaseSkillValidateInputSelfTest();
        s.parameterSchema = schema();

        check("合法输入通过", s.validateInput(valid()) == null);
        check("缺必填参数", contains(s.validateInput(new LinkedHashMap<>()), "缺少必填参数"));
        check("null 输入按缺必填", contains(s.validateInput(null), "缺少必填参数"));

        Map<String, Object> badEnum = valid();
        badEnum.put("operation", "delete");
        check("enum 不合法", contains(s.validateInput(badEnum), "取值必须"));

        Map<String, Object> badInt = valid();
        badInt.put("count", 1.5);
        check("integer 拒绝小数", contains(s.validateInput(badInt), "integer"));

        Map<String, Object> tooSmall = valid();
        tooSmall.put("count", 0);
        check("minimum 生效", contains(s.validateInput(tooSmall), "不能小于"));

        Map<String, Object> tooLarge = valid();
        tooLarge.put("count", 11);
        check("maximum 生效", contains(s.validateInput(tooLarge), "不能大于"));

        Map<String, Object> badBool = valid();
        badBool.put("flag", "yes");
        check("boolean 类型", contains(s.validateInput(badBool), "boolean"));

        Map<String, Object> badArray = valid();
        badArray.put("tags", "a");
        check("array 类型", contains(s.validateInput(badArray), "array"));

        Map<String, Object> optionalMissing = valid();
        optionalMissing.remove("value");
        check("可选字段缺失通过", s.validateInput(optionalMissing) == null);

        Map<String, Object> objectAsString = valid();
        objectAsString.put("value", Map.of("k", 1));
        check("string 兼容 JSON 对象", s.validateInput(objectAsString) == null);

        Map<String, Object> listAsString = valid();
        listAsString.put("value", List.of("a", "b"));
        check("string 兼容 JSON 数组", s.validateInput(listAsString) == null);

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static Map<String, Object> valid() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("operation", "read");
        in.put("count", 2);
        in.put("flag", true);
        in.put("tags", new ArrayList<>(List.of("a")));
        in.put("value", "v");
        return in;
    }

    private static Map<String, Object> schema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("operation", field("string", true, "read", "write"));
        Map<String, Object> count = field("integer", true);
        count.put("minimum", 1);
        count.put("maximum", 10);
        schema.put("count", count);
        schema.put("flag", field("boolean", false));
        schema.put("tags", field("array", false));
        schema.put("value", field("string", false));
        return schema;
    }

    private static Map<String, Object> field(String type, boolean required, String... enums) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("type", type);
        if (required) def.put("required", true);
        if (enums != null && enums.length > 0) def.put("enum", List.of(enums));
        return def;
    }

    private static boolean contains(String s, String sub) {
        return s != null && s.contains(sub);
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
