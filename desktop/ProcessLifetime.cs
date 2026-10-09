using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

// Read-only process ownership and exit verification; never kills by image name.
static class ProcessLifetime {
    [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
    [DllImport("kernel32.dll", SetLastError=true, CharSet=CharSet.Unicode)] static extern bool QueryFullProcessImageName(IntPtr handle, uint flags, StringBuilder path, ref int length);
    [DllImport("kernel32.dll", SetLastError=true)] static extern uint WaitForSingleObject(IntPtr handle, uint timeout);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    internal sealed class Lease : IDisposable {
        internal IntPtr Handle;
        internal int Id;
        internal string Path;
        internal bool Exited { get { return WaitForSingleObject(Handle,0)==0; } }
        public void Dispose(){if(Handle!=IntPtr.Zero){CloseHandle(Handle);Handle=IntPtr.Zero;}}
    }
    internal static List<Lease> Capture(string root) {
        var owned=new List<Lease>();
        string prefix=System.IO.Path.GetFullPath(root).TrimEnd('\\')+"\\";
        int self=Process.GetCurrentProcess().Id;
        try {
            foreach(var process in Process.GetProcesses()) {
                using(process) {
                    if(process.Id==self)continue;
                    IntPtr handle=OpenProcess(0x100000|0x1000,false,process.Id); // synchronize + query limited information
                    if(handle==IntPtr.Zero) {
                        // Do not report success when a known component cannot be inspected.
                        string name="";try{name=process.ProcessName;}catch{}
                        if(name=="WangChuanLink"||name=="WangChuanManager"||name=="easytier-core"||name=="rustdesk"||name=="sunshine"||name=="adb")
                            throw new InvalidOperationException("无法核对进程 "+process.Id+" 的所属目录；请在任务管理器确认旧版组件是否已结束。");
                        continue;
                    }
                    var path=new StringBuilder(32768);int length=path.Capacity;
                    bool queried=QueryFullProcessImageName(handle,0,path,ref length);
                    if(queried&&path.ToString().StartsWith(prefix,StringComparison.OrdinalIgnoreCase))
                        owned.Add(new Lease{Handle=handle,Id=process.Id,Path=path.ToString()});
                    else {
                        bool exited=WaitForSingleObject(handle,0)==0; CloseHandle(handle);
                        if(!queried&&!exited) {
                            string name="";try{name=process.ProcessName;}catch{}
                            if(name=="WangChuanLink"||name=="easytier-core"||name=="rustdesk"||name=="sunshine"||name=="adb")
                                throw new InvalidOperationException("无法读取组件所属目录，不能确认已完全退出。");
                        }
                    }
                }
            }
            return owned;
        } catch {foreach(var lease in owned)lease.Dispose();throw;}
    }
    internal static void WaitForRelease(string root,List<Lease> initial,TimeSpan timeout) {
        var clock=Stopwatch.StartNew();
        while(true) {
            var current=Capture(root);
            try {
                bool exited=true;foreach(var lease in initial)if(!lease.Exited)exited=false;
                if(exited&&current.Count==0)return;
                if(clock.Elapsed>=timeout) {
                    var names=new List<string>();foreach(var lease in current)names.Add(System.IO.Path.GetFileName(lease.Path)+" ("+lease.Id+")");
                    throw new InvalidOperationException("尚未彻底退出："+string.Join("、",names)+"。旧版残留可运行程序包里的“清理残留进程.cmd”，允许管理员提示后重试。不要删除仍在运行的程序。");
                }
            } finally {foreach(var lease in current)lease.Dispose();}
            Thread.Sleep(250);
        }
    }
}
