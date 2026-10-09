package cn.wangchuan.link

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Best-effort connection sampling, not OS-wide accounting. All SQL uses bound values. */
object TrafficHistory {
    private lateinit var context: Context
    private lateinit var store: Store
    private lateinit var device: String
    private val worker = Executors.newSingleThreadExecutor()
    private val syncWorker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var collecting = false
    @Volatile private var syncing = false
    @Volatile var status = "自动同步已开启；连接电脑后自动补传"; private set
    @Volatile private var lastSync = 0L
    @Volatile private var pendingRows = 0
    @Volatile private var syncFailures = 0
    private val labels = HashMap<String, String>()
    private var ticks=0
    private var lastPrune=0L
    private val tick = object : Runnable {
        override fun run() { if (!collecting) return; NetworkOverview.refresh(); if(++ticks%10==0)sync(); main.postDelayed(this, 3000) }
    }
    class Store(c: Context): SQLiteOpenHelper(c, "traffic-history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE buckets(id TEXT PRIMARY KEY,day TEXT,app TEXT,package TEXT,host TEXT,route TEXT,protocol TEXT,up INTEGER,down INTEGER,count INTEGER,first INTEGER,last INTEGER,revision INTEGER,dirty INTEGER)")
            db.execSQL("CREATE INDEX by_day ON buckets(day)")
            db.execSQL("CREATE TABLE seen(id TEXT PRIMARY KEY,up INTEGER,down INTEGER,last INTEGER)")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
    }
    fun initialize(c: Context) {
        context=c.applicationContext; store=Store(context)
        val prefs=context.getSharedPreferences("traffic-history",Context.MODE_PRIVATE)
        lastSync=prefs.getLong("lastSync",0)
        device=prefs.getString("device",null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device",it).apply() }
        worker.execute {
            runCatching { val db=store.writableDatabase
                db.delete("buckets","day < ?",arrayOf(day(System.currentTimeMillis()-365L*86400000)))
                db.delete("seen","last < ?",arrayOf((System.currentTimeMillis()-86400000).toString()))
            }.onFailure { status="历史数据库读取失败，原文件已保留" }
        }
    }
    fun start() { if(collecting)return;collecting=true; ticks=0; main.post(tick);sync() }
    fun stop() { collecting=false;main.removeCallbacks(tick) }
    private fun day(now:Long)=SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(Date(now))
    fun record(rows: List<NetworkOverview.Connection>) {
        if (!::store.isInitialized) return
        worker.execute {
            runCatching {
                val now=System.currentTimeMillis();val db=store.writableDatabase
                db.beginTransaction()
                try {
                    if(now-lastPrune>3600000){
                        db.delete("buckets","day < ?",arrayOf(day(now-365L*86400000)))
                        db.delete("seen","last < ?",arrayOf((now-86400000).toString()));lastPrune=now
                    }
                    for(c in rows) {
                        if(c.id.isBlank()||c.route=="电脑通道")continue
                        val packages=if(c.app.isNotBlank()) listOf(c.app) else context.packageManager.getPackagesForUid(c.uid)?.toList().orEmpty()
                        val pkg=packages.joinToString(",").take(240).ifBlank { "uid:${c.uid}" }
                        val app=labels.getOrPut(pkg) { if(packages.isEmpty()) "未识别应用（UID ${c.uid}）" else packages.joinToString(" / "){ p -> runCatching{context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(p,0)).toString()}.getOrDefault(p) }.take(240) }
                        val host=c.host.take(240).ifBlank { "未识别目标" }
                        var previous: Pair<Long,Long>?=null;var lastSeen=0L
                        db.rawQuery("SELECT up,down,last FROM seen WHERE id=?",arrayOf(c.id)).use { if(it.moveToFirst()){previous=it.getLong(0) to it.getLong(1);lastSeen=it.getLong(2)} }
                        val delta=HistoryDelta.between(previous,c.upload,c.download)
                        // Idle connections need a keep-alive timestamp only every five minutes.
                        // Avoid writing the same SQLite pages every three seconds all night.
                        if(HistoryDelta.needsCheckpoint(delta,lastSeen,now))
                            db.execSQL("INSERT OR REPLACE INTO seen VALUES(?,?,?,?)",arrayOf(c.id,c.upload,c.download,now))
                        if(delta.up==0L&&delta.down==0L&&delta.count==0L)continue
                        val date=day(now)
                        val id=java.security.MessageDigest.getInstance("SHA-256").digest(listOf(device,date,pkg,host,c.route,c.protocol).joinToString("\u0000").toByteArray()).joinToString(""){"%02x".format(it)}
                        db.execSQL("INSERT OR IGNORE INTO buckets VALUES(?,?,?,?,?,?,?,0,0,0,?,?,0,1)",arrayOf(id,date,app,pkg,host,c.route,c.protocol,now,now))
                        db.execSQL("UPDATE buckets SET app=?,up=up+?,down=down+?,count=count+?,last=?,revision=revision+1,dirty=1 WHERE id=?",arrayOf(app,delta.up,delta.down,delta.count,now,id))
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }.onFailure { status="历史采集失败：${it.javaClass.simpleName}" }
        }
    }
    private val fields=listOf("id","day","app","package","host","route","protocol","up","down","count","first","last","revision")
    private fun rows(db:SQLiteDatabase,where:String,args:Array<String>,limit:String="",oldestFirst:Boolean=false):JSONArray {
        val result=JSONArray()
        val order=if(oldestFirst)"ASC"else"DESC"
        db.rawQuery("SELECT ${fields.joinToString()} FROM buckets WHERE $where ORDER BY last $order,id $limit",args).use { c ->
            while(c.moveToNext()){val row=JSONObject();fields.forEachIndexed { i,k -> row.put(k,if(i>=7)c.getLong(i) else c.getString(i)) };result.put(row)}
        };return result
    }
    fun query(from:String,to:String,app:String,host:String,route:String,group:String,offset:Int,done:(JSONObject)->Unit) {
        worker.execute {
            val result=runCatching {
                val args=mutableListOf(from,to,"%${escape(app)}%","%${escape(app)}%","%${escape(host)}%")
                var where="day>=? AND day<=? AND (app LIKE ? ESCAPE '\\' OR package LIKE ? ESCAPE '\\') AND host LIKE ? ESCAPE '\\'"
                if(route.isNotBlank()){where+=" AND route=?";args.add(route)}
                val db=store.readableDatabase
                val out=JSONObject()
                db.rawQuery("SELECT COALESCE(SUM(up),0),COALESCE(SUM(down),0),COALESCE(SUM(count),0) FROM buckets WHERE $where",args.toTypedArray()).use { it.moveToFirst();out.put("up",it.getLong(0)).put("down",it.getLong(1)).put("count",it.getLong(2)) }
                if(group=="detail") {
                    out.put("rows",rows(db,where,args.toTypedArray(),"LIMIT 100 OFFSET ${offset.coerceAtLeast(0)}"))
                    db.rawQuery("SELECT COUNT(*) FROM buckets WHERE $where",args.toTypedArray()).use { it.moveToFirst();out.put("totalRows",it.getLong(0)) }
                }else {
                    val keys=if(group=="app")"package" else "host"
                    val list=JSONArray()
                    db.rawQuery("SELECT app,package,host,SUM(up),SUM(down),SUM(count),MIN(first),MAX(last) FROM buckets WHERE $where GROUP BY $keys ORDER BY SUM(up)+SUM(down) DESC LIMIT 100 OFFSET ${offset.coerceAtLeast(0)}",args.toTypedArray()).use { c -> while(c.moveToNext())list.put(JSONObject().put("day","所选期间").put("app",if(group=="app")c.getString(0)else "全部匹配应用").put("package",if(group=="app")c.getString(1)else "").put("host",if(group=="host")c.getString(2)else "全部匹配网站").put("route","按筛选汇总").put("up",c.getLong(3)).put("down",c.getLong(4)).put("count",c.getLong(5)).put("first",c.getLong(6)).put("last",c.getLong(7))) }
                    out.put("rows",list)
                    db.rawQuery("SELECT COUNT(DISTINCT $keys) FROM buckets WHERE $where",args.toTypedArray()).use {it.moveToFirst();out.put("totalRows",it.getLong(0))}
                };pendingRows=countPending(db);out.put("status",syncDescription()).put("sync",syncDiagnostics())
            }.getOrElse { JSONObject().put("error","历史读取失败：${it.javaClass.simpleName}") }
            main.post{done(result)}
        }
    }
    private fun escape(s:String)=s.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")
    private fun countPending(db:SQLiteDatabase):Int=db.rawQuery("SELECT COUNT(*) FROM buckets WHERE dirty=1",null).use { it.moveToFirst();it.getInt(0) }
    fun syncDiagnostics()=JSONObject().put("automatic",true).put("intervalSeconds",30)
        .put("pendingRows",pendingRows).put("lastSuccess",lastSync).put("consecutiveFailures",syncFailures).put("syncing",syncing)
    fun syncDescription():String {
        val last=if(lastSync>0)SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(Date(lastSync))else"尚无成功记录"
        val mode=if(LinkVpnService.active)"连接期间约每30秒自动同步"else"连接电脑后自动补传"
        val detail=if(pendingRows>0&&status.startsWith("已同步"))"新增记录等待下一轮自动同步"else status
        return "$mode\n最近成功：$last · 待同步 $pendingRows 条\n$detail"
    }
    fun sync() {
        if(syncing||!LinkVpnService.active)return
        syncing=true
        syncWorker.execute {
            try {
                val p=ProfileStore(context).load() ?: return@execute
                val db=store.readableDatabase
                val prefs=context.getSharedPreferences("traffic-history",Context.MODE_PRIVATE)
                val peer=java.security.MessageDigest.getInstance("SHA-256").digest(p.ruleToken().toByteArray()).joinToString(""){"%02x".format(it)}
                if(prefs.getString("peer",null)!=peer){db.execSQL("UPDATE buckets SET dirty=1");prefs.edit().putString("peer",peer).apply()}
                // Old pending rows go first, so frequently changing new rows cannot starve them.
                // Bound each run; no wake lock or rapid retry while the computer is offline.
                val started=android.os.SystemClock.elapsedRealtime()
                for(attempt in 0 until 4){
                    if(!LinkVpnService.active||android.os.SystemClock.elapsedRealtime()-started>10000)break
                    val batch=rows(db,"dirty=1",emptyArray(),"LIMIT 100",oldestFirst=true)
                    pendingRows=countPending(db)
                    if(batch.length()==0)break
                    val req=Request.Builder().url("http://${p.computerIp}:10809/traffic/history").header("Authorization","Bearer ${p.ruleToken()}").post(batch.toString().toRequestBody("application/json".toMediaType())).build()
                    val call=ConnectionProbe.client.newCall(req);call.timeout().timeout(8,TimeUnit.SECONDS)
                    call.execute().use { response ->
                        if(response.code!=204)error("HTTP ${response.code}")
                        db.beginTransaction();try{for(i in 0 until batch.length()){val r=batch.getJSONObject(i);db.execSQL("UPDATE buckets SET dirty=0 WHERE id=? AND revision=?",arrayOf(r.getString("id"),r.getLong("revision")))};db.setTransactionSuccessful()}finally{db.endTransaction()}
                    }
                    lastSync=System.currentTimeMillis();syncFailures=0
                    prefs.edit().putLong("lastSync",lastSync).apply()
                }
                pendingRows=countPending(db)
                status=if(pendingRows==0)"已同步，无需手动操作"else"正在自动补传，手机记录已保留"
            }catch(_:Exception){syncFailures++;status="暂未同步成功，30秒后自动重试；手机记录已保留"}finally{syncing=false}
        }
    }
}
