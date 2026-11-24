package com.arsenal.dbfleet.core;

import com.arsenal.dbfleet.model.ConnSpec;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.registry.DataSourceRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量导入引擎：按类型归组、逐个驱动解析、逐个连接实测。
 * 全部在后台线程执行，UI 通过回调收到每一行结果。
 */
public class ImportEngine {

    private final DataSourceRegistry registry;

    public ImportEngine(DataSourceRegistry registry) {
        this.registry = registry;
    }

    /** 进度回调。 */
    public interface Listener {
        void onProgress(int done, int total, String current);

        void onResult(ConnectionImporter.ImportResult result);

        /** 驱动不可用（文件未下载）。 */
        void onDriverMissing(com.arsenal.dbfleet.model.DBType type, DBPDriver driver);
    }

    /**
     * 导入全部条目。
     *
     * @return 与输入顺序一致的结果列表
     */
    public List<ConnectionImporter.ImportResult> run(List<ConnSpec> specs, String folderPath,
                                                     boolean savePassword, Listener listener) {
        ConnectionImporter importer = new ConnectionImporter(registry);

        // 驱动只解析一次
        Map<com.arsenal.dbfleet.model.DBType, DBPDriver> drivers = new LinkedHashMap<>();
        List<ConnectionImporter.ImportResult> results = new ArrayList<>();

        int index = 0;
        for (ConnSpec spec : specs) {
            index++;
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            String current = spec.getType().getLabel() + " " + spec.getHost() + ":" + spec.getPort();
            if (listener != null) {
                listener.onProgress(index - 1, specs.size(), current);
            }

            DBPDriver driver = drivers.get(spec.getType());
            if (driver == null) {
                driver = ConnectionImporter.resolveDriver(spec.getType());
                if (driver != null) {
                    drivers.put(spec.getType(), driver);
                }
            }
            if (driver == null) {
                ConnectionImporter.ImportResult r = new ConnectionImporter.ImportResult(spec);
                r.error = "本机未安装 " + spec.getType().getLabel() + " 驱动";
                results.add(r);
                if (listener != null) {
                    listener.onResult(r);
                }
                continue;
            }
            if (!ConnectionImporter.isDriverReady(driver)) {
                if (listener != null) {
                    listener.onDriverMissing(spec.getType(), driver);
                }
                ConnectionImporter.ImportResult r = new ConnectionImporter.ImportResult(spec);
                r.error = "驱动 " + driver.getFullName() + " 文件未下载，请先在 数据库 → 驱动管理器 中下载";
                results.add(r);
                if (listener != null) {
                    listener.onResult(r);
                }
                continue;
            }

            ConnectionImporter.ImportResult result;
            try {
                result = importer.importOne(spec, driver, folderPath, null, savePassword);
            } catch (Throwable e) {
                result = new ConnectionImporter.ImportResult(spec);
                result.error = String.valueOf(e);
            }
            results.add(result);
            if (listener != null) {
                listener.onResult(result);
            }
        }
        if (listener != null) {
            listener.onProgress(specs.size(), specs.size(), "完成");
        }
        return results;
    }
}
