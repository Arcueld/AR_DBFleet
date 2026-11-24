package com.arsenal.dbfleet.ui;

import com.arsenal.dbfleet.core.ConnectionImporter;
import com.arsenal.dbfleet.core.ImportEngine;
import com.arsenal.dbfleet.model.ConnSpec;
import com.arsenal.dbfleet.model.DBType;
import com.arsenal.dbfleet.parse.ConnectionParser;
import com.arsenal.dbfleet.parse.ParseResult;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.registry.DataSourceRegistry;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.ConnectionFolderSelector;
import org.jkiss.dbeaver.ui.dialogs.BaseDialog;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 批量导入对话框：粘贴连接串 → 预览解析 → 逐个实测 → 只保留连通的连接。
 */
public class BatchImportDialog extends BaseDialog {

    private static final Log log = Log.getLog(BatchImportDialog.class);

    private Text inputText;
    private Text folderText;
    private Text resultText;
    private Table previewTable;
    private ConnectionFolderSelector folderSelector;
    private Label statusLabel;
    private Button parseButton;
    private Button importButton;
    private Button closeButton;

    private ParseResult parsed = new ParseResult();
    private final List<String> previewStatus = new ArrayList<>();
    /** 与 previewStatus 同下标：连通写「可连接」，否则写失败原因。 */
    private final List<String> previewDetail = new ArrayList<>();
    private volatile boolean running;

    public BatchImportDialog(Shell shell) {
        super(shell, "批量导入连接", null);
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite area = (Composite) super.createContents(parent);
        getShell().setMinimumSize(900, 640);
        return area;
    }

    @Override
    protected Composite createDialogArea(Composite parent) {
        Composite composite = (Composite) super.createDialogArea(parent);
        composite.setLayout(new GridLayout(1, false));
        composite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createInputSection(composite);
        createFolderSection(composite);
        createPreviewSection(composite);
        createResultSection(composite);

        refreshPreview();
        return composite;
    }

    // ------------------------------------------------------------------- UI

