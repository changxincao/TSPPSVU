param(
    [Parameter(Mandatory = $true)]
    [string]$TaskRoot,
    [Parameter(Mandatory = $true)]
    [string]$ExperimentRoot,
    [string]$RunnerName = 'run_main_screen_25_remote.ps1',
    [UInt64]$AffinityMask = 0,
    [switch]$Resume
)

$ErrorActionPreference = 'Stop'
$source = @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

public static class DetachedBatchLauncher {
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
        uint flags = CREATE_NEW_PROCESS_GROUP | CREATE_BREAKAWAY_FROM_JOB | CREATE_NO_WINDOW;
        if (affinityMask != 0) flags |= CREATE_SUSPENDED;
        bool ok = CreateProcessW(application, command, IntPtr.Zero, IntPtr.Zero, false,
            flags,
            IntPtr.Zero, currentDirectory, ref startup, out process);
        if (!ok) throw new Win32Exception(Marshal.GetLastWin32Error());
        try {
            if (affinityMask != 0) {
                if (!SetProcessAffinityMask(process.hProcess, new UIntPtr(affinityMask))) {
                    int error = Marshal.GetLastWin32Error();
                    TerminateProcess(process.hProcess, 1);
                    throw new Win32Exception(error, "Cannot set launcher affinity");
                }
                uint resumed = ResumeThread(process.hThread);
                if (resumed == 0xffffffff) {
                    int error = Marshal.GetLastWin32Error();
                    TerminateProcess(process.hProcess, 1);
                    throw new Win32Exception(error, "Cannot resume affinity-pinned launcher");
                }
            }
            return process.dwProcessId;
        } finally {
            CloseHandle(process.hThread);
            CloseHandle(process.hProcess);
        }
    }
}
'@

if ((Test-Path -LiteralPath $ExperimentRoot) -and -not $Resume) {
    throw "Refusing to reuse remote experiment directory: $ExperimentRoot"
}
New-Item -ItemType Directory -Force -Path $ExperimentRoot | Out-Null
Add-Type -TypeDefinition $source -Language CSharp
$runner = Join-Path $TaskRoot (Join-Path 'scripts' $RunnerName)
if (-not (Test-Path -LiteralPath $runner -PathType Leaf)) {
    throw "Remote runner does not exist: $runner"
}
$expectedClassMajor = 65 # Java 21
$classRoot = Join-Path $TaskRoot 'bin'
$incompatibleClasses = [System.Collections.Generic.List[string]]::new()
foreach ($classFile in Get-ChildItem -LiteralPath $classRoot -Filter '*.class' -File -Recurse) {
    $bytes = [System.IO.File]::ReadAllBytes($classFile.FullName)
    if ($bytes.Length -lt 8) {
        $incompatibleClasses.Add("$($classFile.FullName) (truncated)")
        continue
    }
    $major = ([int]$bytes[6] -shl 8) + [int]$bytes[7]
    if ($major -gt $expectedClassMajor) {
        $incompatibleClasses.Add("$($classFile.FullName) (major=$major)")
    }
}
if ($incompatibleClasses.Count -gt 0) {
    throw "Java 21 compatibility check failed:`n$($incompatibleClasses -join [Environment]::NewLine)"
}
$powershell = 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
$arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$runner`" " +
    "-TaskRoot `"$TaskRoot`" -ExperimentRoot `"$ExperimentRoot`""
$pidStarted = [DetachedBatchLauncher]::Start(
    $powershell, $arguments, $TaskRoot, $AffinityMask)
$launcherRecord = if ($RunnerName -eq 'run_main_screen_25_remote.ps1') {
    'launcher_process.txt'
} else {
    ([System.IO.Path]::GetFileNameWithoutExtension($RunnerName) + '_launcher_process.txt')
}
@(
    "controllerPid=$pidStarted"
    "started=$([DateTime]::Now.ToString('o'))"
    "taskRoot=$TaskRoot"
    "experimentRoot=$ExperimentRoot"
    "runnerName=$RunnerName"
    "affinityMask=0x$($AffinityMask.ToString('X'))"
) | Set-Content -LiteralPath (Join-Path $ExperimentRoot $launcherRecord) -Encoding UTF8
Write-Output "REMOTE_MAIN_SCREEN_STARTED pid=$pidStarted output=$ExperimentRoot"
