package com.arsenal.dbfleet.parse;

import com.arsenal.dbfleet.model.ConnSpec;
import com.arsenal.dbfleet.model.DBType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 连接串解析器。支持三种来源混写：
 * <pre>
 * 1) fscan 导出:   [+] Postgres:192.0.2.10:5432:appuser 'P@ssw0rd1'
 *                  [+] mssql 192.0.2.10:1433:sa P@ssw0rd2
 *                  [+] Redis 192.0.2.10:6379 unauthorized file:/redis/etc/redis.conf
 * 2) JDBC URL:     jdbc:postgresql://192.0.2.11:5432/app?user=app&amp;password=P%40ss
 *                  jdbc:oracle:thin:@//192.0.2.12:1521/ORCL
 *                  jdbc:sqlserver://192.0.2.13:1433;databaseName=master;user=sa;password=P%40ss
 * 3) 自定义:       192.0.2.10:1433 sa / P@ssw0rd2
 *                  oracle 192.0.2.10:1433 sa / P@ssw0rd2
 *                  192.0.2.10:1433:sa:P@ssw0rd2
 * </pre>
 */
public class ConnectionParser {

    private static final Pattern FSCAN = Pattern.compile("^\\[\\+\\]\\s*(.*)$");
    private static final Pattern JDBC = Pattern.compile("^jdbc:([A-Za-z0-9]+):(.*)$", Pattern.DOTALL);
    private static final Pattern SCHEME_URL = Pattern.compile("^([A-Za-z0-9]+)://(.*)$", Pattern.DOTALL);
    private static final Pattern HOST_PORT = Pattern.compile("^([^:/;\\s]+)(?::(\\d{1,5}))?$");
    private static final Pattern CRED_SLASH = Pattern.compile("^(.*?)\\s*/\\s*(.*)$", Pattern.DOTALL);

    private static final Set<String> UNAUTHORIZED = new HashSet<>();

    static {
        UNAUTHORIZED.add("unauthorized");
        UNAUTHORIZED.add("noauth");
        UNAUTHORIZED.add("none");
        UNAUTHORIZED.add("-");
    }

