package cn.wangchuan.link

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.widget.*

object DirectAppPicker {
    fun show(activity: Activity, initial: List<String>, saved: (List<String>) -> Unit) {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = activity.packageManager.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != activity.packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedWith(compareBy({ it.activityInfo.packageName !in initial },
                { it.loadLabel(activity.packageManager).toString() }))
        if (apps.isEmpty()) {
            Toast.makeText(activity, "暂时无法读取应用列表，请稍后重试", Toast.LENGTH_LONG).show()
            return
        }
        // Preserve manually configured non-launchable/missing apps until the
        // user deliberately removes them in the advanced package-name field.
        val selected = initial.toMutableSet()
        val entries = apps.map { it.activityInfo.packageName to it.loadLabel(activity.packageManager).toString() }
        var visible = entries
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
        }
        val search = EditText(activity).apply {
            hint = "搜索应用名称或包名"; isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val count = TextView(activity).apply { textSize = 13f; setPadding(0, dp(8), 0, dp(8)) }
        val list = ListView(activity).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE }
        fun refresh() {
            val query = search.text.toString().trim()
            visible = entries.filter { (pkg, label) -> pkg.contains(query, true) || label.contains(query, true) }
            list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_multiple_choice,
                visible.map { (pkg, label) -> "$label\n$pkg" })
            list.clearChoices()
            visible.forEachIndexed { index, (pkg, _) -> list.setItemChecked(index, pkg in selected) }
            count.text = "已选择 ${selected.size} 个 · 搜索结果 ${visible.size} 个"
        }
        list.setOnItemClickListener { _, _, index, _ ->
            val name = visible[index].first
            if (list.isItemChecked(index)) selected.add(name) else selected.remove(name)
            count.text = "已选择 ${selected.size} 个 · 搜索结果 ${visible.size} 个"
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        form.addView(search); form.addView(count)
        form.addView(list, LinearLayout.LayoutParams(-1, dp(320)))
        // Keep the Save button above the keyboard on small/large-font displays.
        form.viewTreeObserver.addOnGlobalLayoutListener {
            val visibleFrame = android.graphics.Rect()
            form.getWindowVisibleDisplayFrame(visibleFrame)
            val height = (visibleFrame.height() - dp(400)).coerceIn(dp(100), dp(320))
            if (list.layoutParams.height != height) {
                list.layoutParams = list.layoutParams.apply { this.height = height }
            }
        }
        form.addView(TextView(activity).apply {
            text = "所选应用不进入随连，直接使用手机网络。系统仍会显示 VPN 标识；检测整机 VPN 状态的应用可能仍限制使用。保存后重新连接生效。"
            textSize = 12f; setPadding(0, dp(8), 0, dp(8))
        })
        refresh()
        AlertDialog.Builder(activity).setTitle("完全绕过随连").setView(form)
            .setPositiveButton("保存选择") { _, _ -> saved(selected.sorted()) }
            .setNegativeButton("取消", null).show()
    }
}
