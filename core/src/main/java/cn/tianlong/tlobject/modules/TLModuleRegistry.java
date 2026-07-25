package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局模块注册器：以 familyName → instance 存储。
 * name 由 familyName 推导（取最后 ':' 段）。owner 归属由 {@link #isDirectChildOf} 判断。
 * 类型判断由调用方通过 instanceof 自行处理，注册器不存储 type/ownerName。
 */
public class TLModuleRegistry extends TLBaseModule {

    /** familyName → instance */
    protected ConcurrentHashMap<String, Object> registry = new ConcurrentHashMap<>();

    public TLModuleRegistry() {
        super();
    }

    public TLModuleRegistry(String name) {
        super(name);
    }

    public TLModuleRegistry(String name, TLObjectFactory moduleFactory) {
        super(name, moduleFactory);
    }

    @Override
    protected TLBaseModule init() {
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case REGISTRY_REGISTER:
                return doRegister(msg);
            case REGISTRY_UNREGISTER:
                return doUnregister(msg);
            case REGISTRY_LIST:
                return doList(msg);
            case REGISTRY_GET:
                return doGet(msg);
            default:
                return null;
        }
    }

    /** 注册：familyName → instance。name 由 familyName 推导，无需显式存储。 */
    private TLMsg doRegister(TLMsg msg) {
        String familyName = msg.getStringParam(REGISTRY_P_KEY, null);
        Object instance = msg.getParam(INSTANCE);
        if (familyName == null || familyName.isEmpty() || instance == null) return null;
        registry.put(familyName, instance);
        putLog(familyName + " registered", LogLevel.DEBUG, REGISTRY_REGISTER);
        return createMsg().setParam(RESULT, true);
    }

    /** 获取：按 familyName 精确查找，返回 instance。 */
    private TLMsg doGet(TLMsg msg) {
        String key = msg.getStringParam(REGISTRY_P_KEY, null);
        if (key == null || key.isEmpty()) return null;
        Object instance = registry.get(key);
        if (instance == null) return null;
        return createMsg().setParam(INSTANCE, instance);
    }

    /** 列出：可选 ownerFamilyName 过滤直接子模块。name 从 familyName 推导返回。 */
    @SuppressWarnings("unchecked")
    private TLMsg doList(TLMsg msg) {
        String ownerFamilyName = msg.getStringParam(REGISTRY_P_OWNERNAME, null);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : registry.entrySet()) {
            String familyName = entry.getKey();
            if (ownerFamilyName != null && !isDirectChildOf(familyName, ownerFamilyName)) continue;
            Map<String, Object> item = new HashMap<>();
            item.put(REGISTRY_P_KEY, familyName);
            item.put(MODULENAME, familyName.substring(familyName.lastIndexOf(':') + 1));
            item.put(INSTANCE, entry.getValue());
            result.add(item);
        }
        return createMsg().setParam(RESULT, result);
    }

    /** 注销：按 familyName 精确删除，级联删除所有直接子节点。 */
    private TLMsg doUnregister(TLMsg msg) {
        String key = msg.getStringParam(REGISTRY_P_KEY, null);
        if (key == null || key.isEmpty()) return null;
        if (registry.remove(key) != null) {
            cascadeRemove(key);
        }
        putLog(key + " unregistered", LogLevel.DEBUG, REGISTRY_UNREGISTER);
        return createMsg().setParam(RESULT, true);
    }

    /** 递归删除 ownerFamilyName 的所有直接子节点（及它们自己的后代） */
    private void cascadeRemove(String ownerFamilyName) {
        List<String> children = new ArrayList<>();
        for (String familyName : registry.keySet()) {
            if (isDirectChildOf(familyName, ownerFamilyName)) {
                children.add(familyName);
            }
        }
        for (String childFamilyName : children) {
            registry.remove(childFamilyName);
            putLog(childFamilyName + " cascade unregistered (owner " + ownerFamilyName + " removed)", LogLevel.DEBUG);
            cascadeRemove(childFamilyName);
        }
    }

    /** 判断 familyName 是否是 parentFamilyName 的直接子节点。
     *  "a:b" 是 "a" 的直接子节点；"a:b:c" 不是。 */
    static boolean isDirectChildOf(String familyName, String parentFamilyName) {
        if (familyName == null || parentFamilyName == null) return false;
        int lastColon = familyName.lastIndexOf(':');
        if (lastColon < 0) return false;
        return familyName.substring(0, lastColon).equals(parentFamilyName);
    }
}
