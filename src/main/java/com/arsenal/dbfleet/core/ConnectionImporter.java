package com.arsenal.dbfleet.core;

import com.arsenal.dbfleet.model.ConnSpec;
import com.arsenal.dbfleet.model.DBType;
import org.eclipse.core.runtime.IStatus;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPConnectionType;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.connection.DBPDriverConfigurationType;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.registry.DataSourceDescriptor;
import org.jkiss.dbeaver.registry.DataSourceProviderRegistry;
import org.jkiss.dbeaver.registry.DataSourceRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@link ConnSpec} 变成 DBeaver 连接：解析驱动 → 构造配置 → 实测 → 成功才落库。
 */
public class ConnectionImporter {

    private static final Log log = Log.getLog(ConnectionImporter.class);

    private final DataSourceRegistry registry;

    public ConnectionImporter(DBPDataSourceRegistry registry) {
        this.registry = (DataSourceRegistry) registry;
    }

    public DataSourceRegistry getRegistry() {
        return registry;
    }

    // ------------------------------------------------------------------ driver

    /**
     * 按候选顺序在本机已安装的驱动中查找。EE / CE 的 provider id 不同，逐个试。
     */
    public static DBPDriver resolveDriver(DBType type) {
        DataSourceProviderRegistry providerRegistry = DataSourceProviderRegistry.getInstance();
        for (String driverId : type.getDriverIds()) {
            for (String providerId : type.getProviderIds()) {
                DBPDriver driver = providerRegistry.findDriver(providerId + ":" + driverId);
                if (driver != null && !driver.isDeprecated()) {
                    return driver;
                }
            }
        }
        // 兜底：直接按裸 driver id 找（findDriver 支持裸 id）
        for (String driverId : type.getDriverIds()) {
            DBPDriver driver = providerRegistry.findDriver(driverId);
            if (driver != null && !driver.isDeprecated()) {
                return driver;
            }
        }
        // 再兜底：遍历 provider 下所有驱动，按 label/默认端口匹配
        for (var provider : providerRegistry.getEnabledDataSourceProviders()) {
            for (DBPDriver driver : provider.getDrivers()) {
                if (driver.isDeprecated()) {
                    continue;
                }
                for (String driverId : type.getDriverIds()) {
                    if (driverId.equalsIgnoreCase(driver.getId())) {
                        return driver;
                    }
                }
            }
        }
        return null;
    }

