package com.termux.app;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
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
 */
public final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

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
            callback.onDone(true, null);
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

        Log.i(LOG_TAG, "Bootstrap packages installed successfully.");
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
