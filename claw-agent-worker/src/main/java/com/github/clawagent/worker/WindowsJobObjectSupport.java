package com.github.clawagent.worker;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Windows Job Object 封装。
 * worker 主流程只关心“绑定进程”和“读取峰值内存”，native 结构体和句柄生命周期集中放在这里。
 */
final class WindowsJobObjectSupport {
    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS = 9;
    private static final int JOB_OBJECT_LIMIT_JOB_MEMORY = 0x00000200;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    private static final int PROCESS_TERMINATE = 0x0001;
    private static final int PROCESS_SET_QUOTA = 0x0100;

    private WindowsJobObjectSupport() {
    }

    static Optional<JobHandle> attach(Process process, long maxMemoryBytes) {
        if (!isWindows() || maxMemoryBytes <= 0) {
            return Optional.empty();
        }
        WinNT.HANDLE job = Kernel32Job.INSTANCE.CreateJobObjectW(Pointer.NULL, null);
        if (isInvalid(job)) {
            throw new IllegalStateException("创建 Windows Job Object 失败：lastError=" + Native.getLastError());
        }
        try {
            setMemoryLimit(job, maxMemoryBytes);
            assign(job, process.pid());
            return Optional.of(new JobHandle(job, maxMemoryBytes));
        } catch (RuntimeException e) {
            Kernel32Job.INSTANCE.CloseHandle(job);
            throw e;
        }
    }

    private static void setMemoryLimit(WinNT.HANDLE job, long maxMemoryBytes) {
        JobObjectExtendedLimitInformation info = new JobObjectExtendedLimitInformation();
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_JOB_MEMORY | JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        info.JobMemoryLimit = maxMemoryBytes;
        info.write();
        boolean ok = Kernel32Job.INSTANCE.SetInformationJobObject(job,
                JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS, info, info.size());
        if (!ok) {
            throw new IllegalStateException("设置 Windows Job Object 内存限制失败：lastError=" + Native.getLastError());
        }
    }

    private static void assign(WinNT.HANDLE job, long pid) {
        WinNT.HANDLE process = Kernel32Job.INSTANCE.OpenProcess(PROCESS_TERMINATE | PROCESS_SET_QUOTA,
                false, (int) pid);
        if (isInvalid(process)) {
            throw new IllegalStateException("打开命令进程失败，无法绑定 Windows Job Object：pid=" + pid
                    + " lastError=" + Native.getLastError());
        }
        try {
            boolean ok = Kernel32Job.INSTANCE.AssignProcessToJobObject(job, process);
            if (!ok) {
                throw new IllegalStateException("绑定命令进程到 Windows Job Object 失败：pid=" + pid
                        + " lastError=" + Native.getLastError());
            }
        } finally {
            Kernel32Job.INSTANCE.CloseHandle(process);
        }
    }

    private static boolean isInvalid(WinNT.HANDLE handle) {
        return handle == null || WinNT.INVALID_HANDLE_VALUE.equals(handle);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static final class JobHandle implements AutoCloseable {
        private final WinNT.HANDLE job;
        private final long memoryLimitBytes;

        private JobHandle(WinNT.HANDLE job, long memoryLimitBytes) {
            this.job = job;
            this.memoryLimitBytes = memoryLimitBytes;
        }

        long memoryBytes() {
            JobObjectExtendedLimitInformation info = new JobObjectExtendedLimitInformation();
            IntByReference length = new IntByReference();
            boolean ok = Kernel32Job.INSTANCE.QueryInformationJobObject(job,
                    JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS, info, info.size(), length);
            if (!ok) {
                return 0;
            }
            info.read();
            return info.PeakJobMemoryUsed;
        }

        boolean limitLikelyExceeded() {
            long observed = memoryBytes();
            return memoryLimitBytes > 0 && observed >= memoryLimitBytes;
        }

        @Override
        public void close() {
            Kernel32Job.INSTANCE.CloseHandle(job);
        }
    }

    interface Kernel32Job extends StdCallLibrary {
        Kernel32Job INSTANCE = Native.load("kernel32", Kernel32Job.class, W32APIOptions.DEFAULT_OPTIONS);

        WinNT.HANDLE CreateJobObjectW(Pointer jobAttributes, String name);

        boolean SetInformationJobObject(WinNT.HANDLE job, int infoClass, Structure info, int length);

        boolean QueryInformationJobObject(WinNT.HANDLE job, int infoClass, Structure info, int length,
                                          IntByReference returnedLength);

        boolean AssignProcessToJobObject(WinNT.HANDLE job, WinNT.HANDLE process);

        WinNT.HANDLE OpenProcess(int desiredAccess, boolean inheritHandle, int processId);

        boolean CloseHandle(WinNT.HANDLE object);
    }

    @Structure.FieldOrder({
            "BasicLimitInformation",
            "IoInfo",
            "ProcessMemoryLimit",
            "JobMemoryLimit",
            "PeakProcessMemoryUsed",
            "PeakJobMemoryUsed"
    })
    public static class JobObjectExtendedLimitInformation extends Structure {
        public JobObjectBasicLimitInformation BasicLimitInformation = new JobObjectBasicLimitInformation();
        public IoCounters IoInfo = new IoCounters();
        public long ProcessMemoryLimit;
        public long JobMemoryLimit;
        public long PeakProcessMemoryUsed;
        public long PeakJobMemoryUsed;
    }

    @Structure.FieldOrder({
            "PerProcessUserTimeLimit",
            "PerJobUserTimeLimit",
            "LimitFlags",
            "MinimumWorkingSetSize",
            "MaximumWorkingSetSize",
            "ActiveProcessLimit",
            "Affinity",
            "PriorityClass",
            "SchedulingClass"
    })
    public static class JobObjectBasicLimitInformation extends Structure {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public int LimitFlags;
        public long MinimumWorkingSetSize;
        public long MaximumWorkingSetSize;
        public int ActiveProcessLimit;
        public long Affinity;
        public int PriorityClass;
        public int SchedulingClass;
    }

    @Structure.FieldOrder({
            "ReadOperationCount",
            "WriteOperationCount",
            "OtherOperationCount",
            "ReadTransferCount",
            "WriteTransferCount",
            "OtherTransferCount"
    })
    public static class IoCounters extends Structure {
        public long ReadOperationCount;
        public long WriteOperationCount;
        public long OtherOperationCount;
        public long ReadTransferCount;
        public long WriteTransferCount;
        public long OtherTransferCount;
    }
}
