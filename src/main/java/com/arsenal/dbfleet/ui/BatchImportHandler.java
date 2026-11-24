package com.arsenal.dbfleet.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.runtime.DBWorkbench;

/**
 * 菜单 / 右键动作入口：打开批量导入对话框。
 */
public class BatchImportHandler extends AbstractHandler {

    private static final Log log = Log.getLog(BatchImportHandler.class);

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        if (shell == null) {
            shell = org.jkiss.dbeaver.ui.UIUtils.getActiveShell();
        }
        try {
            if (DBWorkbench.getPlatform() == null
                    || DBWorkbench.getPlatform().getWorkspace().getActiveProject() == null) {
                DBWorkbench.getPlatformUI().showError("批量导入连接", "没有可用的 DBeaver 项目", (Throwable) null);
                return null;
            }
            BatchImportDialog dialog = new BatchImportDialog(shell);
            dialog.open();
        } catch (Throwable e) {
            log.error("Batch import failed", e);
            DBWorkbench.getPlatformUI().showError("批量导入连接", "打开导入窗口失败", e);
        }
        return null;
    }
}
