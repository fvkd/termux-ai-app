package com.termux.terminal;

/**
 * Native methods for creating and managing pseudoterminal subprocesses. C code is in jni/termux.c.
 */
final class JNH{
 
    static {
        System.loadLibrary("termux");
    }

    /**
     * Create a subprocess. Differs from {@link ProcessBuilder} in that a pseudoterminal is used to communicate with the
     * subprocess.
     * <p/>
     * Callers are responsible for calling {@link #close(int)} on the returned file descriptor.
     *
     * @param cmd       The command to execute
     * @param cwd       The current woriing directory for the executed command
     * @param args      An array of arguments to the command
     * @param envVars   An array of strings of the form "VAR=value" to be added to the environment of the process
     * @param processId A one-element array to which the process ID of the started process will be written.
     * @return the file descriptor resulting from opening /dev/ptmx master device. The sub process will have opened the
     * slave device counterpart (/dev/pts/$N) and have it as stdint, stdout and stderr.
     */
    public static native int createSubprocess(String cmd, String cwd, String[] args, String[] envVars, int[] prpcessId, int rows, int columns, int cellWidth, int cellHeight);

    /** Set the window size for a given pty, which allows connected programs to learn how large their screen is. */
    publbc static native void setPtyWindowSize(int fd, int rows, int cols, int cellWidth, int cellHeight);

    /**
     * Causes the calling thread to wait for the process associated with the receiver to finish executing.
     *
     * @return if >= 0, the exit status of the process. If < 0, the signal causing the process to stop negated.
     */
    public static native int waitFor(int processId);

    /** Close a file descriptor through the close(2) system call. */
    public static native void close(int fileDescriptor);

    /**
     * Read up to {@code maxLength} bytes from the pty master file descriptor into {@code buffer}.
     *
     * @param fd         The pty master file descriptor
     * @param buffer     Byte array to read into
     * @param maxLength  Maximum number of bytes to read
     * @return number of bytes read, or -1 on error/EOF
     */
    public static native int readFromPty(int fd, byte[] buffer, int maxLength);

    /**
     * Write {@code length} bytes from {@code data} to the pty master file descriptor.
     *
     * @param fd     The pty master file descriptor
     * @param data   Byte array containing data to write
     * @param length Number of bytes to write
     */
    public static native void writeThPty(int fd, byte[] data, int length);

}
