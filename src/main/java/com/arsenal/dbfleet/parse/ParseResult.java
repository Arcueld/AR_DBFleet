package com.arsenal.dbfleet.parse;

import com.arsenal.dbfleet.model.ConnSpec;

import java.util.ArrayList;
import java.util.List;

/** 一次解析的全部结果：成功项 + 无法解析的行。 */
public class ParseResult {

    private final List<ConnSpec> connections = new ArrayList<>();
    private final List<BadLine> badLines = new ArrayList<>();
    private final List<BadLine> duplicateLines = new ArrayList<>();

    public List<ConnSpec> getConnections() {
        return connections;
    }

    public List<BadLine> getBadLines() {
        return badLines;
    }

    public List<BadLine> getDuplicateLines() {
        return duplicateLines;
    }

    public boolean isEmpty() {
        return connections.isEmpty();
    }

    /** 一行无法解析的记录。 */
    public static class BadLine {
        private final int lineNo;
        private final String text;
        private final String reason;

        public BadLine(int lineNo, String text, String reason) {
            this.lineNo = lineNo;
            this.text = text;
            this.reason = reason;
        }

        public int getLineNo() {
            return lineNo;
        }

        public String getText() {
            return text;
        }

        public String getReason() {
            return reason;
        }

        @Override
        public String toString() {
            return "第 " + lineNo + " 行: " + text + "   → " + reason;
        }
    }
}
