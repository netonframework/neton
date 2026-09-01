package neton.http.ktor

/**
 * 安装端口占用时的全局异常钩子，避免打印几十行堆栈。
 * Native 下 EADDRINUSE 在 Ktor 内部协程抛出，try-catch 无法捕获，需用 setUnhandledExceptionHook。
 */
internal expect fun installPortInUseHandler(port: Int)

/**
 * 「端口被占」这件事**只报一次**。
 *
 * Native 下这条消息有两个出口：协程里抛出的 EADDRINUSE 走 unhandled hook，
 * 同步路径上的走 [KtorHttpAdapter] 最外层 catch。两个出口各打各的，于是一次失败
 * 刷出两行一模一样的错误——真实后果不是难看：我们照着「同一个 PID 打了两遍」
 * 推出「进程自己绑了两次 8080」的根因写进了部署文档，之后每次生产重启都按那个
 * 错误结论在赌运气。所以这里用一个标志把出口收敛成一次。
 *
 * 竞态是良性的：最坏情况退回到今天的行为（多打一行），不会漏报。
 */
private var portInUseReported = false

internal fun reportPortInUseOnce(port: Int) {
    if (portInUseReported) return
    portInUseReported = true
    kotlin.io.println(portInUseHint(port))
}

/**
 * 报错要能直接接下一步动作：说清楚是谁占着、怎么查。
 *
 * 生产上这条最常见的成因不是「配错端口」，而是**上一个进程还没退干净**——
 * systemd stop 之后立刻 start 就会撞上。所以提示里给的是排查占用者的命令，
 * 而不是笼统的「换个端口」。
 */
internal fun portInUseHint(port: Int): String =
    "Port $port is already in use — the previous process may still be shutting down.\n" +
        "  Who holds it:  ss -ltnp | grep :$port    (macOS: lsof -nP -iTCP:$port -sTCP:LISTEN)\n" +
        "  If it is the old instance: wait for it to exit, or stop it, then start again."