    /** 驱动文件是否已在本地（未下载的驱动连接必然失败）。 */
    public static boolean isDriverReady(DBPDriver driver) {
        if (!(driver instanceof org.jkiss.dbeaver.registry.driver.DriverDescriptor)) {
            return true;
        }
        org.jkiss.dbeaver.registry.driver.DriverDescriptor dd =
                (org.jkiss.dbeaver.registry.driver.DriverDescriptor) driver;
        List<? extends org.jkiss.dbeaver.model.connection.DBPDriverLibrary> libs = dd.getEnabledDriverLibraries();
        if (libs == null || libs.isEmpty()) {
            return true;
        }
        for (var lib : libs) {
            if (lib.isDisabled()) {
                continue;
            }
            if (!dd.isLibraryResolved(lib)) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------- connection

    /**
     * 创建 + 测试 + 落库。返回结果描述。
     *
     * @param databaseOverride 指定库名；null 表示用候选列表逐个尝试
     */
    public ImportResult importOne(ConnSpec spec, DBPDriver driver, String folderPath,
                                  String databaseOverride, boolean savePassword) {
        ImportResult result = new ImportResult(spec);
        result.driverId = driver.getProviderId() + ":" + driver.getId();

        List<String> dbs = new ArrayList<>();
        if (databaseOverride != null) {
            dbs.add(databaseOverride);
        } else {
            for (String db : spec.getType().getDatabaseCandidates(spec.getDatabase())) {
                dbs.add(db);
            }
        }

        StringBuilder errors = new StringBuilder();
        for (String db : dbs) {
            DataSourceDescriptor descriptor = null;
            try {
                descriptor = buildDescriptor(spec, driver, db, savePassword, folderPath);
                boolean connected = descriptor.connect(new VoidProgressMonitor(), false, false);
                if (connected && descriptor.isConnected()) {
                    String warn = attach(spec, driver, descriptor, folderPath, savePassword);
                    result.success = true;
                    result.database = db;
                    result.name = descriptor.getName();
                    result.warning = warn == null ? "" : warn;
                    safeDisconnect(descriptor);
                    return result;
                }
                String err = descriptor.getConnectionError();
                errors.append(databaseLabel(spec.getType(), db)).append(": ")
                        .append(err == null || err.isEmpty() ? "连接未建立（认证失败或无响应）" : err)
                        .append(" | ");
                safeDisconnect(descriptor);
            } catch (DBException e) {
                errors.append(databaseLabel(spec.getType(), db)).append(": ")
                        .append(flatten(e)).append(" | ");
                safeDisconnect(descriptor);
            } catch (Throwable e) {
                errors.append(databaseLabel(spec.getType(), db)).append(": ")
                        .append(flatten(e)).append(" | ");
                safeDisconnect(descriptor);
            }
        }
        if (errors.length() > 2) {
            errors.setLength(errors.length() - 3);
        }
        result.error = errors.toString();
        if (result.error.isEmpty()) {
            result.error = "连接失败";
        }
        return result;
    }

    private static String databaseLabel(DBType type, String db) {
        if (type == DBType.ORACLE) {
            return "SID/SERVICE=" + (db.isEmpty() ? "(默认)" : db);
        }
        return "db=" + (db.isEmpty() ? "(默认)" : db);
    }

    /** 构造内存中的连接描述符，不落库。 */
    private DataSourceDescriptor buildDescriptor(ConnSpec spec, DBPDriver driver,
                                                 String db, boolean savePassword,
                                                 String folderPath) {
        DBPConnectionConfiguration cfg = new DBPConnectionConfiguration();
        cfg.setHostName(spec.getHost());
        cfg.setHostPort(spec.getPort());
        cfg.setDatabaseName(db);
        cfg.setUserName(spec.getUser());
        cfg.setUserPassword(spec.getPassword());
        cfg.setConfigurationType(DBPDriverConfigurationType.MANUAL);
        cfg.setConnectionType(DBPConnectionType.DEV);
        cfg.setAuthModelId("native");

        switch (spec.getType()) {
            case ORACLE:
                cfg.setProviderProperty("@dbeaver-connection-type@", "BASIC");
                cfg.setProviderProperty("@dbeaver-sid-service@", "SERVICE");
                cfg.setProviderProperty("oracle.logon-as", spec.isSysdba() ? "SYSDBA" : "Normal");
                break;
            case REDIS:
                cfg.setProviderProperty("redis.use.cluster", "false");
                cfg.setProviderProperty("@dbeaver-redis.timeout.connect", "10000");
                cfg.setProviderProperty("@dbeaver-redis.timeout.socket", "100000");
                cfg.setProviderProperty("redis.set.client", "true");
                break;
            case MSSQL:
                // 明确走 SQL Server 密码认证，避免探测到 Windows 集成认证
                cfg.setAuthModelId("sqlserver_database");
                cfg.setProperty("integratedSecurity", "false");
                break;
            case MONGODB: {
                boolean hasUser = !spec.getUser().isEmpty();
                cfg.setProviderProperty("authMechanism", hasUser ? "SCRAM-SHA-1" : "NONE");
                cfg.setProviderProperty("authSource", "admin");
                break;
            }
            case POSTGRESQL:
                cfg.setProviderProperty("@dbeaver-show-non-default-db@", "false");
                break;
            default:
                break;
        }

        String id = DataSourceDescriptor.generateNewId(driver);
        DataSourceDescriptor descriptor = new DataSourceDescriptor(registry, id, driver, cfg);
        descriptor.setName(uniqueName(spec.buildName()));
        descriptor.setSavePassword(savePassword);
        descriptor.setFolder(folderPath == null || folderPath.isEmpty()
                ? null : registry.getFolder(folderPath));
        return descriptor;
    }

    /** 落库。返回非空字符串表示“已创建但有提示”。 */
    private String attach(ConnSpec spec, DBPDriver driver, DataSourceDescriptor descriptor,
                          String folderPath, boolean savePassword) {
        try {
            registry.addDataSource(descriptor);
            return null;
        } catch (DBException e) {
            log.debug("addDataSource failed, retry with plain name", e);
            // 同名冲突：换名重试
            try {
                descriptor.setName(uniqueName(descriptor.getName()));
                registry.addDataSource(descriptor);
                return "连接名重复，已自动改用 " + descriptor.getName();
            } catch (DBException e2) {
                return "连接可用但写入失败: " + flatten(e2);
            }
        }
    }

    private void safeDisconnect(DataSourceDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.disconnect(new VoidProgressMonitor());
        } catch (Throwable ignore) {
            // 连接本来就没建立，忽略
        }
    }

    /** 避免与现有连接重名。 */
    private String uniqueName(String base) {
        String name = base;
        int n = 2;
        while (nameExists(name)) {
            name = base + " (" + n + ")";
            n++;
        }
        return name;
    }

    private boolean nameExists(String name) {
        for (DBPDataSourceContainer c : registry.getDataSources()) {
            if (name.equalsIgnoreCase(c.getName())) {
                return true;
            }
        }
        return false;
    }

    private static String flatten(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable t = e;
        int depth = 0;
        while (t != null && depth < 4) {
            String msg = t.getMessage();
            if (msg != null && !msg.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                sb.append(msg);
            }
            t = t.getCause();
            depth++;
        }
        return sb.length() == 0 ? e.getClass().getSimpleName() : sb.toString();
    }

    // ------------------------------------------------------------------ result

    public static class ImportResult {
        public final ConnSpec spec;
        public boolean success;
        public String driverId = "";
        public String name = "";
        public String database = "";
        public String error = "";
        public String warning = "";

        public ImportResult(ConnSpec spec) {
            this.spec = spec;
        }

        /** 统一收口，保证 UI 侧不用判空。 */
        public void setWarning(String warning) {
            this.warning = warning == null ? "" : warning;
        }

        public void setError(String error) {
            this.error = error == null ? "" : error;
        }

        @Override
        public String toString() {
            if (success) {
                return "OK  " + spec.getType().getLabel() + " " + spec.getHost() + ":" + spec.getPort()
                        + (database.isEmpty() ? "" : " db=" + database)
                        + (warning == null || warning.isEmpty() ? "" : "  [" + warning + "]");
            }
            return "FAIL " + spec.getType().getLabel() + " " + spec.getHost() + ":" + spec.getPort()
                    + "   " + error;
        }
    }
}