    private void createInputSection(Composite parent) {
        UIUtils.createControlLabel(parent, "连接串（每行一条，支持 fscan 输出 / JDBC URL / 自定义格式）");
        inputText = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.WRAP);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, false);
        gd.heightHint = 150;
        gd.widthHint = 820;
        inputText.setLayoutData(gd);
        inputText.setFont(hintFont(inputText));
        inputText.setText(sampleText());

        Composite buttons = new Composite(parent, SWT.NONE);
        buttons.setLayout(new GridLayout(3, false));
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        parseButton = new Button(buttons, SWT.PUSH);
        parseButton.setText("解析预览");
        parseButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        parseButton.addListener(SWT.Selection, e -> doParse());

        importButton = new Button(buttons, SWT.PUSH);
        importButton.setText("测试并导入");
        importButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        importButton.addListener(SWT.Selection, e -> doImport());

        statusLabel = new Label(buttons, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private Font hintFont(Control c) {
        return new Font(c.getDisplay(), "Consolas", 9, SWT.NORMAL);
    }

    private void createFolderSection(Composite parent) {
        Composite folderComposite = new Composite(parent, SWT.NONE);
        folderComposite.setLayout(new GridLayout(2, false));
        folderComposite.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        folderSelector = new ConnectionFolderSelector(folderComposite);

        DBPProject project = NavigatorUtils.getSelectedProject();
        if (project == null && DBWorkbench.getPlatform() != null) {
            project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        }
        if (project != null) {
            try {
                folderSelector.loadConnectionFolders(project);
            } catch (Throwable e) {
                log.debug("loadConnectionFolders failed", e);
            }
        }

        folderText = new Text(folderComposite, SWT.BORDER);
        GridData gd2 = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd2.horizontalSpan = 2;
        folderText.setLayoutData(gd2);
        folderText.setMessage("或直接输入新文件夹路径（支持 a/b/c 嵌套，留空则用上方选择）");
    }

    private void createPreviewSection(Composite parent) {
        UIUtils.createControlLabel(parent, "解析结果");
        previewTable = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE
                | SWT.H_SCROLL | SWT.V_SCROLL);
        previewTable.setHeaderVisible(true);
        previewTable.setLinesVisible(true);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = 180;
        previewTable.setLayoutData(gd);

        UIUtils.createTableColumn(previewTable, SWT.LEFT, "#");
        UIUtils.createTableColumn(previewTable, SWT.LEFT, "类型");
        UIUtils.createTableColumn(previewTable, SWT.LEFT, "主机");
        UIUtils.createTableColumn(previewTable, SWT.LEFT, "端口");
        UIUtils.createTableColumn(previewTable, SWT.LEFT, "数据库");
        UIUtils.createTableColumn(previewTable, SWT.LEFT, "账号");
        UIUtils.createTableColumn(previewTable, SWT.LEFT, "密码");
        UIUtils.createTableColumn(previewTable, SWT.LEFT, "备注");
        UIUtils.createTableColumn(previewTable, SWT.LEFT, "状态");
        UIUtils.createTableColumn(previewTable, SWT.LEFT, "结果说明");
    }

    private void createResultSection(Composite parent) {
        UIUtils.createControlLabel(parent, "执行结果");
        resultText = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = 120;
        resultText.setLayoutData(gd);
        resultText.setFont(hintFont(resultText));
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        closeButton = createButton(parent, IDialogConstants.CLOSE_ID, "关闭", false);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == IDialogConstants.CLOSE_ID) {
            if (running) {
                UIUtils.showMessageBox(getShell(), "批量导入", "仍在导入中，请等待完成", SWT.ICON_WARNING);
                return;
            }
            close();
            return;
        }
        super.buttonPressed(buttonId);
    }

    // -------------------------------------------------------------- actions

    private void doParse() {
        String text = inputText.getText();
        parsed = new ConnectionParser().parse(text);
        previewStatus.clear();
        previewDetail.clear();
        for (int i = 0; i < parsed.getConnections().size(); i++) {
            previewStatus.add("待导入");
            previewDetail.add("");
        }
        refreshPreview();
        StringBuilder sb = new StringBuilder();
        sb.append("解析完成：可导入 ").append(parsed.getConnections().size()).append(" 条");
        if (!parsed.getDuplicateLines().isEmpty()) {
            sb.append("，重复跳过 ").append(parsed.getDuplicateLines().size()).append(" 条");
        }
        if (!parsed.getBadLines().isEmpty()) {
            sb.append("，无法解析 ").append(parsed.getBadLines().size()).append(" 条");
        }
        statusLabel.setText(sb.toString());
        if (!parsed.getBadLines().isEmpty() || !parsed.getDuplicateLines().isEmpty()) {
            resultText.setText(buildParseReport());
        }
    }

    private String buildParseReport() {
        StringBuilder sb = new StringBuilder();
        if (!parsed.getBadLines().isEmpty()) {
            sb.append("=== 无法解析的行（请修正后重新粘贴） ===\n");
            for (ParseResult.BadLine bl : parsed.getBadLines()) {
                sb.append("第 ").append(bl.getLineNo()).append(" 行: ").append(bl.getText())
                        .append("\n    原因: ").append(bl.getReason()).append('\n');
            }
            sb.append('\n');
        }
        if (!parsed.getDuplicateLines().isEmpty()) {
            sb.append("=== 重复行（已跳过） ===\n");
            for (ParseResult.BadLine bl : parsed.getDuplicateLines()) {
                sb.append("第 ").append(bl.getLineNo()).append(" 行: ").append(bl.getText()).append('\n');
            }
        }
        return sb.toString();
    }

    private void doImport() {
        if (running) {
            return;
        }
        doParse();
        if (parsed.getConnections().isEmpty()) {
            UIUtils.showMessageBox(getShell(), "批量导入", "没有可导入的连接", SWT.ICON_WARNING);
            return;
        }
        DataSourceRegistry registry;
        try {
            DBPProject project = NavigatorUtils.getSelectedProject();
            if (project == null && DBWorkbench.getPlatform() != null) {
                project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
            }
            if (project == null) {
                UIUtils.showMessageBox(getShell(), "批量导入", "没有可用的 DBeaver 项目", SWT.ICON_ERROR);
                return;
            }
            if (!project.isRegistryLoaded()) {
                project.ensureOpen();
            }
            registry = (DataSourceRegistry) project.getDataSourceRegistry();
        } catch (Throwable e) {
            log.error("Cannot get data source registry", e);
            UIUtils.showMessageBox(getShell(), "批量导入", "无法访问连接列表: " + e.getMessage(), SWT.ICON_ERROR);
            return;
        }

        final String folderPath = resolveFolderPath(registry);
        final List<ConnSpec> specs = new ArrayList<>(parsed.getConnections());
        final String inputSnapshot = inputText.getText();

        // 记录导入前的连接集合，成功后刷新导航器
        running = true;
        importButton.setEnabled(false);
        parseButton.setEnabled(false);
        statusLabel.setText("开始导入...");
        resultText.setText("");
        for (int i = 0; i < previewStatus.size(); i++) {
            previewStatus.set(i, "等待");
            previewDetail.set(i, "");
        }
        refreshPreview();

        Thread worker = new Thread(() -> {
            final List<ConnectionImporter.ImportResult> results = new ArrayList<>();
            try {
                ImportEngine engine = new ImportEngine(registry);
                engine.run(specs, folderPath, true, new ImportEngine.Listener() {
                    @Override
                    public void onProgress(int done, int total, String current) {
                        uiAsync(() -> {
                            if (statusLabel.isDisposed()) {
                                return;
                            }
                            statusLabel.setText("进度 " + done + "/" + total + "  " + current);
                        });
                    }

                    @Override
                    public void onResult(ConnectionImporter.ImportResult result) {
                        results.add(result);
                        int idx = specs.indexOf(result.spec);
                        uiAsync(() -> {
                            if (previewTable.isDisposed()) {
                                return;
                            }
                            try {
                                applyRowResult(idx, result);
                            } catch (Throwable e) {
                                log.debug("applyRowResult failed", e);
                            } finally {
                                refreshPreview();
                            }
                        });
                    }

                    @Override
                    public void onDriverMissing(DBType type, DBPDriver driver) {
                        uiAsync(() -> appendResult("!! 驱动未下载: " + driver.getFullName()
                                + " (" + type.getLabel() + ")\n"));
                    }
                });
            } catch (Throwable e) {
                log.error("Import failed", e);
                uiAsync(() -> appendResult("导入异常: " + e + "\n"));
            } finally {
                final List<ConnectionImporter.ImportResult> snapshot = new ArrayList<>(results);
                uiAsync(() -> finishImport(registry, specs, snapshot, inputSnapshot));
            }
        }, "AR-DBFleet-Importer");
        worker.setDaemon(true);
        worker.start();
    }

    /** 把单条结果写回预览表。字段可能为 null（历史调用方），一律按空串处理。 */
    private void applyRowResult(int idx, ConnectionImporter.ImportResult result) {
        if (idx < 0 || idx >= previewStatus.size()) {
            return;
        }
        String warn = result.warning == null ? "" : result.warning;
        previewStatus.set(idx, result.success ? "已导入" : "失败");
        previewDetail.set(idx, result.success
                ? ("可连接" + (warn.isEmpty() ? "" : "（" + warn + "）"))
                : oneLine(result.error));
    }

    private void finishImport(DataSourceRegistry registry, List<ConnSpec> specs,
                              List<ConnectionImporter.ImportResult> results, String inputSnapshot) {
        running = false;
        if (importButton.isDisposed()) {
            return;
        }
        importButton.setEnabled(true);
        parseButton.setEnabled(true);

        int ok = 0;
        int fail = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("=== 导入结果  ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append(" ===\n");
        for (ConnectionImporter.ImportResult r : results) {
            if (r.success) {
                ok++;
                sb.append("OK   ").append(r.spec.getType().getLabel()).append(' ')
                        .append(r.spec.getHost()).append(':').append(r.spec.getPort())
                        .append("  →  ").append(r.name);
                if (!r.database.isEmpty() && r.spec.getType() == DBType.ORACLE) {
                    sb.append("  (SID/SERVICE=").append(r.database).append(')');
                }
                if (r.warning != null && !r.warning.isEmpty()) {
                    sb.append("  [").append(r.warning).append(']');
                }
                sb.append('\n');
            } else {
                fail++;
                sb.append("FAIL ").append(r.spec.getType().getLabel()).append(' ')
                        .append(r.spec.getHost()).append(':').append(r.spec.getPort())
                        .append("  ").append(r.error == null ? "连接失败" : r.error).append('\n');
            }
        }
        sb.append("\n合计: 成功 ").append(ok).append(" 条，失败 ").append(fail)
                .append(" 条，共 ").append(specs.size()).append(" 条\n");

        // 重新解析一遍原始输入，报告无法解析的行（用户要求：全部有效行处理完后再报告）
        ParseResult reparse = new ConnectionParser().parse(inputSnapshot);
        if (!reparse.getBadLines().isEmpty() || !reparse.getDuplicateLines().isEmpty()) {
            sb.append('\n');
            sb.append(buildReport(reparse));
        }
        resultText.setText(sb.toString());
        statusLabel.setText("完成: 成功 " + ok + " 条，失败 " + fail + " 条");

        try {
            registry.flushConfig();
        } catch (Throwable e) {
            log.debug("flushConfig failed", e);
        }
    }

    private String buildReport(ParseResult pr) {
        StringBuilder sb = new StringBuilder();
        if (!pr.getBadLines().isEmpty()) {
            sb.append("=== 无法解析的行（未导入，请修正后重试） ===\n");
            for (ParseResult.BadLine bl : pr.getBadLines()) {
                sb.append("第 ").append(bl.getLineNo()).append(" 行: ").append(bl.getText())
                        .append("\n    原因: ").append(bl.getReason()).append('\n');
            }
        }
        if (!pr.getDuplicateLines().isEmpty()) {
            sb.append("\n=== 重复行（已跳过） ===\n");
            for (ParseResult.BadLine bl : pr.getDuplicateLines()) {
                sb.append("第 ").append(bl.getLineNo()).append(" 行: ").append(bl.getText()).append('\n');
            }
        }
        return sb.toString();
    }

    private String resolveFolderPath(DataSourceRegistry registry) {
        String typed = folderText.getText().trim();
        if (!typed.isEmpty()) {
            return typed.replace('\\', '/');
        }
        try {
            var folder = folderSelector.getFolder();
            if (folder != null) {
                String path = folder.getFolderPath();
                return path == null ? "" : path;
            }
        } catch (Throwable e) {
            log.debug("folder selector getFolder failed", e);
        }
        return "";
    }

    // ---------------------------------------------------------------- table

    private void refreshPreview() {
        if (previewTable == null || previewTable.isDisposed()) {
            return;
        }
        previewTable.setRedraw(false);
        try {
            previewTable.removeAll();
            List<ConnSpec> list = parsed.getConnections();
            for (int i = 0; i < list.size(); i++) {
                ConnSpec s = list.get(i);
                TableItem item = new TableItem(previewTable, SWT.NONE);
                item.setText(0, String.valueOf(i + 1));
                item.setText(1, s.getType().getLabel());
                item.setText(2, s.getHost());
                item.setText(3, s.getPort());
                item.setText(4, s.getDatabase().isEmpty()
                        ? s.getType().getDefaultDatabase() : s.getDatabase());
                item.setText(5, s.getUser());
                item.setText(6, maskPassword(s.getPassword()));
                item.setText(7, s.getNote());
                item.setText(8, i < previewStatus.size() ? previewStatus.get(i) : "");
                item.setText(9, i < previewDetail.size() ? previewDetail.get(i) : "");
            }
            for (int c = 0; c < previewTable.getColumnCount(); c++) {
                previewTable.getColumn(c).pack();
            }
            TableColumn[] cols = previewTable.getColumns();
            if (cols.length > 6 && cols[6].getWidth() > 160) {
                cols[6].setWidth(160);
            }
            if (cols.length > 9) {
                cols[9].setWidth(Math.min(cols[9].getWidth(), 420));
            }
        } finally {
            previewTable.setRedraw(true);
        }
    }

    /** 失败原因压成一行，避免表格被撑爆。 */
    private static String oneLine(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = text.replace('\r', ' ').replace('\n', ' ');
        while (s.contains("  ")) {
            s = s.replace("  ", " ");
        }
        return s.trim();
    }

    private static String maskPassword(String pwd) {
        if (pwd == null || pwd.isEmpty()) {
            return "";
        }
        if (pwd.length() <= 4) {
            return "****";
        }
        return pwd.charAt(0) + "************" + pwd.charAt(pwd.length() - 1);
    }

    private void appendResult(String text) {
        if (resultText.isDisposed()) {
            return;
        }
        resultText.append(text);
    }

    private void uiAsync(Runnable r) {
        Display display = getShell() == null ? Display.getDefault() : getShell().getDisplay();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            if (getShell() == null || getShell().isDisposed()) {
                return;
            }
            try {
                r.run();
            } catch (Throwable e) {
                log.debug("UI callback failed", e);
            }
        });
    }

    private static String sampleText() {
        return SampleSupport.sample();
    }

    // ------------------------------------------------------------ finalize

    @Override
    public boolean close() {
        if (running) {
            return false;
        }
        return super.close();
    }
}
