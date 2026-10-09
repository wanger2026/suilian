using System;
using System.Diagnostics;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

class ManagerLifetimeTests {
    static void Assert(bool value,string message){if(!value)throw new Exception(message);}
    static string fixtures;
    static Process Fixture(){
        var p=Process.Start(new ProcessStartInfo(Path.Combine(fixtures,"fixture.exe"),"--fixture \""+fixtures+"\""){UseShellExecute=false,CreateNoWindow=true});
        var until=DateTime.UtcNow.AddSeconds(8);
        while(!File.Exists(Path.Combine(fixtures,"ready"))&&DateTime.UtcNow<until)Thread.Sleep(20);
        Assert(File.Exists(Path.Combine(fixtures,"ready")),"fixture did not start");return p;
    }
    static void LifetimeTests(){
        fixtures=Path.Combine(Path.GetDirectoryName(typeof(ManagerLifetimeTests).Assembly.Location),"fixtures",Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(fixtures);
        File.Copy(typeof(ManagerLifetimeTests).Assembly.Location,Path.Combine(fixtures,"fixture.exe"),true);
        string ready=Path.Combine(fixtures,"ready");if(File.Exists(ready))File.Delete(ready);
        using(var child=Fixture()) {
            var leases=ProcessLifetime.Capture(fixtures);
            try {
                Assert(leases.Count==1&&leases[0].Id==child.Id,"ownership must match the exact fixture folder");
                bool refused=false;try{ProcessLifetime.WaitForRelease(fixtures,leases,TimeSpan.FromMilliseconds(300));}catch(InvalidOperationException){refused=true;}
                Assert(refused,"API absence must not count as all components exited");
                bool locked=false;try{File.Move(Path.Combine(fixtures,"locked"),Path.Combine(fixtures,"moved"));}catch(IOException){locked=true;}
                Assert(locked,"fixture did not hold a real file lock");
                child.Kill();ProcessLifetime.WaitForRelease(fixtures,leases,TimeSpan.FromSeconds(8));
                File.Move(Path.Combine(fixtures,"locked"),Path.Combine(fixtures,"moved"));
            }finally{foreach(var lease in leases)lease.Dispose();if(!child.HasExited)child.Kill();child.WaitForExit();}
        }
        Console.WriteLine("PASS process ownership, stale-component failure, exit wait and file release");
    }
    static void WindowTests(){
        foreach(bool unavailable in new[]{false,true}) {
            using(var app=new Manager(false)) {
                int quits=0;
                app.RequestTest=(path,body)=>{
                    if(unavailable)throw new System.Net.WebException("fixture backend is absent");
                    if(path=="/manager-token")return "{\"token\":\"fixture\"}";
                    if(path=="/action"&&body=="quit"){Interlocked.Increment(ref quits);return "";}
                    return "{}";
                };
                app.Show();Application.DoEvents();app.Close();
                var until=DateTime.UtcNow.AddSeconds(10);
                while(!app.IsDisposed&&DateTime.UtcNow<until){Application.DoEvents();Thread.Sleep(20);}
                Assert(app.IsDisposed,"X hid the window or failed to complete exit");
                Assert(unavailable||quits==1,"X must request backend shutdown exactly once");
            }
        }
        Console.WriteLine("PASS actual FormClosing exits the form with a live or absent backend");
    }
    static DataGridView FindGrid(Control parent){foreach(Control control in parent.Controls){var found=control as DataGridView;if(found!=null)return found;found=FindGrid(control);if(found!=null)return found;}return null;}
    static void DashboardTests(){
        using(var app=new Manager(false)){
            int historyCalls=0;string lastHistoryPath="";
            app.RequestTest=(path,body)=>{
                if(path.StartsWith("/traffic-history?")){Interlocked.Increment(ref historyCalls);lastHistoryPath=path;}
                if(path.StartsWith("/traffic-history?"))return "{\"up\":1024,\"down\":2048,\"count\":1,\"failed\":0,\"error\":\"\",\"totalRows\":1,\"rows\":[{\"app\":\"Fixture Browser\",\"package\":\"fixture.browser\",\"host\":\"fixture.invalid\",\"route\":\"computer\",\"protocol\":\"TCP\",\"day\":\"2026-10-08\",\"first\":1791399600000,\"last\":1791399600000,\"up\":1024,\"down\":2048,\"count\":1}]}";
                if(path=="/management")return "{\"networkReady\":true,\"remoteReady\":true,\"remoteConfigured\":true,\"network\":{\"phoneJoined\":true,\"message\":\"fixture direct\"},\"development\":{\"status\":\"fixture inactive\"},\"traffic\":{\"downloadBytes\":2048,\"uploadBytes\":1024,\"activeConnections\":1,\"failedConnections\":0,\"flows\":[{\"target\":\"fixture.invalid:443\",\"active\":true,\"started\":1791399600,\"downloadBytes\":2048,\"uploadBytes\":1024}]}}";
                return path=="/manager-token"?"{\"token\":\"fixture\"}":"{}";
            };
            app.Show();typeof(Manager).GetMethod("SelectPage",System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic).Invoke(app,new object[]{"连接与流量"});var grid=FindGrid(app);var until=DateTime.UtcNow.AddSeconds(5);
            while(grid.Rows.Count==0&&DateTime.UtcNow<until){Application.DoEvents();Thread.Sleep(20);}
            Assert(grid.Rows.Count==1,"backend JSON did not reach the connection table");
            Assert(Convert.ToString(grid.Rows[0].Cells[1].Value)=="fixture.invalid"&&Convert.ToString(grid.Rows[0].Cells[3].Value)=="2.0 KB","destination or payload counters rendered incorrectly");
            var flags=System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic;
            // An automatic refresh must not silently apply half-typed filters.
            ((TextBox)typeof(Manager).GetField("historyApp",flags).GetValue(app)).Text="not-yet-applied";
            typeof(Manager).GetField("historyAppliedPath",flags).SetValue(app,lastHistoryPath.Replace(DateTime.Today.ToString("yyyy-MM-dd"),DateTime.Today.AddDays(-1).ToString("yyyy-MM-dd")));
            typeof(Manager).GetField("historyRefreshed",flags).SetValue(app,DateTime.UtcNow.AddSeconds(-35));
            int before=historyCalls;
            typeof(Manager).GetMethod("MaybeRefreshHistory",flags).Invoke(app,null);
            until=DateTime.UtcNow.AddSeconds(5);
            while((historyCalls==before||(bool)typeof(Manager).GetField("historyLoading",flags).GetValue(app))&&DateTime.UtcNow<until){Application.DoEvents();Thread.Sleep(20);}
            Assert(historyCalls==before+1,"visible history did not refresh automatically");
            Assert(!lastHistoryPath.Contains("not-yet-applied"),"automatic refresh applied unsaved filters");
            Assert(lastHistoryPath.Contains("&to="+DateTime.Today.ToString("yyyy-MM-dd")),"rolling date range failed at midnight");
            typeof(Manager).GetMethod("SelectPage",flags).Invoke(app,new object[]{"概览"});
            typeof(Manager).GetField("historyRefreshed",flags).SetValue(app,DateTime.UtcNow.AddSeconds(-35));
            before=historyCalls;typeof(Manager).GetMethod("MaybeRefreshHistory",flags).Invoke(app,null);Application.DoEvents();
            Assert(historyCalls==before,"hidden history performed unnecessary polling");
            app.Close();until=DateTime.UtcNow.AddSeconds(10);while(!app.IsDisposed&&DateTime.UtcNow<until){Application.DoEvents();Thread.Sleep(20);}
            Assert(app.IsDisposed,"dashboard test did not exit");
        }
        Console.WriteLine("PASS management JSON to rendered connection table; fixture transport only");
    }
    [STAThread] static int Main(string[] args){
        if(args.Length==2&&args[0]=="--fixture") {
            using(var file=new FileStream(Path.Combine(args[1],"locked"),FileMode.Create,FileAccess.ReadWrite,FileShare.None)) {
                File.WriteAllText(Path.Combine(args[1],"ready"),"ready");Thread.Sleep(30000);
            }return 0;
        }
        try{
            Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);
            LifetimeTests();WindowTests();DashboardTests();return 0;
        }catch(Exception ex){Console.Error.WriteLine(ex);return 1;}
    }
}
