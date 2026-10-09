package com.termux.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dangerous-command guard for Termux+.
 *
 * Analyzes a command line about to be executed and returns a verdict:
 * SAFE, CAUTION, or DANGEROUS, with a human-readable reason. The verdict
 * drives a confirmation dialog before the command is sent to the shell.
 *
 * This finally gives the "command filtering" setting in the Quick Settings
 * panel a real consumer.
 *
 * The analysis is deliberately conservative and string-based (no regex
 * gymnastics): it looks at each shell segment and only blocks commands that
 * are plausibly destructive - it should never crash, and when in doubt it
 * returns SAFE.
 */
public final class CommandGuard {

    public enum Level { SAFE, CAUTION, DANGEROUS }

    public static class Verdict {
        public final Level level;
        public final String reason;

        Verdict(Level level, String reason) {
            this.level = level;
            this.reason = reason;
        }

        /** True when the command should require explicit user confirmation. */
        public boolean isBlocked() {
            return level != Level.SAFE;
        }

        public boolean isDangerous() {
            return level == Level.DANGEROUS;
        }
    }

    private static final Verdict SAFE_VERDICT = new Verdict(Level.SAFE, null);

    private CommandGuard() {}

    /**
     * Inspect a full command line (may contain ; && || | separators).
     */
    public static Verdict inspect(String commandLine) {
        if (commandLine == null) return SAFE_VERDICT;

        String cleaned = commandLine.trim();
        // Strip common prompt decorations so "$ rm -rf /" still matches.
        while (!cleaned.isEmpty() && ("$#❯>".indexOf(cleaned.charAt(0)) >= 0)) {
            cleaned = cleaned.substring(1).trim();
        }
        if (cleaned.isEmpty()) return SAFE_VERDICT;

        Verdict worst = SAFE_VERDICT;
        for (String segment : splitSegments(cleaned)) {
            Verdict v = inspectSegment(segment.trim());
            if (v.level == Level.DANGEROUS) return v; // one hard match is enough
            if (v.level == Level.CAUTION && worst.level == Level.SAFE) worst = v;
        }
        return worst;
    }

    private static List<String> splitSegments(String line) {
        List<String> out = new ArrayList<>();
        // Split on shell separators: ; && || |
        String[] parts = line.split("&&|\\|\\||;|\\|");
        for (String p : parts) {
            if (p != null && !p.trim().isEmpty()) out.add(p);
        }
        return out;
    }

