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

        HashMap<String, Object> info = new HashMap<>();
        info.put(MODULENAME, msg.getStringParam(MODULENAME, null));
        info.put(INSTANCE, msg.getParam(INSTANCE));
        info.put(REGISTRY_P_OWNERNAME, msg.getStringParam(REGISTRY_P_OWNERNAME, null));
        info.put(REGISTRY_P_TYPE, msg.getStringParam(REGISTRY_P_TYPE, null));
        registry.put(key, info);
        putLog(key + " registered", LogLevel.DEBUG, REGISTRY_REGISTER);
        return createMsg().setParam(RESULT, true);
    }

    private TLMsg doUnregister(TLMsg msg) {
        String key = msg.getStringParam(REGISTRY_P_KEY, null);
        if (key == null || key.isEmpty()) return null;
        HashMap<String, Object> removed = registry.remove(key);
        if (removed != null) {
            String removedName = (String) removed.get(MODULENAME);
            if (removedName != null) {
                cascadeRemove(removedName);
            }
        }
        putLog(key + " unregistered", LogLevel.DEBUG, REGISTRY_UNREGISTER);
        return createMsg().setParam(RESULT, true);
    }

    /** 递归删除 ownerName 的所有子孙条目 */
    private void cascadeRemove(String ownerName) {
        List<String> children = new ArrayList<>();
        for (Map.Entry<String, HashMap<String, Object>> e : registry.entrySet()) {
            if (ownerName.equals(e.getValue().get(REGISTRY_P_OWNERNAME))) {
                children.add(e.getKey());
            }
        }
        for (String childKey : children) {
            HashMap<String, Object> child = registry.remove(childKey);
            String childName = child != null ? (String) child.get(MODULENAME) : null;
            putLog(childKey + " cascade unregistered (owner " + ownerName + " removed)", LogLevel.DEBUG);
            if (childName != null) cascadeRemove(childName);
        }
    }

    private TLMsg doList(TLMsg msg) {
        String ownerName = msg.getStringParam(REGISTRY_P_OWNERNAME, null);
        String moduleType = msg.getStringParam(REGISTRY_P_TYPE, null);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, HashMap<String, Object>> entry : registry.entrySet()) {
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
