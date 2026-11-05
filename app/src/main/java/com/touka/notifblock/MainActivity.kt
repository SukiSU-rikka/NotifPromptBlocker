package com.touka.notifblock

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Root 部分：给通知权限打上 user-fixed（「拒绝且不再询问」）标记。
 * 打上之后系统根本不会再弹授权框，App 一申请就直接收到「拒绝」——不依赖 Hook，最稳。
 */
class MainActivity : Activity() {

    private val POST = HookEntry.POST
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }
        fun btn(text: String, mode: Mode) = Button(this).apply {
            this.text = text
            setOnClickListener { run(mode) }
            root.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        root.addView(TextView(this).apply {
            text = "通知弹窗拦截 v2\n\n" +
                "1. 先点「锁定」（需要 Root）\n" +
                "2. LSPosed 里启用模块，作用域勾「权限控制器」\n" +
                "3. 还会自己弹「去开启通知」的 App，再把它也勾进作用域\n" +
                "新装 App 后再点一次「锁定」。\n"
            textSize = 15f
        })
        btn("锁定：所有未授权的 App 永久拒绝通知权限", Mode.LOCK)
        btn("全部关掉：已授权的第三方 App 也一起拒绝", Mode.LOCK_ALL)
        btn("恢复：清除锁定标记", Mode.UNLOCK)
        log = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(log)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    enum class Mode { LOCK, LOCK_ALL, UNLOCK }

    private fun run(mode: Mode) {
        log.text = "处理中…"
        thread {
            val pkgs = packageManager.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            val sb = StringBuilder()
            var n = 0
            for (p in pkgs) {
                if (p.packageName == packageName) continue
                val req = p.requestedPermissions ?: continue
                val idx = req.indexOf(POST)
                if (idx < 0) continue
                val granted = (p.requestedPermissionsFlags!![idx] and
                    PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                val isSystem = (p.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val pk = p.packageName
                when (mode) {
                    Mode.LOCK -> if (!granted) {
                        sb.append("pm set-permission-flags $pk $POST user-fixed\n"); n++
                    }
                    Mode.LOCK_ALL -> if (!granted || !isSystem) {
                        // 系统 App 已授权的不动，避免电话/短信/闹钟收不到通知
                        sb.append("pm revoke $pk $POST 2>/dev/null; pm set-permission-flags $pk $POST user-fixed\n"); n++
                    }
                    Mode.UNLOCK -> {
                        sb.append("pm clear-permission-flags $pk $POST user-fixed user-set 2>/dev/null\n"); n++
                    }
                }
            }
            val (code, out) = su(sb.toString())
            runOnUiThread {
                log.text = if (code == 0) "完成，处理了 $n 个 App。\n$out"
                           else "失败（code=$code），确认已在 SukiSU 里给本 App 授予 Root。\n$out"
            }
        }
    }

    private fun su(script: String): Pair<Int, String> = try {
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        p.outputStream.bufferedWriter().use { it.write(script); it.write("exit 0\n") }
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor() to out.lines().filter { it.isNotBlank() }.take(50).joinToString("\n")
    } catch (e: Exception) {
        -1 to e.toString()
    }
}
