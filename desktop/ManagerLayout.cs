using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Windows.Forms;

partial class Manager {
    readonly Dictionary<string,Panel> pages=new Dictionary<string,Panel>();
    readonly Dictionary<string,Button> navigation=new Dictionary<string,Button>();
    Label pageTitle, pageDescription, stateTag, downMetric, upMetric, countMetric, deviceSummary;
    Panel content;

    CardPanel Card(Panel page,int x,int y,int w,int h){var card=new CardPanel{Location=new Point(x,y),Size=new Size(w,h)};page.Controls.Add(card);return card;}
    Panel Page(string name){var p=new Panel{Dock=DockStyle.Fill,AutoScroll=true,Visible=false};pages.Add(name,p);content.Controls.Add(p);return p;}
    void AddText(Control parent,string value,int size,int x,int y,int w,int h,bool muted=false){parent.Controls.Add(TextLabel(value,size,muted?Muted:Ink,x,y,w,h));}
    void AddAction(Control parent,string value,int x,int y,int w,EventHandler click,bool primary=false){var b=ActionButton(value,x,y,w,primary);b.Click+=click;parent.Controls.Add(b);}
    Label Metric(Panel page,string title,int x){var c=Card(page,x,366,270,136);AddText(c,title,10,22,18,230,28,true);var v=TextLabel("—",26,Ink,20,49,238,48);c.Controls.Add(v);AddText(c,"本次电脑服务运行期间",9,22,106,232,23,true);return v;}

