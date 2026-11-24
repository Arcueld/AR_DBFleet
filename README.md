# AR_DBFleet

DBeaver 批量导入数据库连接插件。

把 fscan 输出、JDBC URL、自定义连接串粘贴进来，一键**创建 → 实测 → 只保留连通的连接**，密码一并保存，全部落到你指定的文件夹。连不上的不会写进连接列表，失败原因逐条记录在表格里。

适用 DBeaver **23.3.0**（理论兼容 23.x）。以独立 OSGi bundle 通过 `dropins` 部署，不需要 DBeaver 源码，不修改安装目录里的任何原有文件。

## 特性

- 三种输入格式混写：fscan 输出 / JDBC URL / 自定义连接串
- 逐条真实建连测试，**只保留连通的**，失败的不落库
- 密码自动保存，全部归入指定文件夹（支持 `a/b/c` 嵌套）
- 预览表逐行显示 `状态` 与 `结果说明`：连通写「可连接」，失败写具体报错
- 导入结束后汇总，并单独列出所有无法解析的行与重复行
- 支持 MySQL/MariaDB、PostgreSQL、SQL Server、Oracle、Redis、MongoDB、ClickHouse

## 安装

1. 下载 release 里的 `com.arsenal.dbfleet_1.0.0.jar`
2. 关闭 DBeaver
3. 放到 DBeaver 安装目录的 `dropins/` 下
4. 若未自动加载，在 `configuration/org.eclipse.equinox.simpleconfigurator/bundles.info` 末尾追加一行（路径相对 `configuration/`，不要写 `../`，不要转义）：

```
com.arsenal.dbfleet,1.0.0,dropins/com.arsenal.dbfleet_1.0.0.jar,4,false
```

5. 启动 DBeaver

卸载：删除该 jar，并删掉 `bundles.info` 里那一行。

## 使用

菜单 **数据库 → 批量导入连接...**，或在数据库导航器右键 → **批量导入连接...**。

1. 粘贴连接串，每行一条
2. 点 **解析预览** 确认解析结果
3. 选择或输入目标文件夹（留空则不分组）
4. 点 **测试并导入**

预览表列：`# / 类型 / 主机 / 端口 / 数据库 / 账号 / 密码 / 备注 / 状态 / 结果说明`。

## 输入格式

**fscan 输出**

```
[+] Postgres:192.0.2.10:5432:appuser 'P@ssw0rd1'
[+] mssql 192.0.2.10:1433:sa P@ssw0rd2
[+] oracle 192.0.2.10:1521:sys P@ssw0rd4
[+] Redis 192.0.2.10:6379 unauthorized
```

`unauthorized` / `noauth` / `none` / `-` 视作空密码；`sys` 账号自动按 `SYSDBA` 登录 Oracle；省略 `[+]` 前缀也能识别。

**JDBC URL**

```
jdbc:postgresql://192.0.2.11:5432/app?user=app&password=P%40ss
jdbc:oracle:thin:@//192.0.2.12:1521/ORCL
jdbc:sqlserver://192.0.2.13:1433;databaseName=master;user=sa;password=P%40ss
```

**自定义**

```
192.0.2.10:1433 sa / P@ssw0rd2
192.0.2.10:1433 sa / P@ssw0rd2 oracle        # 类型后缀
oracle 192.0.2.10:1521:sa/P@ssw0rd4          # 类型前缀
192.0.2.10:3306:root:pa:ss                   # 内联，密码可含冒号
192.0.2.11:5432/app app P@ssw0rd1             # host:port/db user pass
```

不写类型时按端口推断（3306/3307、5432/5433、1433/1434、1521/1522、6379/6380、27017/27018、8123/9000）。**非默认端口又没写类型 ⇒ 该行判为无法解析**并列入结果区，不做猜测。

## 从源码构建

```bash
bash tools/build.sh
```

产物 `dist/com.arsenal.dbfleet_1.0.0.jar`。依赖本机已安装的 DBeaver（编译时读取其 plugins 下的 jar）：

| 环境变量 | 默认值 |
|---|---|
| `DBEAVER_HOME` | `E:/dbEaver` |
| `JDK_HOME` | `C:/Program Files/Java/jdk-18.0.2.1` |

要求 JDK 能执行 `javac --release 17`。

## 说明

- 建连测试走 DBeaver 自身的 `DataSourceDescriptor.connect(...)`，与手动建连语义一致
- 只把测试通过的连接交给 `DataSourceRegistry.addDataSource(...)`
- Oracle 的 SID/SERVICE 无法从 fscan 行获得，按 `orcl → XE → ORCL → pdborcl` 逐个尝试，第一个成功者采用并在结果中回报
- 连接命名带类型与账号（如 `MySQL 192.0.2.10:3306 root`），重名自动加 ` (2)`
- 对话框内的示例连接串每次随机生成，源码不含任何真实环境信息
