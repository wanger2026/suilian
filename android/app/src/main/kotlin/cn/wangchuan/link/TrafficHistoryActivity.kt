package cn.wangchuan.link

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrafficHistoryActivity: Activity() {
    private lateinit var summary:TextView
    private lateinit var syncState:TextView
    private val statusHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshStatus=object:Runnable { override fun run(){syncState.text=TrafficHistory.syncDescription();statusHandler.postDelayed(this,3000)} }
    private lateinit var rows:LinearLayout
    private lateinit var from:EditText;private lateinit var to:EditText
    private lateinit var app:EditText;private lateinit var host:EditText
    private lateinit var route:Spinner;private lateinit var group:Spinner
    private var offset=0;private var total=0;private var ticket=0
    private val ink=0xff182a42.toInt();private val muted=0xff5b6c81.toInt()
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun label(s:String,size:Float=14f,color:Int=ink)=TextView(this).apply { text=s;textSize=size;setTextColor(color);setPadding(0,dp(7),0,dp(7)) }
    private fun column()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun button(p:LinearLayout,s:String,run:()->Unit){p.addView(TextView(this).apply{text=s;textSize=15f;gravity=android.view.Gravity.CENTER;minHeight=dp(48);setPadding(dp(12),dp(12),dp(12),dp(12));setTextColor(0xff2f64ed.toInt());background=GradientDrawable().apply{setColor(0xffedf3ff.toInt());cornerRadius=dp(14).toFloat()};isClickable=true;isFocusable=true;setOnClickListener{run()}},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8);bottomMargin=dp(8)})}
    private fun card(p:LinearLayout)=column().also { c-> c.setPadding(dp(16),dp(14),dp(16),dp(14));c.background=GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(18).toFloat()};p.addView(c,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(14)}) }
    private fun input(p:LinearLayout,h:String,value:String="")=EditText(this).also { it.hint=h;it.setText(value);it.setTextColor(ink);it.setTextSize(14f);it.setSingleLine();p.addView(it) }
    private fun spinner(p:LinearLayout,items:List<String>)=Spinner(this).also{it.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,items);p.addView(it)}
    override fun onCreate(state:Bundle?){
        super.onCreate(state);window.statusBarColor=0xfff4f7fc.toInt();window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val page=column().apply{setPadding(dp(20),dp(16),dp(20),dp(24));setBackgroundColor(0xfff4f7fc.toInt())}
        val root=column().apply{setBackgroundColor(0xfff4f7fc.toInt())}
        root.setOnApplyWindowInsetsListener{v,i->v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom);i}
        root.addView(ScrollView(this).apply{isFillViewport=true;addView(page)});setContentView(root)
        button(page,"‹ 返回应用与流量"){finish()}
        page.addView(label("历史与累计",27f).apply{setTypeface(null,Typeface.BOLD)})
        page.addView(label("软件、网站与线路，一起看清楚",14f,muted))
        val filterCard=card(page);val f=column().apply{visibility=View.GONE}
        button(filterCard,"筛选条件 · 日期 / 软件 / 网站 / 线路"){f.visibility=if(f.visibility==View.GONE)View.VISIBLE else View.GONE}
        filterCard.addView(f);val date=SimpleDateFormat("yyyy-MM-dd",Locale.ROOT)
        f.addView(label("起止日期（包含首尾两天）",12f,muted))
        from=input(f,"开始日期 yyyy-MM-dd",date.format(Date(System.currentTimeMillis()-29L*86400000)))
        to=input(f,"结束日期 yyyy-MM-dd",date.format(Date()))
        button(f,"今天"){from.setText(date.format(Date()));to.setText(date.format(Date()));offset=0;load()}
        button(f,"全部已保留历史（最多365天）"){from.setText(date.format(Date(System.currentTimeMillis()-364L*86400000)));to.setText(date.format(Date()));offset=0;load()}
        app=input(f,"软件名或包名，例如：浏览器")
        host=input(f,"网站域名或 IP，例如：google.com")
        route=spinner(f,listOf("全部线路","手机直连","电脑出口","已阻止","规则分流"))
        group=spinner(f,listOf("每日明细","按软件汇总","按网站汇总"))
        button(f,"应用筛选 / 刷新"){offset=0;load()}
        val stats=card(page);summary=label("正在读取…",16f);stats.addView(summary)
        stats.addView(label("自动同步到电脑",16f).apply{setTypeface(null,Typeface.BOLD)})
        syncState=label(TrafficHistory.syncDescription(),12f,muted);stats.addView(syncState)
        page.addView(label("采样统计只覆盖进入随连的连接，短连接可能漏计；绕过应用不采集。网站显示域名/IP，不读取 HTTPS 路径、正文或账号。每日汇总保留365天；从本版启用后开始积累。",12f,muted))
        rows=column();page.addView(rows)
        button(page,"上一页"){if(offset>=100){offset-=100;load()}}
        button(page,"下一页"){if(offset+100<total){offset+=100;load()}}
        load()
    }
    override fun onResume(){super.onResume();statusHandler.post(refreshStatus)}
    override fun onPause(){statusHandler.removeCallbacks(refreshStatus);super.onPause()}
    private fun load(){
        val a=from.text.toString();val b=to.text.toString();val format=SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).apply{isLenient=false}
        if(!a.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))||!b.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))||runCatching{format.parse(a);format.parse(b);a<=b}.getOrDefault(false).not()) {summary.text="请填写有效的开始和结束日期";return}
        val current=++ticket;summary.text="正在查询本地历史…"
        TrafficHistory.query(a,b,app.text.toString(),host.text.toString(),if(route.selectedItemPosition==0)""else route.selectedItem.toString(),listOf("detail","app","host")[group.selectedItemPosition],offset){result->
            if(isFinishing||current!=ticket)return@query
            if(result.has("error")){summary.text=result.getString("error");return@query}
            total=result.optInt("totalRows");summary.text="筛选累计  ↓ ${NetworkOverview.bytes(result.optLong("down"))}  ↑ ${NetworkOverview.bytes(result.optLong("up"))}\n观测连接 ${result.optLong("count")} · ${total} 条汇总\n第 ${offset/100+1} 页"
            rows.removeAllViews();val list=result.optJSONArray("rows")
            if(list==null||list.length()==0)rows.addView(label("暂无匹配记录。保持连接后刷新，或调整筛选条件。",14f,muted))
            else for(i in 0 until list.length())addRow(list.getJSONObject(i))
        }
    }
    private fun addRow(r:JSONObject){
        val c=card(rows);c.addView(label(r.optString("app"),17f).apply{setTypeface(null,Typeface.BOLD)})
        val pkg=r.optString("package");if(pkg.isNotBlank())c.addView(label(pkg,11f,muted))
        c.addView(label(r.optString("host"),15f));c.addView(label("${r.optString("day")} · ${r.optString("route")} ${r.optString("protocol")}",12f,muted))
        c.addView(label("↓ ${NetworkOverview.bytes(r.optLong("down"))}   ↑ ${NetworkOverview.bytes(r.optLong("up"))}   ${r.optLong("count")} 个连接",14f))
        val date=SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA)
        c.addView(label("首次 ${date.format(Date(r.optLong("first")))}\n最近 ${date.format(Date(r.optLong("last")))}",12f,muted))
    }
}
