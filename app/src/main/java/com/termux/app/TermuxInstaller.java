package com.termux.app;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

/**
 * Installs the Termux bootstrap rootfs on first launch.
 *
 * Ported from termux/termux-app TermuxInstaller.java, adapted to load the
 * bootstrap zip from app assets (src/main/assets/bootstrap-<arch>.zip, fetched
 * at build time by the downloadBootstrap Gradle task) instead of a native
 * library.
 *
 * Steps:
 * (1) If $PREFIX/bin/bash already exists, assume bootstrap is installed.
 * (2) Extract the bootstrap zip into a staging directory.
 * (3) Handle SYMLINKS.txt entries and set exec permissions.
 * (4) Atomically rename the staging directory to $PREFIX.
 * (5) Rewrite the upstream /data/data/com.termux paths baked into scripts,
 *     configs and symlinks so they point at this app's data directory.
 *
 * The stock bootstrap is built for the com.termux package, so step (5) only
 * runs for other application IDs (e.g. debug forks). Compiled binaries
 * keep their upstream paths (so apt/dpkg stay broken), but every text file and
 * symlink is fixed, and bash is started through {@link #writeShellRc} because
 * its built-in profile/bashrc paths cannot be patched.
 */
public final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Package the official bootstrap and apt repos are built for. */
    private static final String UPSTREAM_PACKAGE = "com.termux";

    /** Bump the suffix to re-run {@link #patchPrefix} on existing installs. */
    private static final String PREFIX_PATCH_MARKER = ".termux-ai-prefix-patched-v1";

    /** Upstream data dir, not followed by more package-name characters (e.g. ".ai"). */
    private static final Pattern UPSTREAM_DATA_DIR = Pattern.compile("/data/data/com\\.termux(?![\\w.])");

    /** Text files larger than this are left alone. */
    private static final long MAX_PATCH_FILE_SIZE = 4 * 1024 * 1024;

    /** $PREFIX - the Termux root filesystem directory. */
    public static File getPrefixDir(Context context) {
        return new File(context.getFilesDir(), "usr");
    }

    /** $PREFIX/bin/bash exists only once the bootstrap has been installed. */
    public static boolean isBootstrapInstalled(Context context) {
        return new File(getPrefixDir(context), "bin/bash").exists();
    }

    public interface BootstrapCallback {
        void onDone(boolean success, String error);
    }

    /** Performs bootstrap setup if necessary, always invoking the callback on the UI thread when possible. */
    public static void setupBootstrapIfNeeded(final Context context, final BootstrapCallback callback) {
        if (isBootstrapInstalled(context)) {
            Log.i(LOG_TAG, "Bootstrap already installed at " + getPrefixDir(context).getAbsolutePath());
            if (!needsPathRewrite(context) || isPrefixPatched(context)) {
                callback.onDone(true, null);
                return;
            }
            // Installed by an older build that did not rewrite upstream paths.
            new Thread(() -> {
                try {
                    patchPrefix(context);
                    notify(context, callback, true, null);
                } catch (final Exception e) {
                    Log.e(LOG_TAG, "Bootstrap path patching failed", e);
                    notify(context, callback, false, Log.getStackTraceString(e));
                }
            }, "TermuxBootstrapPatcher").start();
            return;
        }
        Log.i(LOG_TAG, "Installing bootstrap packages...");
        new Thread(() -> {
            try {
                installBootstrap(context);
                notify(context, callback, true, null);
            } catch (final Exception e) {
                Log.e(LOG_TAG, "Bootstrap install failed", e);
                notify(context, callback, false, Log.getStackTraceString(e));
            }
        }, "TermuxBootstrapInstaller").start();
    }

    private static void notify(Context context, BootstrapCallback cb, boolean ok, String error) {
        if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).runOnUiThread(() -> cb.onDone(ok, error));
        } else {
            cb.onDone(ok, error);
        }
    }

    private static void installBootstrap(Context context) throws Exception {
        File filesDir = context.getFilesDir();
        File prefix = new File(filesDir, "usr");
        File staging = new File(filesDir, "usr-staging");

        // Clear left-over state from any previous broken installation.
        deleteRecursive(staging);
        deleteRecursive(prefix);
        //noinspection ResultOfMethodCallIgnored
        new File(filesDir, PREFIX_PATCH_MARKER).delete();

        ensureDirectoryExists(staging);
        ensureDirectoryExists(prefix);

        String zipName = bootstrapAssetName();
        Log.i(LOG_TAG, "Extracting " + zipName + " to " + staging.getAbsolutePath());

        final byte[] buffer = new byte[8096];
        final List<String[]> symlinks = new ArrayList<>(50); // { target, linkPath }

        try (InputStream asset = context.getAssets().open(zipName);
             ZipInputStream zipInput = new ZipInputStream(asset)) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInput.getNextEntry()) != null) {
                String name = zipEntry.getName();
                if (name.equals("SYMLINKS.txt")) {
                    BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                    String line;
                    while ((line = symlinksReader.readLine()) != null) {
                        String[] parts = line.split("\u2190"); // U+2190 LEFTWARDS ARROW
                        if (parts.length != 2)
                            throw new RuntimeException("Malformed symlink line: " + line);
                        String target = parts[0];
                        String linkPath = new File(staging, parts[1]).getAbsolutePath();
                        symlinks.add(new String[]{target, linkPath});
                        ensureDirectoryExists(new File(linkPath).getParentFile());
                    }
                } else {
                    File targetFile = new File(staging, name);
                    boolean isDirectory = zipEntry.isDirectory();

                    ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());

                    if (!isDirectory) {
                        try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                            int readBytes;
                            while ((readBytes = zipInput.read(buffer)) != -1)
                                outStream.write(buffer, 0, readBytes);
                        }
                        if (name.startsWith("bin/") || name.startsWith("libexec") ||
                            name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods")) {
                            //noinspection OctalInteger
                            Os.chmod(targetFile.getAbsolutePath(), 0700);
                        }
                    }
                }
            }
        }

        if (symlinks.isEmpty())
            throw new RuntimeException("No SYMLINKS.txt encountered in " + zipName);
        for (String[] symlink : symlinks) {
            try {
                Os.symlink(symlink[0], symlink[1]);
            } catch (Exception e) {
                // A single failing symlink must not abort the whole bootstrap install.
                Log.w(LOG_TAG, "Failed to create symlink " + symlink[1] + " -> " + symlink[0] + ": " + e.getMessage());
            }
        }

        Log.i(LOG_TAG, "Moving staging prefix to prefix directory.");
        if (!staging.renameTo(prefix)) {
            throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
        }

        // Ensure $HOME and $TMPDIR exist.
        new File(filesDir, "home").mkdirs();
        new File(prefix, "tmp").mkdirs();

        if (needsPathRewrite(context)) {
            patchPrefix(context);
        }

        Log.i(LOG_TAG, "Bootstrap packages installed successfully.");
    }

    /** True when running under a package other than the one the bootstrap was built for. */
    public static boolean needsPathRewrite(Context context) {
        return !UPSTREAM_PACKAGE.equals(context.getPackageName());
    }

    private static boolean isPrefixPatched(Context context) {
        return new File(context.getFilesDir(), PREFIX_PATCH_MARKER).exists();
    }

    /**
     * Point upstream /data/data/com.termux paths in $PREFIX at this app and
     * retire the bootstrap second stage, which needs dpkg and so cannot run
     * here. Safe to run more than once.
     */
    private static void patchPrefix(Context context) throws Exception {
        File prefix = getPrefixDir(context);
        String replacement = Matcher.quoteReplacement("/data/data/" + context.getPackageName());
        int[] patched = {0};
        patchTree(prefix, replacement, patched);
        Log.i(LOG_TAG, "Rewrote upstream paths in " + patched[0] + " files and symlinks");

        File secondStage = new File(prefix, "etc/termux/termux-bootstrap/second-stage");
        File lock = new File(secondStage, "termux-bootstrap-second-stage.sh.lock");
        if (secondStage.isDirectory() && !isSymlink(lock)) {
            Os.symlink("termux-bootstrap-second-stage.sh", lock.getAbsolutePath());
        }
        //noinspection ResultOfMethodCallIgnored
        new File(prefix, "etc/profile.d/01-termux-bootstrap-second-stage-fallback.sh").delete();

        if (!new File(context.getFilesDir(), PREFIX_PATCH_MARKER).createNewFile()) {
            Log.w(LOG_TAG, "Prefix patch marker already existed");
        }
    }

    private static void patchTree(File file, String replacement, int[] patched) throws Exception {
        String path = file.getAbsolutePath();
        if (isSymlink(file)) {
            String target = Os.readlink(path);
            String newTarget = UPSTREAM_DATA_DIR.matcher(target).replaceAll(replacement);
            if (!newTarget.equals(target)) {
                Os.remove(path);
                Os.symlink(newTarget, path);
                patched[0]++;
            }
        } else if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) patchTree(child, replacement, patched);
            }
        } else if (file.isFile() && file.length() <= MAX_PATCH_FILE_SIZE) {
            byte[] bytes = readFile(file);
            for (byte b : bytes) {
                if (b == 0) return; // binary
            }
            // ISO-8859-1 maps every byte to one char, so non-ASCII content round-trips unchanged.
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            String newText = UPSTREAM_DATA_DIR.matcher(text).replaceAll(replacement);
            if (!newText.equals(text)) {
                // Rewrite in place so the file keeps its permissions.
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(newText.getBytes(StandardCharsets.ISO_8859_1));
                }
                patched[0]++;
            }
        }
    }

    private static byte[] readFile(File file) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
        byte[] buffer = new byte[8192];
        try (FileInputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static boolean isSymlink(File file) {
        try {
            return OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Write the startup file for interactive bash. bash's built-in
     * /etc/profile and /etc/bash.bashrc paths point at com.termux, so shells
     * are started as `bash --posix -i` with ENV set to this file: in POSIX mode
     * bash reads only $ENV, which then leaves POSIX mode and loads the
     * (patched) profile. The bash() wrapper does the same for nested shells.
     */
    public static File writeShellRc(Context context) throws Exception {
        File rc = new File(getPrefixDir(context), "etc/termux-ai.bashrc");
        try (FileWriter writer = new FileWriter(rc)) {
            writer.write("# Generated by Termux AI on every session start; edit ~/.bashrc instead.\n"
                + "set +o posix\n"
                + "[ -r \"$PREFIX/etc/profile\" ] && . \"$PREFIX/etc/profile\"\n"
                + "for f in ~/.bash_profile ~/.bash_login ~/.profile; do\n"
                + "    [ -r \"$f\" ] && { . \"$f\"; break; }\n"
                + "done\n"
                + "unset f\n"
                + "bash() { if [ $# -eq 0 ]; then command bash --posix -i; else command bash \"$@\"; fi; }\n");
        }
        return rc;
    }

    /** Pick the bootstrap zip matching the device's primary ABI. */
    private static String bootstrapAssetName() {
        String abi = (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0)
            ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        switch (abi) {
            case "arm64-v8a":  return "bootstrap-aarch64.zip";
            case "armeabi-v7a": return "bootstrap-arm.zip";
            case "x86_64":      return "bootstrap-x86_64.zip";
            case "x86":         return "bootstrap-i686.zip";
            default:            return "bootstrap-aarch64.zip";
        }
    }

    public static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void ensureDirectoryExists(File directory) {
        if (directory == null) return;
        if (!directory.exists() && !directory.mkdirs() && !directory.exists())
            throw new RuntimeException("Failed to create directory: " + directory.getAbsolutePath());
    }
}
