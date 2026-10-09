using System;
using System.Drawing;
using System.IO;
using System.Windows.Forms;

// Temporary, self-contained target. It records event counts, never typed text.
class RemoteInputTarget : Form {
    readonly string report;readonly Timer timer=new Timer();readonly DateTime started=DateTime.UtcNow;
    long frames, clicks, keys, wheels;DateTime saved=DateTime.MinValue;
    RemoteInputTarget(string path){report=path;Text="随连 · 远控验收测试窗口";BackColor=Color.FromArgb(20,38,67);ForeColor=Color.White;
        WindowState=FormWindowState.Maximized;DoubleBuffered=true;KeyPreview=true;
        MouseDown+=(s,e)=>{clicks++;Save();};MouseWheel+=(s,e)=>{wheels++;Save();};KeyDown+=(s,e)=>{keys++;Save();if(e.KeyCode==Keys.Escape)Close();};
        timer.Interval=16;timer.Tick+=(s,e)=>{frames++;Invalidate();if((DateTime.UtcNow-saved).TotalMilliseconds>500)Save();};Shown+=(s,e)=>{timer.Start();Focus();Save();};FormClosed+=(s,e)=>{timer.Stop();Save();};
    }
    void Save(){saved=DateTime.UtcNow;File.WriteAllText(report,"{\"mouseDown\":"+clicks+",\"keyDown\":"+keys+",\"wheel\":"+wheels+",\"frames\":"+frames+",\"elapsedMs\":"+(long)(saved-started).TotalMilliseconds+"}");}
    protected override void OnPaint(PaintEventArgs e){base.OnPaint(e);var g=e.Graphics;g.SmoothingMode=System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
        using(var title=new Font("Microsoft YaHei UI",36))using(var detail=new Font("Microsoft YaHei UI",24)){
            g.DrawString("随连 · 实机远控验收",title,Brushes.White,70,75);
            g.DrawString("点击画面或发送按键，验证手机 → 电脑输入",detail,Brushes.LightSkyBlue,70,175);
            g.DrawString("鼠标点击 "+clicks+"    按键 "+keys+"    滚轮 "+wheels,detail,Brushes.White,70,265);
            g.DrawString("动态帧序号  "+frames,detail,Brushes.White,70,350);
            g.DrawString("此窗口仅记录计数。Esc 关闭。",detail,Brushes.LightGray,70,440);
        }
        g.FillEllipse(Brushes.MediumAquamarine,60+(int)(frames*7%Math.Max(1,ClientSize.Width-170)),Math.Max(530,ClientSize.Height-170),80,80);
    }
    [STAThread]static void Main(string[] args){Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);Application.Run(new RemoteInputTarget(args.Length>0?args[0]:Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"remote-input.json")));}
}
