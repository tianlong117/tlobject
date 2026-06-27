package cn.tianlong.tlobject.cache;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static cn.tianlong.tlobject.cache.TLParamString.*;

public class TLFileCache extends TLBaseCache {


    protected String cachePath;
    protected Gson gson = new Gson();
    protected Gson writeGson;  // 带日期格式的 Gson，仅创建一次

    public TLFileCache() {
        super();
    }

    public TLFileCache(String name) {
        super(name);
    }

    public TLFileCache(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if (params != null && params.get("cachePath") != null)
            cachePath = params.get("cachePath");
        else
            cachePath = System.getProperty("user.dir") + File.separator + "cache" + File.separator;
        if (params != null && params.get("fileLockNumber") != null)
            fileLockNumber = params.get("fileLockNumber");
        ifUseLock = true;
    }

    @Override
    protected TLBaseModule init() {
        super.init();
        writeGson = new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss").serializeNulls().create();
        File path = new File(cachePath);
        if (!path.exists()) {
            boolean created = path.mkdirs();
            if (!created) {
                putLog("cachePath create failure : " + cachePath, LogLevel.ERROR);
                return null;
            }
        }
        putLog("cachePath create : " + cachePath, LogLevel.DEBUG);
        return this;
    }

    public Object getCache(String cacheName, String cacheKey) {
        return getCache(cacheName, cacheKey, null);
    }

    @Override
    public Object getCache(String cacheName, String cacheKey, String valueType) {
        if (cacheName == null || cacheTables == null || !cacheTables.containsKey(cacheName))
            return this;
        cacheKey = checkCacheKey(cacheKey);
        try {
            return get(cacheName, cacheKey);
        } catch (Exception e) {
            e.printStackTrace();
            putLog("缓存读错误,cacheName " + cacheName + " cacheKey:" + cacheKey, LogLevel.ERROR, "getCache");
            return this;
        }
    }

    protected Object get(String cacheName, String cacheKey) throws Exception {

        String filePath = getCacheFileName(cacheName, cacheKey);
        ReentrantReadWriteLock lock = getLock(filePath);
        ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
        readLock.lock();
        File file = new File(filePath);
        if (!file.exists()) {
            readLock.unlock();
            reBackLock();
            return this;
        }
        byte[] b;
        try (InputStream in = new FileInputStream(file)) {
            b = new byte[(int) file.length()];
            int offset = 0;
            while (offset < b.length) {
                int read = in.read(b, offset, b.length - offset);
                if (read == -1) break;
                offset += read;
            }
        }
        readLock.unlock();
        reBackLock();
        String jsonStr = new String(b, StandardCharsets.UTF_8);
        HashMap<String, Object> cachedata;
        try {
            cachedata = gson.fromJson(jsonStr, HashMap.class);
        } catch (Exception e) {
            putLog("json 解析错误" + cacheName + " cacheKey:" + cacheKey, LogLevel.ERROR, "getCache");
            return this;
        }
        Double cacheTime = (Double) cachedata.get(CACHE_P_EXPTTIME);
        long expTime = cacheTime.longValue();
        if (expTime > System.currentTimeMillis() || expTime == 0L) {
            return cachedata.get(CACHE_R_VALUE);
        } else {
            putLog("缓存过期:cacheName " + cacheName + " cacheKey:" + cacheKey, LogLevel.DEBUG, "getCache");
            ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
            writeLock.lock();
            try {
                if (file.exists()) {
                    file.delete();
                }
                return this;
            } finally {
                writeLock.unlock();
                reBackLock();
            }
        }
    }

    public boolean writeCache(String cacheName, String cacheKey, Object cacheValue, int exptime) {
        return writeCache(cacheName, cacheKey, cacheValue, exptime, null);
    }

    @Override
    public boolean writeCache(String cacheName, String cacheKey, Object cacheValue, int exptime, String valueType) {
        if (cacheName == null || cacheName.isEmpty() || cacheTables == null)
            return false;
        cacheKey = checkCacheKey(cacheKey);
        Long cacheExptime = takeExptime(cacheName, exptime);
        if (cacheExptime < 0L)
            return false;
        return cache(cacheName, cacheKey, cacheValue, cacheExptime);
    }

    protected boolean cache(String cacheName, String cacheKey, Object cacheValue, Long cacheExptime) {
        String filePath = getCacheFileName(cacheName, cacheKey);
        File file = new File(filePath);
        File fileParent = file.getParentFile();
        if (!fileParent.exists()) {
            fileParent.mkdirs();
        }
        ReentrantReadWriteLock lock = getLock(filePath);
        ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            HashMap<String, Object> cachedatas = new HashMap<>();
            cachedatas.put(CACHE_P_EXPTTIME, cacheExptime);
            cachedatas.put(CACHE_P_VALUE, cacheValue);
            String jsonString = writeGson.toJson(cachedatas);
            // 原子写入：先写临时文件，成功后再 rename
            File tmpFile = new File(filePath + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmpFile)) {
                out.write(jsonString.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            putLog("缓存文件写错误:" + filePath, LogLevel.ERROR, "cache");
            e.printStackTrace();
            return false;
        } finally {
            writeLock.unlock();
            reBackLock();
        }
    }

    public boolean deleteCache(String cacheName, String cacheKey) {
        return deleteCache(cacheName, cacheKey, null);
    }

    @Override
    public boolean deleteCache(String cacheName, String cacheKey, String valueType) {
        if (cacheName == null || cacheTables == null || !cacheTables.containsKey(cacheName))
            return false;
        cacheKey = checkCacheKey(cacheKey);
        String filePath = getCacheFileName(cacheName, cacheKey);
        ReentrantReadWriteLock lock = getLock(filePath);
        ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            File file = new File(filePath);
            if (file.exists()) {
                file.delete();
            }
            // 也清理残留的临时文件
            File tmpFile = new File(filePath + ".tmp");
            if (tmpFile.exists()) tmpFile.delete();
            return true;
        } finally {
            writeLock.unlock();
            reBackLock();
        }
    }

    /** 将 key 中的非法文件名字符进行 URL 编码，防止不同 key 映射到同一文件 */
    protected String checkCacheKey(String cacheKey) {
        if (cacheKey == null || cacheKey.isEmpty())
            return "";
        // 对文件名不安全的字符做 URL 编码
        StringBuilder sb = new StringBuilder();
        for (char c : cacheKey.toCharArray()) {
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                sb.append('%').append(Integer.toHexString(c).toUpperCase());
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    protected String getCacheFileName(String cacheName, String cacheKey) {
        StringBuilder sb = new StringBuilder();
        sb.append(cachePath);
        sb.append(File.separator);
        sb.append(cacheName);
        sb.append(File.separator);
        sb.append(cacheName);
        sb.append("_");
        sb.append(cacheKey);
        sb.append(".json");
        return sb.toString();
    }

}
