package com.arsenal.dbfleet.model;

/** 一条解析成功的待导入连接。 */
public class ConnSpec {

    private final DBType type;
    private final String host;
    private final String port;
    private final String user;
    private final String password;
    private final String database;
    private final String rawLine;
    private final int lineNo;
    private final String note;
    private final boolean sysdba;

    public ConnSpec(DBType type, String host, String port, String user, String password,
                    String database, String rawLine, int lineNo, String note, boolean sysdba) {
        this.type = type;
        this.host = host;
        this.port = port;
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
        this.database = database == null ? "" : database;
        this.rawLine = rawLine;
        this.lineNo = lineNo;
        this.note = note == null ? "" : note;
        this.sysdba = sysdba;
    }

    public DBType getType() {
        return type;
    }

    public String getHost() {
        return host;
    }

    public String getPort() {
        return port;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }

    public String getDatabase() {
        return database;
    }

    public String getRawLine() {
        return rawLine;
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getNote() {
        return note;
    }

    public boolean isSysdba() {
        return sysdba;
    }

    /**
     * 连接显示名。带上类型与账号，避免同主机多库多账号互相覆盖：
     * {@code MySQL 10.1.1.1:3306 root}，有库名再补 {@code /db}。
     */
    public String buildName() {
        StringBuilder sb = new StringBuilder(type.getLabel())
                .append(' ').append(host).append(':').append(port);
        if (!user.isEmpty()) {
            sb.append(' ').append(user);
        }
        if (!database.isEmpty() && type != DBType.ORACLE) {
            sb.append('/').append(database);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "[" + type.getLabel() + "] " + host + ":" + port
                + (user.isEmpty() ? "" : " user=" + user)
                + (password.isEmpty() ? "" : " pass=" + mask(password));
    }

    private static String mask(String s) {
        return s.length() <= 2 ? "**" : s.charAt(0) + "***" + s.charAt(s.length() - 1);
    }
}
