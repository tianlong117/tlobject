package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局模块注册器：维护所有已注册模块的信息，支持按 owner 查询。
 * 应用层决定注册时机，框架只提供设施。
 */
public class TLModuleRegistry extends TLBaseModule {

    /** key → {moduleName, instance, ownerName, ...} */
    protected ConcurrentHashMap<String, HashMap<String, Object>> registry = new ConcurrentHashMap<>();

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

    private TLMsg doRegister(TLMsg msg) {
        String key = msg.getStringParam(REGISTRY_P_KEY, null);
        if (key == null || key.isEmpty()) return null;

        Object instance = msg.getParam(INSTANCE);
        String moduleName = msg.getStringParam(MODULENAME, null);
        String ownerName = msg.getStringParam(REGISTRY_P_OWNERNAME, null);

        HashMap<String, Object> info = new HashMap<>();
        info.put(MODULENAME, moduleName);
        info.put(INSTANCE, instance);
        info.put(REGISTRY_P_OWNERNAME, ownerName);
        info.put(REGISTRY_P_TYPE, msg.getStringParam(REGISTRY_P_TYPE, null));

        // 从模块实例获取家族名字
        String familyName = null;
        if (instance instanceof TLBaseModule) {
            familyName = ((TLBaseModule) instance).getFamilyName();
        }
        info.put(REGISTRY_P_FAMILYNAME, familyName);

        registry.put(key, info);
        // 同时以家族名字为 key 注册，支持按完整路径查找
        if (familyName != null && !familyName.isEmpty() && !familyName.equals(key)) {
            registry.put(familyName, info);
        }
        putLog(key + " registered (familyName=" + familyName + ")", LogLevel.DEBUG, REGISTRY_REGISTER);
        return createMsg().setParam(RESULT, true);
    }

    private TLMsg doUnregister(TLMsg msg) {
        String key = msg.getStringParam(REGISTRY_P_KEY, null);
        if (key == null || key.isEmpty()) return null;
        HashMap<String, Object> removed = registry.remove(key);
        if (removed != null) {
            // 收集并删除所有指向同一 info map 的 key（短名 + 家族名字）
            List<String> keysToRemove = new ArrayList<>();
            for (Map.Entry<String, HashMap<String, Object>> e : registry.entrySet()) {
                if (e.getValue() == removed) {
                    keysToRemove.add(e.getKey());
                }
            }
            for (String k : keysToRemove) {
                registry.remove(k);
            }
            String removedName = (String) removed.get(MODULENAME);
            if (removedName != null) {
                cascadeRemove(removedName);
            }
        }
        putLog(key + " unregistered", LogLevel.DEBUG, REGISTRY_UNREGISTER);
        return createMsg().setParam(RESULT, true);
    }

    /** 递归删除 ownerName 的所有子孙条目（含家族名字 key） */
    private void cascadeRemove(String ownerName) {
        List<String> children = new ArrayList<>();
        for (Map.Entry<String, HashMap<String, Object>> e : registry.entrySet()) {
            if (ownerName.equals(e.getValue().get(REGISTRY_P_OWNERNAME))) {
                children.add(e.getKey());
            }
        }
        for (String childKey : children) {
            HashMap<String, Object> child = registry.remove(childKey);
            if (child != null) {
                // 删除所有指向同一 info map 的 alternate key（家族名字等）
                List<String> altKeys = new ArrayList<>();
                for (Map.Entry<String, HashMap<String, Object>> e : registry.entrySet()) {
                    if (e.getValue() == child) {
                        altKeys.add(e.getKey());
                    }
                }
                for (String altKey : altKeys) {
                    registry.remove(altKey);
                }
                String childName = (String) child.get(MODULENAME);
                putLog(childKey + " cascade unregistered (owner " + ownerName + " removed)", LogLevel.DEBUG);
                if (childName != null) cascadeRemove(childName);
            }
        }
    }

    private TLMsg doList(TLMsg msg) {
        String ownerName = msg.getStringParam(REGISTRY_P_OWNERNAME, null);
        String moduleType = msg.getStringParam(REGISTRY_P_TYPE, null);
        List<Map<String, Object>> result = new ArrayList<>();
        Set<HashMap<String, Object>> seen = new HashSet<>();  // 去重：短 key 和 familyName key 指向同一 map
        for (Map.Entry<String, HashMap<String, Object>> entry : registry.entrySet()) {
            if (!seen.add(entry.getValue())) continue;  // 已见过的 info map 跳过
            if (ownerName != null) {
                String entryOwner = (String) entry.getValue().get(REGISTRY_P_OWNERNAME);
                if (!ownerName.equals(entryOwner)) continue;
            }
            if (moduleType != null) {
                String entryType = (String) entry.getValue().get(REGISTRY_P_TYPE);
                if (!moduleType.equals(entryType)) continue;
            }
            Map<String, Object> item = new HashMap<>(entry.getValue());
            item.put(REGISTRY_P_KEY, entry.getKey());
            result.add(item);
        }
        return createMsg().setParam(RESULT, result);
    }

    private TLMsg doGet(TLMsg msg) {
        String key = msg.getStringParam(REGISTRY_P_KEY, null);
        if (key == null || key.isEmpty()) return null;
        HashMap<String, Object> info = registry.get(key);
        if (info == null) return null;
        HashMap<String, Object> result = new HashMap<>(info);
        result.put(REGISTRY_P_KEY, key);
        return createMsg().addMap(result);
    }
}
