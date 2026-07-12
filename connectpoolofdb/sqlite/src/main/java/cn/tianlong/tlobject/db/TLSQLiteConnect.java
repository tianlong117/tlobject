package cn.tianlong.tlobject.db;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;


/**
 * SQLite database connector. Based on sqlite-jdbc, uses SQLiteDataSource.
 * SQLite is a file-based database, no connection pool needed.
 * Supports SQLite pragma configuration via params.
 *
 * Required param:
 *   dburl - JDBC URL, e.g. "jdbc:sqlite:/path/to/db.sqlite" or "jdbc:sqlite::memory:"
 *
 * Optional params (SQLite pragmas):
 *   dateClass  - "text" (default) / "integer" / "real"
 *   journalMode - WAL / DELETE / TRUNCATE / PERSIST / MEMORY / OFF
 *   synchronous - OFF / NORMAL / FULL / EXTRA
 *   foreignKeys - ON / OFF
 *   busyTimeout - lock wait timeout in milliseconds
 *   cacheSize   - cache page count (negative means KB), e.g. "-2000"
 *   tempStore   - DEFAULT / FILE / MEMORY
 *   lockingMode - NORMAL / EXCLUSIVE
 *   caseSensitiveLike - true / false
 *
 * @author tianlong
 * @date 2026/7/12
 */

public class TLSQLiteConnect extends TLBaseModule implements TLBaseConnectorInterface {

    private SQLiteDataSource dataSource;

    public TLSQLiteConnect() {
        super();
    }

    public TLSQLiteConnect(String name) {
        super(name);
    }

    public TLSQLiteConnect(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected TLBaseModule init() {
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "init":
                init(fromWho, msg);
                break;
            case DB_GETCONN:
                returnMsg = getConnection(fromWho, msg);
                break;
            case "getDataSource":
                returnMsg = createMsg().setParam("dataSource", dataSource);
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }

    @SuppressWarnings("unchecked")
    protected void init(Object fromWho, TLMsg msg) {
        if (!msg.isNull("params"))
            params = (HashMap<String, String>) msg.getParam("params");

        String dburl = params.get("dburl");
        if (dburl == null || dburl.isEmpty()) {
            putLog("SQLite connect failed: missing dburl param", LogLevel.ERROR);
            return;
        }

        SQLiteConfig config = new SQLiteConfig();

        // Date class: text (default), integer, or real
        String dateClass = params.getOrDefault("dateClass", "text");
        config.setDateClass(dateClass.toLowerCase());

        // Apply pragma settings
        applyPragma(config, params);

        // Build DataSource
        dataSource = new SQLiteDataSource(config);
        dataSource.setUrl(dburl);

        putLog("SQLite DataSource initialized: " + dburl, LogLevel.INFO);
    }

    /**
     * Apply SQLite pragma settings from params to SQLiteConfig.
     */
    private void applyPragma(SQLiteConfig config, HashMap<String, String> params) {
        // Journal Mode
        String journalMode = params.get("journalMode");
        if (journalMode != null) {
            try {
                SQLiteConfig.JournalMode mode = SQLiteConfig.JournalMode.valueOf(journalMode.toUpperCase());
                config.setJournalMode(mode);
            } catch (IllegalArgumentException e) {
                putLog("Invalid journalMode: " + journalMode, LogLevel.WARN);
            }
        }

        // Synchronous
        String synchronous = params.get("synchronous");
        if (synchronous != null) {
            try {
                SQLiteConfig.SynchronousMode mode = SQLiteConfig.SynchronousMode.valueOf(synchronous.toUpperCase());
                config.setSynchronous(mode);
            } catch (IllegalArgumentException e) {
                putLog("Invalid synchronous: " + synchronous, LogLevel.WARN);
            }
        }

        // Foreign Keys
        String foreignKeys = params.get("foreignKeys");
        if (foreignKeys != null) {
            config.enforceForeignKeys("ON".equalsIgnoreCase(foreignKeys) || "true".equalsIgnoreCase(foreignKeys));
        }

        // Busy Timeout (ms)
        String busyTimeout = params.get("busyTimeout");
        if (busyTimeout != null) {
            try {
                config.setBusyTimeout(Integer.parseInt(busyTimeout));
            } catch (NumberFormatException e) {
                putLog("Invalid busyTimeout: " + busyTimeout, LogLevel.WARN);
            }
        }

        // Cache Size
        String cacheSize = params.get("cacheSize");
        if (cacheSize != null) {
            try {
                config.setCacheSize(Integer.parseInt(cacheSize));
            } catch (NumberFormatException e) {
                putLog("Invalid cacheSize: " + cacheSize, LogLevel.WARN);
            }
        }

        // Temp Store
        String tempStore = params.get("tempStore");
        if (tempStore != null) {
            try {
                SQLiteConfig.TempStore mode = SQLiteConfig.TempStore.valueOf(tempStore.toUpperCase());
                config.setTempStore(mode);
            } catch (IllegalArgumentException e) {
                putLog("Invalid tempStore: " + tempStore, LogLevel.WARN);
            }
        }

        // Locking Mode
        String lockingMode = params.get("lockingMode");
        if (lockingMode != null) {
            try {
                SQLiteConfig.LockingMode mode = SQLiteConfig.LockingMode.valueOf(lockingMode.toUpperCase());
                config.setLockingMode(mode);
            } catch (IllegalArgumentException e) {
                putLog("Invalid lockingMode: " + lockingMode, LogLevel.WARN);
            }
        }

        // Case Sensitive Like
        String caseSensitiveLike = params.get("caseSensitiveLike");
        if (caseSensitiveLike != null) {
            config.enableCaseSensitiveLike(
                "ON".equalsIgnoreCase(caseSensitiveLike) || "true".equalsIgnoreCase(caseSensitiveLike));
        }

        // Generic KV pragma (e.g. "pragma.auto_vacuum" = "incremental")
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("pragma.")) {
                try {
                    SQLiteConfig.Pragma pragma = SQLiteConfig.Pragma.valueOf(
                            key.substring(7).toUpperCase()); // strip "pragma." prefix
                    config.setPragma(pragma, entry.getValue());
                } catch (IllegalArgumentException e) {
                    putLog("Unknown pragma: " + key, LogLevel.WARN);
                }
            }
        }
    }

    private TLMsg getConnection(Object fromWho, TLMsg msg) {
        Connection conn = connect();
        return msg.setParam(DB_R_CONN, conn);
    }

    @Override
    public Connection connect() {
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            putLog("SQLite connect failed: " + (params != null ? params.get("dburl") : "unknown"),
                   LogLevel.ERROR);
            return null;
        }
    }

    @Override
    public void close(Object conn) {
        try {
            ((Connection) conn).close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