    private static Verdict inspectSegment(String segment) {
        if (segment.isEmpty()) return SAFE_VERDICT;

        List<String> tokens = tokenize(segment);
        if (tokens.isEmpty()) return SAFE_VERDICT;

        String cmd = basename(tokens.get(0));

        // ---- Filesystem-level destruction --------------------------------
        if (cmd.startsWith("mkfs")) {
            return dangerous("format a filesystem");
        }

        if (cmd.equals("dd")) {
            if (hasRawDeviceTarget(tokens)) {
                return dangerous("write raw data directly to a disk device");
            }
            return SAFE_VERDICT;
        }

        // Redirection into a raw device, e.g. "cat x > /dev/sda"
        if (hasRawDeviceTarget(tokens)) {
            return dangerous("write raw data directly to a disk device");
        }

        // Fork bomb
        if (segment.contains(":(){")) {
            return dangerous("run a fork bomb");
        }

        // ---- rm ------------------------------------------------------------
        if (cmd.equals("rm")) {
            boolean recursive = false;
            boolean force = false;
            List<String> targets = new ArrayList<>();
            for (int i = 1; i < tokens.size(); i++) {
                String t = tokens.get(i);
                if (t.startsWith("-") && !t.startsWith("--")) {
                    if (t.contains("r")) recursive = true;
                    if (t.contains("f")) force = true;
                } else {
                    targets.add(t);
                }
            }
            if (recursive && hasDestructiveTarget(targets)) {
                return dangerous("recursively delete " + describeTargets(targets));
            }
            if (recursive && force && !targets.isEmpty()) {
                return caution("recursively force-delete files without prompting");
            }
            if (recursive) {
                return caution("recursively delete files");
            }
            return SAFE_VERDICT;
        }

        // ---- chmod/chown on roots -------------------------------------------
        if (cmd.equals("chmod") || cmd.equals("chown") || cmd.equals("chgrp")) {
            boolean recursive = false;
            for (int i = 1; i < tokens.size(); i++) {
                String t = tokens.get(i);
                if (t.equals("-R") || t.equals("-r") || t.contains("recursive")) recursive = true;
            }
            if (recursive && hasDestructiveTarget(tokens)) {
                return caution("recursively change permissions/ownership on " + describeTargets(tokens));
            }
            return SAFE_VERDICT;
        }

        // ---- Remote scripts piped into a shell ------------------------------
        String lower = cmd.toLowerCase(Locale.ROOT);
        if (lower.equals("curl") || lower.equals("wget")) {
            String joined = String.join(" ", tokens);
            String flat = joined.replace(" ", "");
            if (flat.contains("|sh") || flat.contains("|bash") || flat.contains("|zsh")
                || flat.contains("|sudo") || joined.contains("| sh") || joined.contains("| bash")) {
                return caution("pipe a downloaded script straight into a shell");
            }
            return SAFE_VERDICT;
        }

        // ---- Power commands ---------------------------------------------------
        if (cmd.equals("reboot") || cmd.equals("shutdown") || cmd.equals("halt") || cmd.equals("poweroff")) {
            return caution(cmd + " the device");
        }

        return SAFE_VERDICT;
    }

    private static boolean hasRawDeviceTarget(List<String> tokens) {
        for (String t : tokens) {
            // dd-style "of=/dev/..." or redirection "> /dev/..."
            if (t.startsWith("of=/dev/") && !t.startsWith("of=/dev/null")) return true;
            if (t.equals(">/dev/sd") || t.startsWith(">/dev/sd") || t.startsWith(">/dev/mmcblk") || t.startsWith(">/dev/block")) return true;
            if (t.startsWith("/dev/sd") || t.startsWith("/dev/mmcblk") || t.startsWith("/dev/block")) return true;
        }
        return false;
    }

    /** Targets whose recursive deletion is unrecoverable (device-wide). */
    private static boolean hasDestructiveTarget(List<String> targets) {
        for (String t : targets) {
            if (t == null || t.isEmpty()) continue;
            if (t.startsWith("-")) continue;
            String flat = t.replace("\"", "");
            if (flat.equals("/") || flat.equals("/*") || flat.equals("/.") || flat.equals("../*")
                || flat.equals("~") || flat.equals("~/*") || flat.equals("*")
                || flat.equals("$HOME") || flat.startsWith("$HOME/")
                || flat.equals("$PREFIX") || flat.startsWith("$PREFIX/")
                || flat.startsWith("/data/data/com.termux")) {
                return true;
            }
        }
        return false;
    }

    private static String describeTargets(List<String> targets) {
        List<String> real = new ArrayList<>();
        for (String t : targets) {
            if (t != null && !t.isEmpty() && !t.startsWith("-")) real.add(t);
        }
        if (real.isEmpty()) return "files";
        if (real.size() == 1) return real.get(0);
        return real.get(0) + " (and " + (real.size() - 1) + " more)";
    }

    private static List<String> tokenize(String segment) {
        List<String> tokens = new ArrayList<>();
        for (String t : segment.split("\\s+")) {
            if (!t.isEmpty()) tokens.add(t);
        }
        return tokens;
    }

    /** "/usr/bin/rm" -> "rm" */
    private static String basename(String token) {
        int idx = token.lastIndexOf('/');
        return idx >= 0 ? token.substring(idx + 1) : token;
    }

    private static Verdict dangerous(String reason) {
        return new Verdict(Level.DANGEROUS, reason);
    }

    private static Verdict caution(String reason) {
        return new Verdict(Level.CAUTION, reason);
    }
}
