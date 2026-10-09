using System;
using System.Collections;
using System.Collections.Generic;
using System.Drawing;
using System.Threading.Tasks;
using System.Windows.Forms;

partial class Manager {
    DateTimePicker historyFrom,historyTo;
    TextBox historyApp,historyHost;
    ComboBox historySource,historyRoute,historyGroup;
    Label historySummary,historyPage;
    DataGridView historyGrid;
    int historyOffset,historyTotal,historyTicket;
    DateTime historyRefreshed=DateTime.MinValue;
    string historyAppliedPath,historyAppliedSource;
    bool historyLoading;
    int historyDateDays=30,historyAppliedDateDays=30;
    bool historySettingDates;
    void SetHistoryDates(int days){historySettingDates=true;historyFrom.Value=DateTime.Today.AddDays(1-days);historyTo.Value=DateTime.Today;historySettingDates=false;historyDateDays=days;historyOffset=0;LoadHistory();}
    void BuildHistory(Panel page) {
        var f=Card(page,0,8,850,210);
        AddText(f,"历史连接与累计",17,20,12,790,32);
        historyFrom=new DateTimePicker{Location=new Point(22,55),Width=155,Format=DateTimePickerFormat.Custom,CustomFormat="yyyy-MM-dd",Value=DateTime.Today.AddDays(-29)};
        historyTo=new DateTimePicker{Location=new Point(198,55),Width=155,Format=DateTimePickerFormat.Custom,CustomFormat="yyyy-MM-dd",Value=DateTime.Today};
        f.Controls.Add(historyFrom);f.Controls.Add(historyTo);
        historyFrom.ValueChanged+=(s,e)=>{if(!historySettingDates)historyDateDays=0;};historyTo.ValueChanged+=(s,e)=>{if(!historySettingDates)historyDateDays=0;};
        AddAction(f,"今天",372,48,105,(s,e)=>SetHistoryDates(1));
        AddAction(f,"全部历史",488,48,134,(s,e)=>SetHistoryDates(365));
        AddAction(f,"筛选 / 刷新",637,48,188,(s,e)=>{historyOffset=0;LoadHistory();},true);
        AddText(f,"软件名 / 包名",9,22,96,190,22,true);AddText(f,"网站域名 / IP",9,235,96,195,22,true);
        historyApp=new TextBox{Location=new Point(22,121),Width=195};historyHost=new TextBox{Location=new Point(235,121),Width=210};f.Controls.Add(historyApp);f.Controls.Add(historyHost);
        historySource=HistoryChoice(f,465,119,360,new[]{"手机应用记录（采样，含手机直连）","电脑实际出口（TCP，无法直接识别手机应用）"});
        historyGroup=HistoryChoice(f,22,165,195,new[]{"每日明细","按软件汇总","按网站汇总"});
        historyRoute=HistoryChoice(f,235,165,210,new[]{"全部线路","手机直连","电脑出口","已阻止","规则分流"});
        historySummary=TextLabel("手机历史自动同步；本页约每30秒自动刷新。",11,Ink,4,232,844,66);page.Controls.Add(historySummary);
        historyGrid=new DataGridView{Location=new Point(0,310),Size=new Size(850,220),ReadOnly=true,AllowUserToAddRows=false,AllowUserToDeleteRows=false,RowHeadersVisible=false,BackgroundColor=Color.White,BorderStyle=BorderStyle.None,SelectionMode=DataGridViewSelectionMode.FullRowSelect,AutoSizeColumnsMode=DataGridViewAutoSizeColumnsMode.Fill,MultiSelect=false};
        foreach(string title in new[]{"软件 / 包名","网站 / IP","线路","下载","上传","连接数","最近访问"})historyGrid.Columns.Add(title,title);
        historyGrid.Columns[0].FillWeight=190;historyGrid.Columns[1].FillWeight=200;historyGrid.Columns[6].FillWeight=140;historyGrid.RowTemplate.Height=36;
        historyGrid.CellDoubleClick+=(s,e)=>{if(e.RowIndex>=0){var r=historyGrid.Rows[e.RowIndex].Tag as Dictionary<string,object>;if(r!=null)MessageBox.Show(this,"软件："+r["app"]+"\n包名："+r["package"]+"\n网站："+r["host"]+"\n日期："+r["day"]+"\n线路："+r["route"]+"\n协议："+r["protocol"]+"\n首次："+HistoryTime(r["first"])+"\n最近："+HistoryTime(r["last"]),"访问汇总详情");}};
        page.Controls.Add(historyGrid);
        AddAction(page,"上一页",0,543,114,(s,e)=>{if(historyOffset>=100){historyOffset-=100;LoadHistory();}});
        AddAction(page,"下一页",127,543,114,(s,e)=>{if(historyOffset+100<historyTotal){historyOffset+=100;LoadHistory();}});
        historyPage=TextLabel("每页100条",10,Muted,263,553,570,28);page.Controls.Add(historyPage);
        AddText(page,"保留365天，从本版开始累计。两种统计口径分开查询，不重复相加。手机采样可能漏掉短连接，绕过应用不采集；HTTPS 显示域名/IP，不含网页路径和正文。双击行查看详情。",9,2,607,846,67,true);
    }
    ComboBox HistoryChoice(Panel p,int x,int y,int w,string[] items){var c=new ComboBox{Location=new Point(x,y),Width=w,DropDownStyle=ComboBoxStyle.DropDownList};c.Items.AddRange(items);c.SelectedIndex=0;p.Controls.Add(c);return c;}
    string HistoryTime(object value){return DateTimeOffset.FromUnixTimeMilliseconds(Convert.ToInt64(value)).LocalDateTime.ToString("MM-dd HH:mm:ss");}
    void MaybeRefreshHistory(){
        Panel page;
        if(Visible&&!historyLoading&&pages.TryGetValue("连接与流量",out page)&&page.Visible&&(DateTime.UtcNow-historyRefreshed).TotalSeconds>=30)LoadHistory(true);
    }
    async void LoadHistory(bool automatic=false){
        if(historyGrid==null||preview||closing)return;
        if(!automatic&&historyFrom.Value.Date>historyTo.Value.Date){historySummary.Text="开始日期不能晚于结束日期。";return;}
        int request=++historyTicket;
        string source=historySource.SelectedIndex==0?"phone":"pc";
        string route=historyRoute.SelectedIndex==0?"":Convert.ToString(historyRoute.SelectedItem);
        string group=new[]{"detail","app","host"}[historyGroup.SelectedIndex];
        string path="/traffic-history?from="+historyFrom.Value.ToString("yyyy-MM-dd")+"&to="+historyTo.Value.ToString("yyyy-MM-dd")+"&app="+Uri.EscapeDataString(historyApp.Text)+"&host="+Uri.EscapeDataString(historyHost.Text)+"&route="+Uri.EscapeDataString(route)+"&source="+source+"&group="+group+"&offset="+historyOffset;
        if(automatic&&historyAppliedPath!=null){
            path=historyAppliedPath;source=historyAppliedSource;
            if(historyAppliedDateDays>0){
                path=System.Text.RegularExpressions.Regex.Replace(path,"([?&]from=)[0-9-]+","${1}"+DateTime.Today.AddDays(1-historyAppliedDateDays).ToString("yyyy-MM-dd"));
                path=System.Text.RegularExpressions.Regex.Replace(path,"([?&]to=)[0-9-]+","${1}"+DateTime.Today.ToString("yyyy-MM-dd"));
                if(historyDateDays==historyAppliedDateDays){historySettingDates=true;historyFrom.Value=DateTime.Today.AddDays(1-historyDateDays);historyTo.Value=DateTime.Today;historySettingDates=false;}
                historyAppliedPath=path;
            }
        }
        else{historyAppliedPath=path;historyAppliedSource=source;historyAppliedDateDays=historyDateDays;}
        int scroll=historyGrid.FirstDisplayedScrollingRowIndex;
        var selected=historyGrid.CurrentRow==null?null:historyGrid.CurrentRow.Tag as Dictionary<string,object>;
        historyLoading=true;historyRefreshed=DateTime.UtcNow;
        if(!automatic)historySummary.Text="正在查询本地历史…";
        try{var result=await Task.Run(()=>Object(path));if(request!=historyTicket||IsDisposed)return;
            historyTotal=Convert.ToInt32(result["totalRows"]);historySummary.Text="筛选累计  下载 "+ByteSize(result["down"])+"    上传 "+ByteSize(result["up"])+"    "+(source=="phone"?"观测":"实际")+"连接 "+result["count"]+"\n"+(source=="phone"?"来自手机本地采样，同步可能延迟；完全绕过的应用不采集。":"电脑实际 TCP 有效载荷，不含 UDP、远控和串流；应用身份请切换手机记录。")+Convert.ToString(result["error"]);
            historyGrid.Rows.Clear();var list=result["rows"] as IEnumerable;if(list!=null)foreach(var item in list){var r=item as Dictionary<string,object>;if(r==null)continue;int n=historyGrid.Rows.Add(r["app"]+" / "+r["package"],r["host"],r["route"],ByteSize(r["down"]),ByteSize(r["up"]),r["count"],HistoryTime(r["last"]));historyGrid.Rows[n].Tag=r;}
            if(automatic){
                if(scroll>=0&&scroll<historyGrid.Rows.Count)historyGrid.FirstDisplayedScrollingRowIndex=scroll;
                if(selected!=null&&selected.ContainsKey("id"))foreach(DataGridViewRow row in historyGrid.Rows){var data=row.Tag as Dictionary<string,object>;if(data!=null&&data.ContainsKey("id")&&Equals(data["id"],selected["id"])){historyGrid.CurrentCell=row.Cells[0];break;}}
            }
            historyPage.Text="第 "+(historyOffset/100+1)+" 页，共 "+historyTotal+" 条 · 自动刷新 "+DateTime.Now.ToString("HH:mm:ss");
        }catch{if(request==historyTicket&&!IsDisposed)historySummary.Text="暂时无法读取历史，30秒后自动重试；原数据保留。";}
        finally{if(request==historyTicket)historyLoading=false;}
    }
}