    void BuildLayout(){
        Text="随连 · 设备管理";ClientSize=new Size(1120,760);MinimumSize=new Size(1136,799);
        Font=new Font("Microsoft YaHei UI",10);BackColor=Color.FromArgb(244,247,252);ForeColor=Ink;
        StartPosition=FormStartPosition.CenterScreen;Icon=Branding.AppIcon();AutoScaleMode=AutoScaleMode.Dpi;
        var sidebar=new Panel{Dock=DockStyle.Left,Width=208,Height=760,BackColor=Color.FromArgb(18,31,51)};
        var mark=new PictureBox{Location=new Point(24,32),Size=new Size(43,43),Image=Branding.Mark(86),SizeMode=PictureBoxSizeMode.Zoom};sidebar.Controls.Add(mark);
        sidebar.Controls.Add(TextLabel("随连",23,Color.White,78,28,110,45));
        sidebar.Controls.Add(TextLabel("让设备，自在相连",9,Color.FromArgb(153,173,199),26,89,165,25));
        string[] names={"概览","连接与流量","设备工具","设置与帮助"};
        for(int i=0;i<names.Length;i++){
            string key=names[i];var b=ActionButton(key,18,151+i*61,172,false);b.TextAlign=ContentAlignment.MiddleLeft;b.Padding=new Padding(14,0,0,0);b.FlatAppearance.BorderSize=0;
            b.BackColor=sidebar.BackColor;b.ForeColor=Color.FromArgb(178,194,214);b.Click+=(s,e)=>SelectPage(key);navigation.Add(key,b);sidebar.Controls.Add(b);
        }
        var background=ActionButton("后台运行",20,624,168,false);background.Anchor=AnchorStyles.Left|AnchorStyles.Bottom;background.Click+=(s,e)=>RunInBackground();sidebar.Controls.Add(background);
        stop=ActionButton("彻底退出",20,684,168,false);stop.Anchor=AnchorStyles.Left|AnchorStyles.Bottom;stop.ForeColor=Color.FromArgb(171,52,71);stop.Click+=async(s,e)=>await ExitCompletely();sidebar.Controls.Add(stop);
        Controls.Add(sidebar);
        var body=new Panel{Dock=DockStyle.Fill,Padding=new Padding(26,0,26,0)};Controls.Add(body);body.BringToFront();
        var header=new Panel{Dock=DockStyle.Top,Height=117,Width=860};pageTitle=TextLabel("设备概览",24,Ink,0,28,620,43);header.Controls.Add(pageTitle);
        pageDescription=TextLabel("电脑准备一次，手机随时连接。",10,Muted,2,78,650,28);header.Controls.Add(pageDescription);
        stateTag=TextLabel("● 待准备",10,Muted,716,45,145,30);stateTag.Anchor=AnchorStyles.Top|AnchorStyles.Right;header.Controls.Add(stateTag);body.Controls.Add(header);
        var footer=new Panel{Dock=DockStyle.Bottom,Height=64,Width=860};feedback=TextLabel("首次准备可能需要 Windows 管理员确认。",9,Muted,2,10,850,46);feedback.Anchor=AnchorStyles.Left|AnchorStyles.Right|AnchorStyles.Top;footer.Controls.Add(feedback);body.Controls.Add(footer);
        content=new Panel{Dock=DockStyle.Fill};body.Controls.Add(content);content.BringToFront();

        var overview=Page("概览");var hero=Card(overview,0,8,528,338);
        AddText(hero,"你的电脑",10,22,20,460,26,true);
        headline=TextLabel("准备好后，即可连接",21,Ink,22,60,481,58);hero.Controls.Add(headline);
        start=ActionButton("一键准备连接",22,135,225,true);start.Click+=async(s,e)=>await Prepare();hero.Controls.Add(start);
        network=TextLabel("● 网络入口尚未启动",10,Muted,22,206,480,32);hero.Controls.Add(network);
        remote=TextLabel("● 日常远控将在准备时自动配置",10,Muted,22,247,480,32);hero.Controls.Add(remote);
        development=TextLabel("● AI 联调等待手机单次授权",10,Muted,22,286,480,32);hero.Controls.Add(development);
        var pairing=Card(overview,548,8,302,338);AddText(pairing,"连接手机",15,23,20,245,32);
        qr=new PictureBox{Location=new Point(60,69),Size=new Size(180,180),BackColor=Color.FromArgb(245,248,253),SizeMode=PictureBoxSizeMode.Zoom};pairing.Controls.Add(qr);
        pair=ActionButton("显示配对二维码",22,267,258,false);pair.Click+=async(s,e)=>await Pair();pairing.Controls.Add(pair);
        downMetric=Metric(overview,"手机下载",0);upMetric=Metric(overview,"手机上传",290);countMetric=Metric(overview,"正在使用的连接",580);
        AddText(overview,"二维码仅在你点击后显示，两分钟自动隐藏。点 × 会彻底退出；常驻请选择“后台运行”。",9,3,524,844,40,true);

        var connections=Page("连接与流量");BuildHistory(connections);
        deviceSummary=new Label();traffic=new Label();

        var tools=Page("设备工具");var daily=Card(tools,0,8,415,225);
        AddText(daily,"日常远控",18,22,20,364,35);AddText(daily,"访问电脑桌面、操作软件和文件。\n访问密码和直连设置会自动准备。",10,22,70,365,62,true);AddAction(daily,"准备日常远控",22,153,370,async(s,e)=>await Prepare(),true);
        var streaming=Card(tools,435,8,415,225);AddText(streaming,"高帧率串流",18,22,20,364,35);AddText(streaming,"适合视频、游戏和流畅桌面。\n电脑初始化和手机 PIN 确认自动处理。",10,22,70,365,62,true);AddAction(streaming,"准备高帧率模式",22,153,370,async(s,e)=>await Sunshine());
        var usb=Card(tools,0,255,415,251);AddText(usb,"USB 一键准备",18,22,20,364,35);AddText(usb,"升级保留配对，缺少的授权会提示。\n首次无线配对自动填写端口和配对码，\n之后只需在手机授权本次联调。",10,22,71,366,92,true);AddAction(usb,"安装升级",22,177,116,async(s,e)=>await UsbInstall());AddAction(usb,"检查权限",149,177,116,async(s,e)=>await UsbCheck());AddAction(usb,"无线配对",276,177,116,async(s,e)=>await UsbPair());
        var ai=Card(tools,435,255,415,251);AddText(ai,"AI 真机联调",18,22,20,364,35);AddText(ai,"仅在手机已进入远控画面、\n并明确授权本次联调后开放。\n退出远控会关闭联调连接。",10,22,71,366,92,true);AddAction(ai,"查看联调引导",22,177,370,(s,e)=>OpenDevelopmentGuide());
        AddText(tools,"联调开启前不开放远程 ADB。系统授权仍需你在手机上确认。",9,3,526,845,38,true);

        var settings=Page("设置与帮助");var info=Card(settings,0,8,850,260);
        AddText(info,"随连 0.3.17 · 测试版",20,24,23,790,43);AddText(info,"网络直连、日常远控和高帧率按需使用。\n历史按日保留365天，重启不清零；不记录网站正文或账号口令。\n电脑需保持开机联网。当前未自动修改你的开机启动和电源设置。",11,24,86,790,106,true);
        AddAction(info,"使用说明",24,196,250,(s,e)=>Help());AddAction(info,"打开程序文件夹",296,196,250,(s,e)=>Process.Start("explorer.exe",root));
        var exitInfo=Card(settings,0,289,850,194);AddText(exitInfo,"明确控制运行状态",16,24,22,790,35);AddText(exitInfo,"后台运行：隐藏窗口，保留手机连接。\n彻底退出或点 ×：关闭网络、远控与联调，等待所属进程释放。\n如果退出失败，会显示未结束组件，不会假装已关闭。",11,24,78,790,94,true);
        SelectPage("概览");
    }
    void SelectPage(string key){
        if(key=="连接与流量")LoadHistory();
        foreach(var item in pages)item.Value.Visible=item.Key==key;
        foreach(var item in navigation){item.Value.BackColor=item.Key==key?Color.FromArgb(41,66,104):Color.FromArgb(18,31,51);item.Value.ForeColor=item.Key==key?Color.White:Color.FromArgb(178,194,214);}
        pageTitle.Text=key=="概览"?"设备概览":key;
        pageDescription.Text=key=="概览"?"电脑准备一次，手机随时连接。":key=="连接与流量"?"按软件、网站、日期和线路筛选累计用量。":key=="设备工具"?"按需要准备远控、串流和手机开发。":"运行方式、版本和使用帮助。";
    }
    void UpdateDashboard(Dictionary<string,object> data,Dictionary<string,object> stats,bool phone,bool ready){
        stateTag.Text=ready?(phone?"● 手机已直连":"● 电脑已准备"):"● 待准备";stateTag.ForeColor=ready?Color.SeaGreen:Muted;
        deviceSummary.Text=phone?"已配对手机 · 隧道直连":"已配对手机 · 等待建立直连";
        if(stats==null)return;
        downMetric.Text=ByteSize(stats["downloadBytes"]);upMetric.Text=ByteSize(stats["uploadBytes"]);countMetric.Text=Convert.ToString(stats["activeConnections"]);
    }

    void OpenDevelopmentGuide(){MessageBox.Show(this,"1. 用 USB 连接手机，点“安装并检查手机”。\n2. 按检查结果开启系统确实缺少的调试权限。\n3. 手机进入日常远控画面，点 AI 联调并勾选本次授权。\n4. 电脑显示“真机 ADB 已验证”后，AI 才可通过专用入口操作。\n\n无线调试的首次系统确认无法由普通 APK 静默跳过。", "AI 真机联调",MessageBoxButtons.OK,MessageBoxIcon.Information);}
}
