param(
    [Parameter(Mandatory = $true)]
    [string]$TaskRoot,
    [Parameter(Mandatory = $true)]
    [string]$ExperimentRoot,
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

    public static int Start(string application, string arguments, string currentDirectory) {
        const uint CREATE_NEW_PROCESS_GROUP = 0x00000200;
        const uint CREATE_BREAKAWAY_FROM_JOB = 0x01000000;
        const uint CREATE_NO_WINDOW = 0x08000000;
        STARTUPINFO startup = new STARTUPINFO();
        startup.cb = Marshal.SizeOf(typeof(STARTUPINFO));
        PROCESS_INFORMATION process;
        StringBuilder command = new StringBuilder("\"" + application + "\" " + arguments);
        bool ok = CreateProcessW(application, command, IntPtr.Zero, IntPtr.Zero, false,
            CREATE_NEW_PROCESS_GROUP | CREATE_BREAKAWAY_FROM_JOB | CREATE_NO_WINDOW,
            IntPtr.Zero, currentDirectory, ref startup, out process);
        if (!ok) throw new Win32Exception(Marshal.GetLastWin32Error());
        CloseHandle(process.hThread);
        CloseHandle(process.hProcess);
        return process.dwProcessId;
    }
}
'@

if ((Test-Path -LiteralPath $ExperimentRoot) -and -not $Resume) {
    throw "Refusing to reuse remote experiment directory: $ExperimentRoot"
}
New-Item -ItemType Directory -Force -Path $ExperimentRoot | Out-Null
Add-Type -TypeDefinition $source -Language CSharp
$runner = Join-Path $TaskRoot 'scripts\run_main_screen_25_remote.ps1'
$powershell = 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
$arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$runner`" " +
    "-TaskRoot `"$TaskRoot`" -ExperimentRoot `"$ExperimentRoot`""
$pidStarted = [DetachedBatchLauncher]::Start(
    $powershell, $arguments, $TaskRoot)
@(
    "controllerPid=$pidStarted"
    "started=$([DateTime]::Now.ToString('o'))"
    "taskRoot=$TaskRoot"
    "experimentRoot=$ExperimentRoot"
) | Set-Content -LiteralPath (Join-Path $ExperimentRoot 'launcher_process.txt') -Encoding UTF8
Write-Output "REMOTE_MAIN_SCREEN_STARTED pid=$pidStarted output=$ExperimentRoot"