    /** 解析多行文本。 */
    public ParseResult parse(String text) {
        ParseResult result = new ParseResult();
        if (text == null) {
            return result;
        }
        Set<String> seen = new HashSet<>();
        String[] lines = text.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String raw = lines[i].trim();
            if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("//")) {
                continue;
            }
            ConnSpec spec = null;
            String error;
            try {
                spec = parseLine(raw, lineNo);
                error = spec == null ? "无法识别的格式（非默认端口时必须显式指定数据库类型）" : null;
            } catch (RuntimeException e) {
                spec = null;
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            if (spec == null) {
                result.getBadLines().add(new ParseResult.BadLine(lineNo, raw, error));
                continue;
            }
            String key = spec.getType().name() + '|' + spec.getHost().toLowerCase(Locale.ROOT)
                    + '|' + spec.getPort() + '|' + spec.getUser() + '|' + spec.getPassword()
                    + '|' + spec.getDatabase();
            if (!seen.add(key)) {
                result.getDuplicateLines().add(new ParseResult.BadLine(lineNo, raw, "与前面的行完全重复，已跳过"));
                continue;
            }
            result.getConnections().add(spec);
        }
        return result;
    }

    /** 解析单行；无法识别返回 null。 */
    public ConnSpec parseLine(String line, int lineNo) {
        String raw = line.trim();
        if (raw.isEmpty()) {
            return null;
        }
        Matcher m = JDBC.matcher(raw);
        if (m.matches()) {
            return parseJdbc(m.group(1), m.group(2), raw, lineNo);
        }
        m = FSCAN.matcher(raw);
        if (m.matches()) {
            return parseFscan(m.group(1).trim(), raw, lineNo);
        }
        m = SCHEME_URL.matcher(raw);
        if (m.matches() && DBType.fromJdbcSubProtocol(m.group(1)) != null) {
            return parseJdbc(m.group(1), m.group(2), raw, lineNo);
        }
        return parseCustom(raw, lineNo);
    }

    // ------------------------------------------------------------------ fscan

    private ConnSpec parseFscan(String body, String raw, int lineNo) {
        if (body.isEmpty()) {
            return null;
        }
        String rest = body;
        DBType type = null;
        String head = firstToken(rest);
        String headKey = head;
        int sepIdx = indexOf(rest, ':');
        if (sepIdx >= 0 && (sepIdx < rest.length() - 1)) {
            String beforeColon = rest.substring(0, sepIdx);
            if (!beforeColon.contains(".") && looksLikeType(beforeColon)) {
                headKey = beforeColon;
            }
        }
        DBType fromHead = DBType.fromText(headKey);
        if (fromHead != null) {
            type = fromHead;
            rest = rest.substring(headKey.length()).trim();
            if (rest.startsWith(":")) {
                rest = rest.substring(1).trim();
            }
        }
        if (rest.isEmpty()) {
            return null;
        }
        // host:port 或 host:port:user 或 host:port:user:password
        int colon = rest.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String host = rest.substring(0, colon).trim();
        String tail = rest.substring(colon + 1).trim();
        if (host.isEmpty() || !isHostLike(host)) {
            return null;
        }
        String port;
        String credPart;
        int colon2 = tail.indexOf(':');
        int space = indexOfWhitespace(tail);
        if (colon2 >= 0 && (space < 0 || colon2 < space)) {
            port = tail.substring(0, colon2).trim();
            credPart = tail.substring(colon2 + 1).trim();
        } else {
            // fscan 也存在 "host:port user pass" 无第二冒号的形式
            String[] parts = tail.split("\\s+", 2);
            port = parts[0].trim();
            credPart = parts.length > 1 ? parts[1].trim() : "";
        }
        if (!isPort(port)) {
            return null;
        }
        type = type == null ? DBType.fromPort(port) : type;
        if (type == null) {
            return null;
        }
        String user = "";
        String password = "";
        String note = "";
        if (!credPart.isEmpty()) {
            int sp = indexOfWhitespace(credPart);
            if (sp < 0) {
                user = unquote(credPart);
            } else {
                user = unquote(credPart.substring(0, sp));
                password = unquote(credPart.substring(sp).trim());
            }
            int cp = user.indexOf(':');
            if (cp > 0) {
                // host:port:user:password 形态
                String maybePass = user.substring(cp + 1);
                user = user.substring(0, cp);
                if (!maybePass.isEmpty()) {
                    password = maybePass;
                }
            }
            if (isUnauthorized(password)) {
                password = "";
                note = "未授权访问（无密码）";
            }
            if (isUnauthorized(user)) {
                user = "";
                password = "";
                note = "未授权访问（无账号）";
            }
        }
        boolean sysdba = type == DBType.ORACLE && "sys".equalsIgnoreCase(user);
        if (sysdba) {
            note = appendNote(note, "SYSDBA");
        }
        return new ConnSpec(type, host, port, user, password, "", raw, lineNo, note, sysdba);
    }

    private static boolean looksLikeType(String s) {
        if (s.isEmpty() || s.length() > 16) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetter(c) && c != '-' && c != '_') {
                return false;
            }
        }
        return DBType.fromText(s) != null;
    }

    // ------------------------------------------------------------------- JDBC

    private ConnSpec parseJdbc(String sub, String rest, String raw, int lineNo) {
        DBType type = DBType.fromJdbcSubProtocol(sub);
        if (type == null) {
            return null;
        }
        String body = rest;
        if (type == DBType.ORACLE) {
            return parseJdbcOracle(body, raw, lineNo);
        }
        if (!body.startsWith("//")) {
            return null;
        }
        body = body.substring(2);
        Map<String, String> props = new LinkedHashMap<>();
        String hostPort;
        String afterHost;
        if (type == DBType.MSSQL) {
            // jdbc:sqlserver://host:port;key=value;...
            int semi = body.indexOf(';');
            if (semi < 0) {
                hostPort = body;
                afterHost = "";
            } else {
                hostPort = body.substring(0, semi);
                afterHost = body.substring(semi + 1);
                for (String kv : afterHost.split(";")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        props.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                    }
                }
            }
        } else {
            int q = body.indexOf('?');
            String main = q < 0 ? body : body.substring(0, q);
            if (q >= 0) {
                parseQuery(body.substring(q + 1), props);
            }
            int slash = main.indexOf('/');
            if (slash < 0) {
                hostPort = main;
                afterHost = "";
            } else {
                hostPort = main.substring(0, slash);
                afterHost = main.substring(slash + 1);
            }
        }
        String host;
        String port;
        String hp = hostPort.trim();
        int colon = hp.lastIndexOf(':');
        if (colon > 0) {
            host = hp.substring(0, colon);
            port = hp.substring(colon + 1);
        } else {
            host = hp;
            port = type.getDefaultPort();
        }
        if (host.isEmpty() || !isPort(port)) {
            return null;
        }
        String database = afterHost == null ? "" : afterHost.trim();
        int q = database.indexOf('?');
        if (q >= 0) {
            database = database.substring(0, q);
        }
        String user = takeProp(props, "user");
        String password = takeProp(props, "password");
        if (user.isEmpty()) {
            user = takeProp(props, "username");
        }
        if (type == DBType.MSSQL) {
            database = takeProp(props, "databaseName");
            afterHost = "";
        }
        boolean sysdba = type == DBType.ORACLE && "sys".equalsIgnoreCase(user);
        String note = props.isEmpty() ? "" : "保留 JDBC 参数: " + props.keySet();
        return new ConnSpec(type, host, port, user, password, database, raw, lineNo, note, sysdba);
    }

    private ConnSpec parseJdbcOracle(String body, String raw, int lineNo) {
        String spec = body.trim();
        if (spec.startsWith("thin:")) {
            spec = spec.substring("thin:".length()).trim();
        }
        if (spec.startsWith("@")) {
            spec = spec.substring(1).trim();
        }
        Map<String, String> props = new LinkedHashMap<>();
        int q = spec.indexOf('?');
        if (q >= 0) {
            parseQuery(spec.substring(q + 1), props);
            spec = spec.substring(0, q);
        }
        String host;
        String port;
        String database;
        if (spec.startsWith("//")) {
            spec = spec.substring(2);
            int slash = spec.indexOf('/');
            String hp = slash < 0 ? spec : spec.substring(0, slash);
            database = slash < 0 ? "" : spec.substring(slash + 1);
            int colon = hp.lastIndexOf(':');
            if (colon > 0) {
                host = hp.substring(0, colon);
                port = hp.substring(colon + 1);
            } else {
                host = hp;
                port = DBType.ORACLE.getDefaultPort();
            }
        } else {
            // host:port:SID / host:port/service
            String[] parts = spec.split(":", -1);
            if (parts.length < 2) {
                return null;
            }
            host = parts[0].trim();
            port = parts[1].trim();
            StringBuilder db = new StringBuilder();
            for (int i = 2; i < parts.length; i++) {
                if (i > 2) {
                    db.append(':');
                }
                db.append(parts[i]);
            }
            database = db.toString().replaceFirst("^/+", "");
        }
        if (host.isEmpty() || !isPort(port)) {
            return null;
        }
        String user = takeProp(props, "user");
        String password = takeProp(props, "password");
        boolean sysdba = "sys".equalsIgnoreCase(user);
        String note = sysdba ? "SYSDBA" : "";
        if (!props.isEmpty()) {
            note = appendNote(note, "保留 JDBC 参数: " + props.keySet());
        }
        return new ConnSpec(DBType.ORACLE, host, port, user, password, database, raw, lineNo, note, sysdba);
    }

    private static void parseQuery(String query, Map<String, String> props) {
        for (String kv : query.split("[&;]")) {
            if (kv.isEmpty()) {
                continue;
            }
            int eq = kv.indexOf('=');
            if (eq > 0) {
                props.put(kv.substring(0, eq).trim(), urlDecode(kv.substring(eq + 1)));
            }
        }
    }

    private static String urlDecode(String s) {
        if (s.indexOf('%') < 0 && s.indexOf('+') < 0) {
            return s;
        }
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String takeProp(Map<String, String> props, String key) {
        for (Map.Entry<String, String> e : new ArrayList<>(props.entrySet())) {
            if (e.getKey().equalsIgnoreCase(key)) {
                props.remove(e.getKey());
                return e.getValue() == null ? "" : e.getValue();
            }
        }
        return "";
    }

    // ----------------------------------------------------------------- custom

    private ConnSpec parseCustom(String raw, int lineNo) {
        String rest = raw.trim();
        DBType type = null;
        // 类型前缀
        String head = firstToken(rest);
        DBType prefix = DBType.fromText(head);
        if (prefix != null && !head.contains(":") && !head.contains("/")) {
            type = prefix;
            rest = rest.substring(head.length()).trim();
        } else if (head.contains(":")) {
            // 省略 "[+]" 前缀的 fscan 行： Postgres:host:port:user 'pass'
            String headType = head.substring(0, head.indexOf(':'));
            if (DBType.fromText(headType) != null) {
                return parseFscan(raw, raw, lineNo);
            }
        }
        // 类型后缀
        int lastSpace = rest.lastIndexOf(' ');
        if (type == null && lastSpace > 0) {
            String tail = rest.substring(lastSpace + 1).trim();
            DBType suffix = DBType.fromText(tail);
            if (suffix != null && !tail.contains(":") && !tail.contains("/")) {
                type = suffix;
                rest = rest.substring(0, lastSpace).trim();
            }
        }
        if (rest.isEmpty()) {
            return null;
        }
        // 取 host 部分：第一个空白分隔的 token
        String hostToken;
        String remainder;
        int sp = indexOfWhitespace(rest);
        if (sp < 0) {
            hostToken = rest;
            remainder = "";
        } else {
            hostToken = rest.substring(0, sp);
            remainder = rest.substring(sp).trim();
        }
        String host;
        String port;
        String inlineUser = "";
        String inlinePass = "";
        String inlineDb = "";
        int colon = hostToken.indexOf(':');
        if (colon < 0) {
            // host 后可能是 "/db"
            String ht = hostToken;
            int slash = ht.indexOf('/');
            if (slash >= 0) {
                inlineDb = ht.substring(slash + 1);
                ht = ht.substring(0, slash);
            }
            host = ht;
            port = "";
        } else {
            String hp = hostToken.substring(0, colon);
            String after = hostToken.substring(colon + 1);
            String[] parts = after.split(":", -1);
            host = hp;
            port = parts[0].trim();
            int slash = port.indexOf('/');
            if (slash >= 0) {
                // host:port/db
                inlineDb = port.substring(slash + 1);
                port = port.substring(0, slash).trim();
            }
            if (parts.length > 1) {
                String cred = parts[1].trim();
                int cs = cred.indexOf('/');
                if (cs >= 0) {
                    // host:port:user/pass(:pass 续段)
                    inlineUser = cred.substring(0, cs).trim();
                    StringBuilder sb = new StringBuilder(cred.substring(cs + 1));
                    for (int i = 2; i < parts.length; i++) {
                        sb.append(':').append(parts[i]);
                    }
                    inlinePass = sb.toString();
                } else {
                    inlineUser = cred;
                    if (parts.length > 2) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 2; i < parts.length; i++) {
                            if (i > 2) {
                                sb.append(':');
                            }
                            sb.append(parts[i]);
                        }
                        inlinePass = sb.toString();
                    }
                }
            }
        }
        if (host.isEmpty() || !isHostLike(host)) {
            return null;
        }
        if (port.isEmpty()) {
            if (type == null) {
                return null;
            }
            port = type.getDefaultPort();
        }
        if (!isPort(port)) {
            return null;
        }
        if (type == null) {
            type = DBType.fromPort(port);
        }
        if (type == null) {
            return null;
        }
        String user = unquote(inlineUser);
        String password = inlinePass;
        String database = inlineDb;
        String note = "";
        if (!remainder.isEmpty()) {
            Matcher m = CRED_SLASH.matcher(remainder);
            if (m.matches()) {
                user = unquote(m.group(1).trim());
                password = unquote(m.group(2).trim());
            } else {
                String[] toks = remainder.split("\\s+", 2);
                user = unquote(toks[0]);
                password = toks.length > 1 ? unquote(toks[1]) : "";
            }
            // user 内可能带 "/"（无空格形式）
            if (password.isEmpty() && user.contains("/")) {
                int slash = user.indexOf('/');
                password = user.substring(slash + 1);
                user = user.substring(0, slash);
            }
            // user 内可能带 ":pass"
            int cp = user.indexOf(':');
            if (cp > 0 && password.isEmpty()) {
                password = user.substring(cp + 1);
                user = user.substring(0, cp);
            }
        } else if (!inlinePass.isEmpty()) {
            password = unquote(inlinePass);
        }
        if (isUnauthorized(password)) {
            password = "";
            note = appendNote(note, "未授权访问（无密码）");
        }
        if (isUnauthorized(user)) {
            user = "";
            password = "";
            note = appendNote(note, "未授权访问（无账号）");
        }
        boolean sysdba = type == DBType.ORACLE && "sys".equalsIgnoreCase(user);
        if (sysdba) {
            note = appendNote(note, "SYSDBA");
        }
        return new ConnSpec(type, host, port, user, password, database, raw, lineNo, note, sysdba);
    }

    // ----------------------------------------------------------------- utils

    private static String appendNote(String note, String add) {
        return note.isEmpty() ? add : note + "; " + add;
    }

    private static String firstToken(String s) {
        int sp = indexOfWhitespace(s);
        return sp < 0 ? s : s.substring(0, sp);
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOf(String s, char c) {
        return s.indexOf(c);
    }

    private static boolean isPort(String s) {
        if (s == null || s.isEmpty() || s.length() > 5) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        int p = Integer.parseInt(s);
        return p > 0 && p < 65536;
    }

    /** 粗略校验 host 合法性：允许 IPv4 / IPv6 / 域名 / localhost。 */
    private static boolean isHostLike(String s) {
        if (s == null || s.isEmpty() || s.length() > 255) {
            return false;
        }
        if (s.startsWith("[") && s.endsWith("]")) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '/' || c == '\\' || c == '?' || c == '#' || c == '@') {
                return false;
            }
        }
        return true;
    }

    private static boolean isUnauthorized(String s) {
        return s != null && UNAUTHORIZED.contains(s.trim().toLowerCase(Locale.ROOT));
    }

    static String unquote(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() >= 2) {
            char first = t.charAt(0);
            char last = t.charAt(t.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return t.substring(1, t.length() - 1);
            }
        }
        return t;
    }
}
