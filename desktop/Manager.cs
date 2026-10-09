using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

partial class Manager : Form {
    const string BaseUrl = "http://127.0.0.1:17881";
    readonly string root = AppDomain.CurrentDomain.BaseDirectory;
    readonly JavaScriptSerializer json = new JavaScriptSerializer();
    Label headline, network, remote, development, feedback;
    PictureBox qr;
    Label traffic;
    Button start, pair, stop;
    readonly Timer timer = new Timer();
    readonly NotifyIcon tray = new NotifyIcon();
    bool busy, polling, exiting, preview, closing, exitRequested;
    string csrf;
#if TESTING
    internal Func<string,string,string> RequestTest;
#endif
    DateTime qrUntil = DateTime.MinValue;
    static readonly Color Ink=Color.FromArgb(24,42,66), Blue=Color.FromArgb(47,100,237), Muted=Color.FromArgb(91,108,129);

    Label TextLabel(string text, int size, Color color, int x, int y, int w, int h) {
        return new Label { Text=text, Font=new Font("Microsoft YaHei UI",size), ForeColor=color, Location=new Point(x,y), Size=new Size(w,h), AutoEllipsis=true };
    }
    Button ActionButton(string text, int x,int y,int w,bool primary) {
        return new Button { Text=text, Location=new Point(x,y), Size=new Size(w,46), FlatStyle=FlatStyle.Flat,
            BackColor=primary?Blue:Color.White, ForeColor=primary?Color.White:Blue, Font=new Font("Microsoft YaHei UI",11), Cursor=Cursors.Hand };
    }
    public Manager(bool previewMode) {
        preview=previewMode; BuildLayout();
        tray.Icon=Icon; tray.Text="随连";
        var menu=new ContextMenuStrip(); menu.Items.Add("打开管理窗口",null,(s,e)=>ShowWindow());
        menu.Items.Add("彻底退出",null,async(s,e)=>await ExitCompletely());
        tray.ContextMenuStrip=menu; tray.DoubleClick+=(s,e)=>ShowWindow();
        FormClosing+=async(s,e)=>{ if(!exiting&&!preview&&e.CloseReason==CloseReason.UserClosing){e.Cancel=true;await ExitCompletely();} };
        FormClosed+=(s,e)=>{timer.Stop();tray.Dispose();};
        timer.Interval=4000; timer.Tick+=async(s,e)=>{await Poll();MaybeRefreshHistory();};
        if(!preview) Shown+=async(s,e)=>{tray.Visible=true;timer.Start();await Poll();};
    }
    void ShowWindow(){Show();WindowState=FormWindowState.Normal;Activate();}
    void RunInBackground(){if(closing)return;Hide();tray.Visible=true;tray.ShowBalloonTip(1500,"随连","正在后台运行。托盘菜单可彻底退出。",ToolTipIcon.Info);}
    class Client:WebClient { protected override WebRequest GetWebRequest(Uri address){var r=base.GetWebRequest(address);r.Timeout=20000;return r;} }
    string Request(string path,string body=null) {
#if TESTING
        if(RequestTest!=null)return RequestTest(path,body);
#endif
        using(var client=new Client()) {
            client.Encoding=Encoding.UTF8;
            if(body==null) return client.DownloadString(BaseUrl+path);
            client.Headers["X-CSRF"]=csrf;
            try{return client.UploadString(BaseUrl+path,"POST",body);}
            catch(WebException ex){if(ex.Response!=null)using(var r=new StreamReader(ex.Response.GetResponseStream()))throw new Exception(r.ReadToEnd());throw;}
        }
    }
    Dictionary<string,object> Object(string path){return json.Deserialize<Dictionary<string,object>>(Request(path));}
    string ByteSize(object value){double n=Convert.ToDouble(value);if(n>=1073741824)return (n/1073741824).ToString("F2")+" GB";if(n>=1048576)return (n/1048576).ToString("F1")+" MB";return (n/1024).ToString("F1")+" KB";}
    bool Flag(Dictionary<string,object> data,string key){return data.ContainsKey(key)&&data[key] is bool&&(bool)data[key];}
    void Token() {
        try{csrf=(string)Object("/manager-token")["token"];}
        catch{var match=Regex.Match(Request("/"),"X-CSRF':'([a-f0-9]+)'");if(!match.Success)throw new Exception("无法验证电脑端，请重新启动管理工具");csrf=match.Groups[1].Value;}
    }
    async Task Poll() {
        if(polling||busy||preview||closing)return; polling=true;
        try {
            var data=await Task.Run(()=>Object("/management"));
            bool net=Flag(data,"networkReady"), rd=Flag(data,"remoteReady"), configured=Flag(data,"remoteConfigured");
            var mesh=data.ContainsKey("network")?data["network"] as Dictionary<string,object>:null;
            bool phone=mesh!=null&&Flag(mesh,"phoneJoined");
            network.Text="●  "+(mesh!=null?Convert.ToString(mesh["message"]):"正在检测组网入口");network.ForeColor=phone?Color.SeaGreen:Muted;
            remote.Text=rd&&configured?"●  日常远控：已配置，可直接连接":"●  日常远控：请点一键准备"; remote.ForeColor=rd&&configured?Color.SeaGreen:Muted;
            headline.Text=net&&rd&&configured?(phone?"手机隧道已连，出口状态需单独验证":"电脑已准备，等待手机连接"):"继续完成电脑端准备";
            var stats=data.ContainsKey("traffic")?data["traffic"] as Dictionary<string,object>:null;
            if(stats!=null)traffic.Text="电脑出口 · 本次运行的 TCP 代理流量\n手机下载 "+ByteSize(stats["downloadBytes"])+"    手机上传 "+ByteSize(stats["uploadBytes"])+"    活跃连接 "+Convert.ToString(stats["activeConnections"])+"    失败连接 "+Convert.ToString(stats["failedConnections"])+"\n不含原网直连应用、UDP、远控及串流用量。";
            else traffic.Text="当前后台未提供流量统计，请更新电脑端。";
            UpdateDashboard(data,stats,phone,net);
            var dev=data["development"] as Dictionary<string,object>;
            if(dev!=null)development.Text="●  AI 真机联调："+Convert.ToString(dev["status"]);
        } catch { headline.Text="点击准备，启动电脑端"; network.Text="●  暂时无法读取电脑连接状态";remote.Text="●  日常远控：状态待确认";stateTag.Text="● 状态待确认";stateTag.ForeColor=Muted;network.ForeColor=Muted;remote.ForeColor=Muted;deviceSummary.Text="暂时无法读取状态 · 以下为上次统计"; }
        finally{polling=false;if(DateTime.Now>qrUntil&&qr.Image!=null){qr.Image.Dispose();qr.Image=null;}}
    }
    async Task Work(Func<Task> action){if(busy||closing)return;busy=true;start.Enabled=pair.Enabled=false;try{await action();}catch(Exception ex){feedback.Text=ex.Message;if(!exitRequested)MessageBox.Show(this,ex.Message,"随连",MessageBoxButtons.OK,MessageBoxIcon.Information);}finally{busy=false;start.Enabled=pair.Enabled=true;}if(exitRequested)await ExitCompletely();else await Poll();}
    async Task EnsureBackend() {
        bool compatible=false, exists=false;
        await Task.Run(()=>{try{Request("/status");exists=true;}catch{}try{compatible=Convert.ToString(Object("/management")["version"])=="0.3.17";}catch{}});
        if(compatible){await Task.Run(()=>Token());return;}
        if(exists)feedback.Text="准备更新电脑端，允许管理员提示后自动退出旧版…";
        var script=Path.Combine(root,"start.ps1"); if(!File.Exists(script))throw new Exception("请完整解压电脑端压缩包后运行。");
        feedback.Text="请允许系统管理员提示，正在启动网络组件…";
        DateTime startupBegan=DateTime.UtcNow;
        Process.Start(new ProcessStartInfo("powershell.exe","-NoProfile -ExecutionPolicy Bypass -File \""+script+"\" -NoBrowser -ManagerPid "+Process.GetCurrentProcess().Id){UseShellExecute=true,WindowStyle=ProcessWindowStyle.Hidden});
        for(int i=0;i<35;i++){
            await Task.Delay(1000);
            try{await Task.Run(()=>{if(Convert.ToString(Object("/management")["version"])!="0.3.17")throw new Exception();Token();});return;}catch{}
            string failure=StartupFailure(startupBegan);
            if(failure!=null)throw new Exception("启动失败："+failure);
        }
        throw new Exception("电脑端未启动。请允许管理员提示，并确认已完整解压程序。");
    }
    string StartupFailure(DateTime began){
        try{
            string path=Path.Combine(root,"startup-status.json");
            if(!File.Exists(path)||File.GetLastWriteTimeUtc(path)<began)return null;
            var state=json.Deserialize<Dictionary<string,object>>(File.ReadAllText(path,Encoding.UTF8));
            if(Convert.ToString(state["stage"])=="failed")return Convert.ToString(state["error"]);
        }catch{}return null;
    }
    async Task Prepare(){await Work(async()=>{await EnsureBackend();feedback.Text="正在自动设置访问权限与配对密码，无需打开 RustDesk 设置…";await Task.Run(()=>Request("/action","prepare"));feedback.Text="日常远控已准备。手机扫码后即可直接连接，访问密码已随配对自动处理。";await ShowQr();});}
    async Task Pair(){await Work(async()=>{await EnsureBackend();await ShowQr();});}
    async Task ShowQr(){await Task.Run(()=>Request("/action","pair"));byte[] bytes=await Task.Run(()=>{using(var c=new Client())return c.DownloadData(BaseUrl+"/pair.png");});using(var memory=new MemoryStream(bytes)){var old=qr.Image;qr.Image=new Bitmap(Image.FromStream(memory));if(old!=null)old.Dispose();}qrUntil=DateTime.Now.AddMinutes(2);}
    async Task ExitCompletely(){
        if(closing||exiting)return;
        exitRequested=true;
        if(busy){feedback.Text="已安排退出，当前准备操作结束后立即关闭全部组件…";return;}
        closing=true;timer.Stop();ShowWindow();start.Enabled=pair.Enabled=stop.Enabled=false;
        feedback.Text="正在关闭网络、远控和联调组件，确认全部结束后退出…";
        try {
            await Task.Run(()=>{
                var leases=ProcessLifetime.Capture(root);
                try {
                    try{Token();Request("/action","quit");}catch(WebException){} // absence is NOT proof of exit
                    ProcessLifetime.WaitForRelease(root,leases,TimeSpan.FromSeconds(35));
                }finally{foreach(var lease in leases)lease.Dispose();}
            });
            exiting=true;tray.Visible=false;Close();
        }catch(Exception ex){closing=false;exitRequested=false;start.Enabled=pair.Enabled=stop.Enabled=true;feedback.Text=ex.Message;timer.Start();MessageBox.Show(this,ex.Message,"电脑端尚未完全关闭",MessageBoxButtons.OK,MessageBoxIcon.Warning);}
    }
    Task UsbInstall(){return UsbPrepare("Install");}
    Task UsbCheck(){return UsbPrepare("Check");}
    Task UsbPair(){return UsbPrepare("Pair");}
    async Task UsbPrepare(string mode){await Work(async()=>{
        var script=Path.Combine(root,"usb-prepare.ps1");if(!File.Exists(script))throw new Exception("此开发包缺少 USB 准备工具，请完整解压新版电脑端。");
        if(mode=="Pair")await EnsureBackend();
        feedback.Text=mode=="Install"?"正在检查 USB 手机并安全升级，系统授权请在手机上确认…":mode=="Pair"?"正在准备首次无线配对，请保持手机解锁；配对码不会保存…":"正在检查 USB 授权和无线调试，不修改手机设置…";
        string result=await Task.Run(()=>{using(var p=new Process()){
            p.StartInfo=new ProcessStartInfo("powershell.exe","-NoProfile -ExecutionPolicy Bypass -File \""+script+"\" -Mode "+mode){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true,StandardOutputEncoding=Encoding.UTF8,StandardErrorEncoding=Encoding.UTF8};p.Start();var output=p.StandardOutput.ReadToEndAsync();var error=p.StandardError.ReadToEndAsync();
            if(!p.WaitForExit(180000)){try{p.Kill();}catch{}throw new Exception("USB 准备超时，请查看手机授权提示后重新检查安装状态。");}
            if(p.ExitCode!=0)throw new Exception(error.Result);return output.Result;
        }});
        feedback.Text="USB 检查完成，具体结果见检查报告。";
        using(var report=new Form{Text="USB 准备检查报告",Size=new Size(690,590),StartPosition=FormStartPosition.CenterParent,Icon=Icon,BackColor=Color.White}){
            report.Controls.Add(new TextBox{Multiline=true,ReadOnly=true,ScrollBars=ScrollBars.Vertical,Dock=DockStyle.Fill,BorderStyle=BorderStyle.None,Font=new Font("Microsoft YaHei UI",11),Text=result,BackColor=Color.White});report.Padding=new Padding(24);report.ShowDialog(this);
        }
    });}
    async Task Sunshine(){await Work(async()=>{
        await EnsureBackend();await Task.Run(()=>Request("/action","sunshine"));
        feedback.Text="正在检测硬件编码器，首次启动可能需要约 30 秒…";
        for(int n=0;n<60;n++){
            if(exitRequested)return;
            var state=await Task.Run(()=>Object("/management"));var streaming=state.ContainsKey("streaming")?state["streaming"] as Dictionary<string,object>:null;
            if(streaming!=null){
                if(Flag(streaming,"ready")){feedback.Text="高帧率已准备，手机打开高帧率模式即可继续自动配对。";return;}
                string error=Convert.ToString(streaming["error"]);if(!string.IsNullOrEmpty(error))throw new Exception(error);
            }
            await Task.Delay(1000);
        }
        throw new Exception("高帧率组件仍未准备好，请查看电脑编码器与显示器状态。");
    });}
    void Help(){MessageBox.Show(this,"日常使用：\n1. 电脑点“一键准备并连接”。\n2. 手机扫码，开启智能分流。\n3. 手机点“日常远控”，自动使用配对密码。\n\n手机报错：更新到最新APK，点“查看连接诊断”复制具体错误。\n\n电脑应保持开机联网且不休眠；当前版本没有自动修改开机启动和电源策略。\n\n本工具会加密备份原RustDesk配置，再将远控限制为已配对手机访问。", "使用说明", MessageBoxButtons.OK, MessageBoxIcon.Information);}
    [STAThread] static void Main(string[] args){
        Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);
        bool preview=(args.Length==2||args.Length==3)&&args[0]=="--preview", created;
        using(var instance=new System.Threading.Mutex(true,"Local\\WangChuanLink-Manager",out created)) {
            if(!created&&!preview){MessageBox.Show("管理工具已经打开，请从任务栏或托盘打开现有窗口。","随连");return;}
            using(var app=new Manager(preview)) {
                if(preview){app.Show();if(args.Length==3&&app.pages.ContainsKey(args[2]))app.SelectPage(args[2]);Application.DoEvents();using(var bmp=new Bitmap(app.Width,app.Height)){app.DrawToBitmap(bmp,new Rectangle(Point.Empty,app.Size));bmp.Save(args[1]);}app.exiting=true;app.Close();}
                else Application.Run(app);
            }
        }
    }
}
