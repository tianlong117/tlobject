package cn.tianlong.java.demo.aiagent.skills;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 价格计算 Skill — 根据数量计算总价。
 *
 * 通过 XML 参数 itemName 和 unitPrice 配置商品名称和单价，
 * LLM 调用时传入 quantity 即可得到总价。
 *
 * 创建日期：2026/7/7
 * 作者:tianlong
 */
public class TLPriceCalculatorSkill extends TLBaseSkill {

    private String itemName = "商品";
    private double unitPrice = 0;

    public TLPriceCalculatorSkill() { super(); }
    public TLPriceCalculatorSkill(String name) { super(name); }
    public TLPriceCalculatorSkill(String name, TLObjectFactory factory) { super(name, factory); }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (skillName == null || skillName.isEmpty())
            skillName = "calculate_price";
        if (skillDescription == null || skillDescription.isEmpty())
            skillDescription = "根据购买数量计算总价";

        if (params != null) {
            if (params.get("itemName") != null)
                itemName = params.get("itemName");
            if (params.get("unitPrice") != null) {
                try { unitPrice = Double.parseDouble(params.get("unitPrice")); }
                catch (NumberFormatException ignored) {}
            }
        }

        // 动态构建参数 schema
        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            Map<String, Object> quantityProp = new LinkedHashMap<>();
            quantityProp.put("type", "integer");
            quantityProp.put("description", "购买" + itemName + "的数量");
            quantityProp.put("required", true);
            parameterSchema.put("quantity", quantityProp);
        }

        // 更新描述
        skillDescription = itemName + "单价" + unitPrice + "元，根据购买数量计算总价";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        Map<String, Object> input = (Map<String, Object>) msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
        if (input.isEmpty()) {
            input = new LinkedHashMap<>();
            if (msg.containsParam("quantity"))
                input.put("quantity", msg.getParam("quantity"));
        }

        Object qtyObj = input.get("quantity");
        int quantity = 0;
        if (qtyObj instanceof Number) {
            quantity = ((Number) qtyObj).intValue();
        } else if (qtyObj instanceof String) {
            try { quantity = Integer.parseInt((String) qtyObj); }
            catch (NumberFormatException ignored) {}
        }

        if (quantity <= 0) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error: 请输入有效的购买数量（大于0的整数）");
        }

        double total = quantity * unitPrice;
        String output = itemName + " × " + quantity + " = " + total + "元";

        return createMsg().setParam(RESULT, true)
                .setParam(AI_P_SKILLOUTPUT, output)
                .setParam("itemName", itemName)
                .setParam("quantity", quantity)
                .setParam("unitPrice", unitPrice)
                .setParam("total", total);
    }
}
