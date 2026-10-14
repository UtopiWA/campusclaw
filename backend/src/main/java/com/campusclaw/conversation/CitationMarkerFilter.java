package com.campusclaw.conversation;

/** 无依据回答的增量过滤器，可在网络分片边界上移除完整的数字引用标记。 */
public class CitationMarkerFilter {
    private final StringBuilder pending = new StringBuilder();

    public String accept(String delta) {
        if (delta != null) {
            pending.append(delta);
        }
        return drain(false);
    }

    public String finish() {
        return drain(true);
    }

    private String drain(boolean finishing) {
        StringBuilder output = new StringBuilder();
        int cursor = 0;
        while (cursor < pending.length()) {
            int open = pending.indexOf("[", cursor);
            if (open < 0) {
                output.append(pending, cursor, pending.length());
                cursor = pending.length();
                break;
            }
            output.append(pending, cursor, open);
            int close = pending.indexOf("]", open + 1);
            if (close < 0) {
                if (finishing) {
                    output.append(pending, open, pending.length());
                    cursor = pending.length();
                } else {
                    cursor = open;
                }
                break;
            }
            String number = pending.substring(open + 1, close);
            if (!number.isEmpty() && number.codePoints().allMatch(Character::isDigit)) {
                cursor = close + 1;
            } else {
                output.append('[');
                cursor = open + 1;
            }
        }
        pending.delete(0, cursor);
        return output.toString();
    }
}
