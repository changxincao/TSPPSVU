# WMI is not required: Windows OpenSSH tokens may deny CIM while native APIs work.
if (-not ('OlistWindowsProcess' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;
public static class OlistWindowsProcess {
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
    struct Startup { public int cb; public string reserved,desktop,title;
        public int x,y,width,height,xchars,ychars,fill,flags; public short show,reservedSize;
        public IntPtr reservedBytes,input,output,error; }
    [StructLayout(LayoutKind.Sequential)]
    struct ProcessInfo { public IntPtr process,thread; public int pid,tid; }
    [DllImport("kernel32.dll",SetLastError=true)] static extern IntPtr OpenProcess(uint access,bool inherit,int pid);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    [DllImport("ntdll.dll")] static extern int NtQueryInformationProcess(IntPtr h,int type,IntPtr buffer,int size,out int length);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]
    static extern bool CreateProcessW(string app,StringBuilder command,IntPtr pa,IntPtr ta,bool inherit,
        uint flags,IntPtr env,string directory,ref Startup startup,out ProcessInfo process);
    public static string CommandLine(int pid) {
        IntPtr h=OpenProcess(0x1000,false,pid),buffer=IntPtr.Zero;
        if(h==IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
        try {
            int length; NtQueryInformationProcess(h,60,IntPtr.Zero,0,out length);
            if(length<=0) throw new InvalidOperationException("Cannot size command line for PID "+pid);
            buffer=Marshal.AllocHGlobal(length);
            int status=NtQueryInformationProcess(h,60,buffer,length,out length);
            if(status!=0) throw new InvalidOperationException("Cannot read command line for PID "+pid+": "+status);
            int bytes=(ushort)Marshal.ReadInt16(buffer);
            IntPtr text=Marshal.ReadIntPtr(buffer,IntPtr.Size==8?8:4);
            return bytes==0?"":Marshal.PtrToStringUni(text,bytes/2);
        } finally {if(buffer!=IntPtr.Zero)Marshal.FreeHGlobal(buffer);CloseHandle(h);}
    }
    public static int StartDetached(string app,string args,string directory) {
        Startup startup=new Startup();startup.cb=Marshal.SizeOf(typeof(Startup));
        ProcessInfo p;
        // New process group + break away from SSH job + no visible window.
        if(!CreateProcessW(app,new StringBuilder("\""+app+"\" "+args),IntPtr.Zero,IntPtr.Zero,false,
            0x00000200|0x01000000|0x08000000,IntPtr.Zero,directory,ref startup,out p))
            throw new Win32Exception(Marshal.GetLastWin32Error());
        try {return p.pid;} finally {CloseHandle(p.process);CloseHandle(p.thread);}
    }
}
'@
}
