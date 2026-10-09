package cn.wangchuan.link

import android.app.Activity
import android.os.Bundle
import android.widget.*
import org.json.JSONObject

class RuleSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profile = runCatching { ProfileStore(this).load() }.getOrNull()
        if (profile == null) { Toast.makeText(this, "请先配对电脑", Toast.LENGTH_LONG).show(); finish(); return }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 36, 28, 36) }
        fun text(value: String) { form.addView(TextView(this).apply { text = value; textSize = 16f; setPadding(0, 14, 0, 8) }) }
        fun input(title: String, values: List<String>): EditText {
            text(title)
            return EditText(this).apply { setText(values.joinToString("\n")); minLines = 2; maxLines = 6; form.addView(this) }
        }
        text("手机分流规则")
        text("自定义域名优先于规则集。每行一个域名，不含 https:// 或路径。保存后断开并重新连接生效。")
        val direct = input("始终使用手机网络", profile.directDomains)
        val computer = input("始终通过电脑", profile.computerDomains)
        val apps = input("完全绕过分流的应用包名（可选）", profile.directApps)
        form.addView(Button(this).apply {
            text = "从已安装应用中选择"
            setOnClickListener {
                DirectAppPicker.show(this@RuleSettingsActivity,
                    apps.text.toString().split(Regex("[\\s,，]+")).filter { it.isNotBlank() }) {
                    apps.setText(it.joinToString("\n"))
                }
            }
        })
        text("所选应用完全使用手机原网络，包括 DNS 和 IPv6。应用选择优先于下面的域名规则；未选的 X 等仍按分流规则运行。")
        text("规则集分组：DIRECT 使用手机，COMPUTER 使用电脑，REJECT 拦截。这里的默认选择针对手机使用，并非黑盒子当前选择的自动镜像。")
        val manifest = JSONObject(assets.open("network-rules/manifest.json").bufferedReader().use { it.readText() })
        val providers = manifest.getJSONArray("providers")
        val controls = linkedMapOf<String, Spinner>()
        val targets = listOf("DIRECT", "COMPUTER", "REJECT")
        for (i in 0 until providers.length()) {
            val item = providers.getJSONObject(i)
            val group = item.getString("group")
            if (controls.containsKey(group)) continue
            text(group)
            controls[group] = Spinner(this).apply {
                adapter = ArrayAdapter(this@RuleSettingsActivity, android.R.layout.simple_spinner_dropdown_item, targets)
                setSelection(targets.indexOf(profile.groupTarget(group, item.getString("target"))).coerceAtLeast(0))
                form.addView(this)
            }
        }
        form.addView(Button(this).apply {
            text = "保存规则"
            setOnClickListener {
                fun lines(field: EditText) = field.text.toString().split(Regex("[\\s,，]+" )).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                try {
                    val directList = lines(direct)
                    val computerList = lines(computer)
                    require(directList.intersect(computerList.toSet()).isEmpty()) { "同一域名不能同时选择手机和电脑" }
                    val groups = JSONObject()
                    controls.forEach { (group, control) -> groups.put(group, control.selectedItem.toString()) }
                    ProfileStore(this@RuleSettingsActivity).save(profile.edited(directList, computerList, lines(apps), groups))
                    Toast.makeText(this@RuleSettingsActivity, "已保存；重新连接后生效", Toast.LENGTH_LONG).show()
                    finish()
                } catch (_: Exception) { Toast.makeText(this@RuleSettingsActivity, "规则无效，请检查域名、重复选择和应用包名", Toast.LENGTH_LONG).show() }
            }
        })
        setContentView(ScrollView(this).apply { addView(form) })
    }
}
