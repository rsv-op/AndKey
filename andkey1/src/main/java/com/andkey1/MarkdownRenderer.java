package com.andkey1;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.util.ArrayList;
import java.util.List;

/**
 * Tiny Markdown → Spannable renderer, enough for the bundled guide:
 * headings, fenced code blocks, inline code, bold, tables, bullets, quotes,
 * horizontal rules and links. No dependencies, no HTML.
 */
final class MarkdownRenderer {

    private MarkdownRenderer() {
    }

    static CharSequence render(String markdown) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = markdown.split("\n", -1);
        boolean inFence = false;
        for (String raw : lines) {
            String line = raw.replace("\r", "");
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                out.append('\n');
                continue;
            }
            if (inFence) {
                appendMono(out, line + "\n");
                continue;
            }
            if (trimmed.isEmpty()) {
                out.append('\n');
                continue;
            }
            if (trimmed.equals("---") || trimmed.equals("***")) {
                String rule = "────────────────────────────";
                int start = out.length();
                out.append(rule).append('\n');
                out.setSpan(new ForegroundColorSpan(0xFF9E9E9E), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            if (trimmed.startsWith("#")) {
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                    level++;
                }
                String text = trimmed.substring(level).trim();
                int start = out.length();
                out.append(text).append('\n');
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                float scale = level <= 1 ? 1.45f : (level == 2 ? 1.25f : 1.12f);
                out.setSpan(new RelativeSizeSpan(scale), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }
            if (trimmed.startsWith("|")) {
                appendMono(out, stripTable(trimmed) + "\n");
                continue;
            }
            if (trimmed.startsWith(">")) {
                int start = out.length();
                out.append(trimmed.substring(1).trim()).append('\n');
                out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(0xFF546E7A), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                appendInline(out, "•  " + trimmed.substring(2));
                out.append('\n');
                continue;
            }
            if (trimmed.matches("^\\d+\\.\\s.*")) {
                appendInline(out, trimmed);
                out.append('\n');
                continue;
            }
            appendInline(out, trimmed);
            out.append('\n');
        }
        return out;
    }

    private static String stripTable(String row) {
        String s = row.startsWith("|") ? row.substring(1) : row;
        if (s.endsWith("|")) {
            s = s.substring(0, s.length() - 1);
        }
        String[] cells = s.split("\\|", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(" │ ");
            }
            sb.append(cells[i].trim());
        }
        return sb.toString();
    }

    private static void appendMono(SpannableStringBuilder out, String text) {
        // Code blocks keep their indentation, so append verbatim.
        int start = out.length();
        out.append(text);
        out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(0.88f), start, out.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new BackgroundColorSpan(0xFFF1F3F4), start, out.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Handles inline `code`, **bold** and [text](url). */
    private static void appendInline(SpannableStringBuilder out, String text) {
        List<int[]> spans = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end < 0) {
                    break;
                }
                int start = out.length();
                out.append(text, i + 1, end);
                spans.add(new int[]{start, out.length(), 1});
                i = end + 1;
                continue;
            }
            if (c == '*' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int end = text.indexOf("**", i + 2);
                if (end < 0) {
                    break;
                }
                int start = out.length();
                out.append(text, i + 2, end);
                spans.add(new int[]{start, out.length(), 2});
                i = end + 2;
                continue;
            }
            if (c == '[') {
                int close = text.indexOf(']', i + 1);
                if (close > 0 && close + 1 < text.length() && text.charAt(close + 1) == '(') {
                    int paren = text.indexOf(')', close + 2);
                    if (paren > 0) {
                        int start = out.length();
                        out.append(text, i + 1, close);
                        spans.add(new int[]{start, out.length(), 3});
                        out.append(' ');
                        out.append('(').append(text, close + 2, paren).append(')');
                        i = paren + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        for (int[] s : spans) {
            switch (s[2]) {
                case 1:
                    out.setSpan(new TypefaceSpan("monospace"), s[0], s[1],
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case 2:
                    out.setSpan(new StyleSpan(Typeface.BOLD), s[0], s[1],
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                default:
                    out.setSpan(new ForegroundColorSpan(0xFF0B57D0), s[0], s[1],
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
            }
        }
    }
}
