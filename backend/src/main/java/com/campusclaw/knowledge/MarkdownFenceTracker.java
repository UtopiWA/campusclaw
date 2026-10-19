package com.campusclaw.knowledge;

/** 跟踪会影响 Markdown 结构识别的代码与块公式围栏。 */
final class MarkdownFenceTracker {
    private Character codeFenceCharacter;
    private int codeFenceLength;
    private String mathClosingDelimiter;

    boolean isOpen() {
        return codeFenceCharacter != null || mathClosingDelimiter != null;
    }

    void consume(String line) {
        String trimmed = line.trim();
        if (codeFenceCharacter != null) {
            if (isCodeFenceClosing(trimmed, codeFenceCharacter, codeFenceLength)) {
                codeFenceCharacter = null;
                codeFenceLength = 0;
            }
            return;
        }
        if (mathClosingDelimiter != null) {
            if (trimmed.equals(mathClosingDelimiter)) {
                mathClosingDelimiter = null;
            }
            return;
        }

        int fenceLength = codeFenceLength(trimmed);
        if (fenceLength >= 3) {
            codeFenceCharacter = trimmed.charAt(0);
            codeFenceLength = fenceLength;
        } else if (trimmed.equals("$$")) {
            mathClosingDelimiter = "$$";
        } else if (trimmed.equals("\\[")) {
            mathClosingDelimiter = "\\]";
        }
    }

    private int codeFenceLength(String value) {
        if (value.isEmpty() || (value.charAt(0) != '`' && value.charAt(0) != '~')) {
            return 0;
        }
        char marker = value.charAt(0);
        int length = 0;
        while (length < value.length() && value.charAt(length) == marker) {
            length++;
        }
        return length;
    }

    private boolean isCodeFenceClosing(String value, char marker, int minimumLength) {
        int length = 0;
        while (length < value.length() && value.charAt(length) == marker) {
            length++;
        }
        if (length < minimumLength) {
            return false;
        }
        for (int index = length; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}

