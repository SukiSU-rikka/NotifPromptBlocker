package com.touka.notifblock

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.util.WeakHashMap

/**
 * 两层拦截：
 *  ① 作用域里勾「权限控制器」→ 系统级：所有 App 弹出的系统通知授权框都被拦掉，直接回「拒绝」
 *  ② 作用域里勾某个具体 App → 应用级：让该 App 以为通知已开启，不再弹它自己画的「去开启通知」框
 */
class HookEntry : XposedModule() {

    companion object {
        const val TAG = "NotifBlock"
        const val POST = "android.permission.POST_NOTIFICATIONS"
        const val ACTION_REQ = "android.content.pm.action.REQUEST_PERMISSIONS"
        const val ACTION_FOR_OTHER = "android.content.pm.action.REQUEST_PERMISSIONS_FOR_OTHER"
        const val EXTRA_NAMES = "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES"
        const val EXTRA_RESULTS = "android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS"
    }

    private var hooked = false
    /** 被拦截过的授权 Activity → 它原本申请的权限列表 */
    private val blocked = WeakHashMap<Activity, Array<String>>()

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage || hooked) return
        hooked = true
        val pkg = param.packageName
        val isController = pkg.contains("permissioncontroller") || pkg.contains("securitypermission")
        try {
            if (isController) hookSystemDialog() else hookAppSide()
            log(Log.INFO, TAG, "hooked $pkg (controller=$isController)")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook failed in $pkg", t)
        }
    }

    // ───────────── ① 系统授权弹窗（权限控制器进程） ─────────────

    private fun hookSystemDialog() {
        val create2 = Instrumentation::class.java.getDeclaredMethod(
            "callActivityOnCreate", Activity::class.java, Bundle::class.java)
        val create3 = Instrumentation::class.java.getDeclaredMethod(
            "callActivityOnCreate", Activity::class.java, Bundle::class.java, PersistableBundle::class.java)

        for (m in listOf(create2, create3)) {
            hook(m).intercept { chain ->
                val act = chain.args[0] as? Activity
                val all = act != null && strip(act)
                val r = chain.proceed()
                if (all && act != null && !act.isFinishing) {
                    act.setResult(Activity.RESULT_OK, null)   // 会被下面的 setResult hook 改成「拒绝」
                    act.finish()
                    @Suppress("DEPRECATION") act.overridePendingTransition(0, 0)
                }
                r
            }
        }

        // 不管授权页自己回什么结果，都把通知权限那一项改成「拒绝」，并且数组和 App 申请时对齐
        val setResult = Activity::class.java.getDeclaredMethod(
            "setResult", Int::class.javaPrimitiveType, Intent::class.java)
        hook(setResult).intercept { chain ->
            val act = chain.thisObject as Activity
            val orig = synchronized(blocked) { blocked[act] } ?: return@intercept chain.proceed()
            val data = chain.args[1] as? Intent
            val gotNames = data?.getStringArrayExtra(EXTRA_NAMES) ?: emptyArray()
            val gotRes = data?.getIntArrayExtra(EXTRA_RESULTS) ?: IntArray(0)
            val res = IntArray(orig.size) { i ->
                val idx = gotNames.indexOf(orig[i])
                if (orig[i] == POST || idx < 0 || idx >= gotRes.size) PackageManager.PERMISSION_DENIED
                else gotRes[idx]
            }
            val out = Intent(data ?: Intent())
                .putExtra(EXTRA_NAMES, orig)
                .putExtra(EXTRA_RESULTS, res)
            chain.proceed(arrayOf<Any?>(Activity.RESULT_OK, out))
        }
    }

    /** 从授权请求里删掉通知权限。返回 true = 删完后什么都不剩，整个弹窗应该直接关掉 */
    private fun strip(act: Activity): Boolean {
        val intent = act.intent ?: return false

        // Android 13+：老 App 创建通知渠道时，系统自己替它弹的那个框
        if (intent.action == ACTION_FOR_OTHER) {
            synchronized(blocked) { blocked[act] = arrayOf(POST) }
            act.intent = Intent(ACTION_REQ).putExtra(EXTRA_NAMES, emptyArray<String>())
            log(Log.INFO, TAG, "blocked system-triggered prompt for " +
                intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME))
            return true
        }

        val names = intent.getStringArrayExtra(EXTRA_NAMES) ?: return false
        if (POST !in names) return false
        val kept = names.filter { it != POST }.toTypedArray()
        synchronized(blocked) { blocked[act] = names }
        intent.putExtra(EXTRA_NAMES, kept)
        log(Log.INFO, TAG, "stripped POST_NOTIFICATIONS, remaining=${kept.size}")
        return kept.isEmpty()
    }

    // ───────────── ② 应用内自己画的「开启通知」催促框 ─────────────

    private fun hookAppSide() {
        val myUid = Process.myUid()

        // NotificationManagerCompat.areNotificationsEnabled() 最终也走这里
        hook(NotificationManager::class.java.getDeclaredMethod("areNotificationsEnabled"))
            .intercept { true }

        // ContextCompat.checkSelfPermission / Context.checkSelfPermission 都会落到这里
        val ctxImpl = Class.forName("android.app.ContextImpl")
        hook(ctxImpl.getDeclaredMethod("checkPermission",
            String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
            .intercept { chain ->
                if (chain.args[0] == POST && chain.args[2] == myUid) PackageManager.PERMISSION_GRANTED
                else chain.proceed()
            }

        hook(Activity::class.java.getDeclaredMethod("shouldShowRequestPermissionRationale", String::class.java))
            .intercept { chain -> if (chain.args[0] == POST) false else chain.proceed() }

        // 万一 App 还是去申请：把通知权限从申请里拿掉，只申请通知的话直接回「已授予」
        hook(Activity::class.java.getDeclaredMethod(
            "requestPermissions", Array<String>::class.java, Int::class.javaPrimitiveType))
            .intercept { chain ->
                val names = (chain.args[0] as? Array<*>)?.map { it as String }
                    ?: return@intercept chain.proceed()
                if (POST !in names) return@intercept chain.proceed()
                val kept = names.filter { it != POST }
                if (kept.isNotEmpty()) {
                    chain.proceed(arrayOf<Any?>(kept.toTypedArray(), chain.args[1]))
                } else {
                    val act = chain.thisObject as Activity
                    val code = chain.args[1] as Int
                    val arr = names.toTypedArray()
                    Handler(Looper.getMainLooper()).post {
                        act.onRequestPermissionsResult(code, arr,
                            IntArray(arr.size) { PackageManager.PERMISSION_GRANTED })
                    }
                    null
                }
            }
    }
}
