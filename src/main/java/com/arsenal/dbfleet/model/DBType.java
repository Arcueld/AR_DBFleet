package com.arsenal.dbfleet.model;

/**
 * 支持批量导入的数据库类型。
 * <p>
 * providerIds / driverIds 为按优先级排列的候选：企业版 (EE) 与社区版 (CE) 的 provider id 不同，
 * 导入时以本机实际安装为准逐个匹配。
 */
public enum DBType {

    MYSQL("MySQL", "3306", "",
            new String[]{"mysql-ee", "mysql"},
            new String[]{"mysql8", "mysql5"}),

    POSTGRESQL("PostgreSQL", "5432", "postgres",
            new String[]{"postgresql"},
            new String[]{"postgres-jdbc"}),

    MSSQL("SQL Server", "1433", "master",
            new String[]{"sqlserver-ee", "sqlserver"},
            new String[]{"microsoft"}),

    ORACLE("Oracle", "1521", "",
            new String[]{"oracle-ee", "oracle"},
            new String[]{"oracle_thin"}),

    REDIS("Redis", "6379", "0",
            new String[]{"redis"},
            new String[]{"redis_jedis"}),

    MONGODB("MongoDB", "27017", "admin",
            new String[]{"mongodb"},
            new String[]{"cql"}),

    CLICKHOUSE("ClickHouse", "8123", "default",
            new String[]{"clickhouse"},
            new String[]{"com_clickhouse", "yandex_clickhouse"});

    private static final String[] ORACLE_DBS = {"orcl", "XE", "ORCL", "pdborcl"};

    private final String label;
    private final String defaultPort;
    private final String defaultDatabase;
    private final String[] providerIds;
    private final String[] driverIds;

    DBType(String label, String defaultPort, String defaultDatabase,
           String[] providerIds, String[] driverIds) {
        this.label = label;
        this.defaultPort = defaultPort;
        this.defaultDatabase = defaultDatabase;
        this.providerIds = providerIds;
        this.driverIds = driverIds;
    }

    public String getLabel() {
        return label;
    }

    public String getDefaultPort() {
        return defaultPort;
    }

    public String getDefaultDatabase() {
        return defaultDatabase;
    }

    public String[] getProviderIds() {
        return providerIds;
    }

    public String[] getDriverIds() {
        return driverIds;
    }

    /**
     * 需要按顺序尝试的数据库名/服务名候选。
     * Oracle 无法从 fscan 行中拿到 SID/SERVICE，只能枚举常见默认值。
     */
    public String[] getDatabaseCandidates(String provided) {
        String db = provided == null ? "" : provided.trim();
        if (this == ORACLE) {
            String[] list = new String[ORACLE_DBS.length + (db.isEmpty() ? 0 : 1)];
            int i = 0;
            if (!db.isEmpty()) {
                list[i++] = db;
            }
            for (String s : ORACLE_DBS) {
                list[i++] = s;
            }
            return list;
        }
        if (!db.isEmpty()) {
            return new String[]{db};
        }
        return new String[]{defaultDatabase};
    }

    /** 文本形式（fscan 输出的类型名、自定义行里的类型前缀/后缀）→ 枚举。 */
    public static DBType fromText(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim().toLowerCase();
        switch (t) {
            case "mysql":
            case "mariadb":
            case "mysql5":
            case "mysql8":
            case "mssql":
                return t.equals("mssql") ? MSSQL : MYSQL;
            case "postgres":
            case "postgresql":
            case "pgsql":
            case "pg":
                return POSTGRESQL;
            case "sqlserver":
            case "sql-server":
            case "tsql":
            case "microsoft":
                return MSSQL;
            case "oracle":
            case "ora":
                return ORACLE;
            case "redis":
                return REDIS;
            case "mongodb":
            case "mongo":
                return MONGODB;
            case "clickhouse":
            case "ch":
                return CLICKHOUSE;
            default:
                break;
        }
        for (DBType t2 : values()) {
            if (t2.name().equalsIgnoreCase(t)) {
                return t2;
            }
        }
        return null;
    }

    /** JDBC 子协议 → 枚举。 */
    public static DBType fromJdbcSubProtocol(String sub) {
        if (sub == null) {
            return null;
        }
        switch (sub.trim().toLowerCase()) {
            case "postgresql":
                return POSTGRESQL;
            case "mysql":
            case "mariadb":
                return MYSQL;
            case "sqlserver":
            case "jtds":
                return MSSQL;
            case "oracle":
                return ORACLE;
            case "redis":
                return REDIS;
            case "mongodb":
            case "mongo":
                return MONGODB;
            case "clickhouse":
                return CLICKHOUSE;
            default:
                return null;
        }
    }

    /** 默认端口 → 枚举；非默认端口返回 null（此时必须显式给出类型）。 */
    public static DBType fromPort(String port) {
        if (port == null) {
            return null;
        }
        switch (port.trim()) {
            case "3306":
            case "3307":
                return MYSQL;
            case "5432":
            case "5433":
                return POSTGRESQL;
            case "1433":
            case "1434":
                return MSSQL;
            case "1521":
            case "1522":
                return ORACLE;
            case "6379":
            case "6380":
                return REDIS;
            case "27017":
            case "27018":
                return MONGODB;
            case "8123":
            case "9000":
                return CLICKHOUSE;
            default:
                return null;
        }
    }
}
