# 通知弹窗拦截（NotifPromptBlocker）v2

LSPosed 模块（libxposed API 102），包名 com.touka.notifblock。三层拦截：

1. 系统级：作用域勾「权限控制器」后，把通知权限从系统授权弹窗里摘除，只剩通知权限时整个弹窗直接回「拒绝」；
2. 应用级：把某个 App 勾进作用域后，让它以为通知已开启，不再弹应用内「去开启通知」催促框；
3. Root 锁定：App 内点「锁定」，用 pm set-permission-flags 给未授权 App 批量打 user-fixed（拒绝且不再询问），不依赖 Hook。

注意：默认 scope.list 只含 AOSP/Google 的权限控制器；ColorOS 等系统需在 LSPosed 里手动把本系统的权限控制器包（如 com.oplus.securitypermission）加进作用域，第 1 层才生效。

构建：推送到 GitHub 后由 Actions 产出 debug APK；签名密钥已固定，后续版本可覆盖安装。
