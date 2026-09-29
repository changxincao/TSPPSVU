$ErrorActionPreference = 'Stop'

$source = @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

public static class MediumModerateW1Launcher {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct STARTUPINFO {
        public int cb;
        public string lpReserved;
        public string lpDesktop;
        public string lpTitle;
        public int dwX;
        public int dwY;
        public int dwXSize;
        public int dwYSize;
        public int dwXCountChars;
        public int dwYCountChars;
        public int dwFillAttribute;
        public short wShowWindow;
        public short cbReserved2;
        public IntPtr lpReserved2;
        public IntPtr hStdInput;
        public IntPtr hStdOutput;
        public IntPtr hStdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct PROCESS_INFORMATION {
        public IntPtr hProcess;
        public IntPtr hThread;
        public int dwProcessId;
        public int dwThreadId;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CreateProcessW(string applicationName, StringBuilder commandLine,
        IntPtr processAttributes, IntPtr threadAttributes, bool inheritHandles,
        uint creationFlags, IntPtr environment, string currentDirectory,
        ref STARTUPINFO startupInfo, out PROCESS_INFORMATION processInformation);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr handle);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetProcessAffinityMask(IntPtr process, UIntPtr mask);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint ResumeThread(IntPtr thread);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool TerminateProcess(IntPtr process, uint exitCode);

    public static int Start(string application, string arguments, string currentDirectory,
            ulong affinityMask) {
        const uint CREATE_NEW_PROCESS_GROUP = 0x00000200;
        const uint CREATE_SUSPENDED = 0x00000004;
        const uint CREATE_BREAKAWAY_FROM_JOB = 0x01000000;
        const uint CREATE_NO_WINDOW = 0x08000000;
        STARTUPINFO startup = new STARTUPINFO();
        startup.cb = Marshal.SizeOf(typeof(STARTUPINFO));
        PROCESS_INFORMATION process;
        StringBuilder command = new StringBuilder("\"" + application + "\" " + arguments);
        bool ok = CreateProcessW(application, command, IntPtr.Zero, IntPtr.Zero, false,
            CREATE_NEW_PROCESS_GROUP | CREATE_SUSPENDED | CREATE_BREAKAWAY_FROM_JOB | CREATE_NO_WINDOW,
            IntPtr.Zero, currentDirectory, ref startup, out process);
        if (!ok) throw new Win32Exception(Marshal.GetLastWin32Error());
        try {
            if (!SetProcessAffinityMask(process.hProcess, new UIntPtr(affinityMask))) {
                int error = Marshal.GetLastWin32Error();
                TerminateProcess(process.hProcess, 1);
                throw new Win32Exception(error, "Cannot set worker affinity");
            }
            uint resumed = ResumeThread(process.hThread);
            if (resumed == 0xffffffff) {
                int error = Marshal.GetLastWin32Error();
                TerminateProcess(process.hProcess, 1);
                throw new Win32Exception(error, "Cannot resume affinity-pinned worker");
            }
            return process.dwProcessId;
        } finally {
            CloseHandle(process.hThread);
            CloseHandle(process.hProcess);
        }
    }
}
'@

Add-Type -TypeDefinition $source -Language CSharp
$taskRoot = 'D:\ccx\TSPP_SVU\staging\w1-positive-support-medium-20260929'
$runner = Join-Path $taskRoot 'scripts\run_medium_moderate_w1_top_radii_remote.ps1'
$powershell = 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
$outputRoot = 'D:\ccx\TSPP_SVU\experiments\moderate_common_seed20261020_20260929\medium_w1_top_radii_positive_support_2thread_20260929'
if (Test-Path -LiteralPath $outputRoot) {
    throw "Refusing to reuse existing formal output directory: $outputRoot"
}
New-Item -ItemType Directory -Path $outputRoot | Out-Null

# One logical processor from each assigned P core. Each controller runs one
# two-thread worker at a time; its Java/CPLEX child inherits the affinity.
$groups = @(
    @{ Name = 'A'; Replications = '0-2'; Mask = [uint64]0x0005 },
    @{ Name = 'B'; Replications = '3-4'; Mask = [uint64]0x0500 }
)
$started = @()
foreach ($group in $groups) {
    $arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$runner`" " +
        "-Replications `"$($group.Replications)`" -Group `"$($group.Name)`" " +
        "-OutputRoot `"$outputRoot`""
    $pidStarted = [MediumModerateW1Launcher]::Start(
        $powershell, $arguments, $taskRoot, $group.Mask)
    $started += [pscustomobject]@{
        group = $group.Name
        replications = $group.Replications
        affinityMask = ('0x{0:X}' -f $group.Mask)
        pid = $pidStarted
    }
}

$started | ConvertTo-Csv -NoTypeInformation |
    Set-Content -LiteralPath (Join-Path $outputRoot 'launcher_processes.csv') -Encoding UTF8
@(
    'globalTaskParallel=2'
    'tasksPerController=1'
    'solverThreadsPerTask=2'
    'affinityPolicy=disjoint_p_cores_one_logical_processor_per_core'
    'cplexVersion=22.1.1'
    'javaVersion=21'
    'support=positive_weight_lane_min_max'
    'w1Grid=0.00025,0.001,0.01'
) | Set-Content -LiteralPath (Join-Path $outputRoot 'launch_manifest.txt') -Encoding UTF8
$started | Format-Table -AutoSize
Write-Output "REMOTE_W1_STARTED output=$outputRoot"
