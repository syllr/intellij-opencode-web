package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task.Backgroundable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.shenyuanlaolarou.opencodewebui.OPENCODE_HOST
import com.shenyuanlaolarou.opencodewebui.OPENCODE_PORT
import com.shenyuanlaolarou.opencodewebui.utils.OpenCodeApi
import com.shenyuanlaolarou.opencodewebui.utils.OpenCodeApiResult
import com.shenyuanlaolarou.opencodewebui.utils.SessionInfo
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingWorker
import javax.swing.border.EmptyBorder

/**
 * OpenCodeWeb 工具窗口的纯 Swing Dashboard。
 *
 * 设计:
 * - 打开工具窗口自动调 [OpenCodeServerManager.startServer] 启 server(若已在跑则复用,fast path)
 * - **Sessions 列表是主入口** — 每行点击 → Edge --app 模式打开对应 session URL
 * - **"+ New Session" 入口**(列表最上方固定一行):点击后 Edge 打开项目 URL(无 /session/<id> 段),
 *   opencode Web UI 落地后自己起新 session,IDE 端不再调 createSession
 * - 顶部状态区:Server 状态点(彩色 ●)+ "Server: running"/"Server: stopped" 文字
 * - 底部控制区:Stop / Restart
 * - Edge 未安装时点 session 行 → 弹 dialog 提示用户安装(不 throw,不 crash)
 *
 * Sessions 通过 [OpenCodeApi.getSessions] 异步拉取,SwingWorker 不阻塞 EDT。
 */
class MyToolWindow(
    @Suppress("unused") private val toolWindow: ToolWindow,
    private val project: Project,
) {
    private val log = thisLogger()

    private val statusDot = JBLabel("●")
    private val statusText = JBLabel("Server: stopped")
    private val sessionsTitleLabel = JBLabel("Recent Sessions")
    private val sessionsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    private val stopButton = JButton("Stop").apply { isEnabled = false }
    private val restartButton = JButton("Restart").apply { isEnabled = true }
    private val cleanNewSessionsButton = JButton("Clean Empty").apply { isEnabled = false }

    // CAS 守卫:防止快速连点 session 导致多次 launch(Backgroundable 在后台线程执行 1.5s sleep,
    // 期间用户可能再点 → 第二个 Backgroundable 也会 launch)
    private val isLaunchingEdge = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        MyToolWindowFactory.myToolWindowInstances[project] = this
        Disposer.register(project) {
            MyToolWindowFactory.myToolWindowInstances.remove(project)
            OpenCodeServerManager.disposeForProject(project)
        }
    }

    fun getContent(): JComponent = buildPanel()

    fun buildOpenUrl(sessionId: String = ""): String =
        OpenCodeBrowserLauncher.buildUrl(project.basePath ?: "", OPENCODE_PORT, sessionId)

    private fun buildPanel(): JPanel {
        val root = JPanel(BorderLayout(0, 8))
        root.border = EmptyBorder(12, 12, 12, 12)

        root.add(buildHeaderPanel(), BorderLayout.NORTH)
        root.add(buildSessionsScrollPane(), BorderLayout.CENTER)
        root.add(buildControlPanel(), BorderLayout.SOUTH)

        wireActions()
        refreshStatus()
        refreshSessions()
        wireSseStatusCallbacks()
        ensureServerRunning()
        return root
    }

    private fun ensureServerRunning() {
        OpenCodeServerManager.startServer(
            project = project,
            onStarted = {
                log.info("[Dashboard] ensureServerRunning: server up, refreshing UI")
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    refreshStatus()
                    refreshSessions()
                }
            },
            onFailed = { e ->
                log.warn("[Dashboard] ensureServerRunning: server start failed: ${e.message}")
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    applyStatus(ServerStatus.Stopped)
                }
            }
        )
    }

    private fun buildHeaderPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = EmptyBorder(0, 0, 12, 0)

        val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(statusDot)
            add(statusText)
        }
        panel.add(statusRow)

        return panel
    }

    private fun buildSessionsScrollPane(): JComponent {
        val outer = JPanel(BorderLayout(0, 4))
        sessionsTitleLabel.font = sessionsTitleLabel.font.deriveFont(Font.BOLD)
        outer.add(sessionsTitleLabel, BorderLayout.NORTH)
        outer.add(JBScrollPane(sessionsPanel).apply {
            preferredSize = Dimension(400, 240)
        }, BorderLayout.CENTER)
        return outer
    }

    private fun buildControlPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        panel.add(stopButton)
        panel.add(restartButton)
        panel.add(cleanNewSessionsButton)
        return panel
    }

    private fun wireActions() {
        stopButton.addActionListener {
            applyStatus(ServerStatus.Stopping)
            ProgressManager.getInstance().run(object : Backgroundable(project, "Stopping OpenCode Server", true) {
                override fun run(indicator: ProgressIndicator) {
                    runCatching { OpenCodeServerManager.shutdownServer() }
                        .onFailure { log.warn("[Dashboard] shutdownServer failed: ${it.message}") }
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        refreshStatus()
                    }
                }
            })
        }
        restartButton.addActionListener {
            applyStatus(ServerStatus.Starting)
            ProgressManager.getInstance().run(object : Backgroundable(project, "Starting OpenCode Server", true) {
                override fun run(indicator: ProgressIndicator) {
                    OpenCodeServerManager.startServer(
                        project = project,
                        onStarted = {
                            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                                applyStatus(ServerStatus.Running)
                                refreshSessions()
                                wireSseStatusCallbacks()
                            }
                        },
                        onFailed = { e ->
                            log.warn("[Dashboard] restart failed: ${e.message}")
                            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                                applyStatus(ServerStatus.Stopped)
                            }
                        }
                    )
                }
            })
        }
        cleanNewSessionsButton.addActionListener {
            val result = Messages.showDialog(
                project,
                "Delete all empty \"New session - ...\" sessions from OpenCode's database?\n" +
                    "This frees DB space and removes clutter; sessions with custom titles and any session " +
                    "with message content are kept.",
                "Clean Empty Sessions",
                arrayOf("Clean", "Cancel"),
                1, // default = Cancel
                null,
            )
            if (result != 0) return@addActionListener
            cleanNewSessionsButton.isEnabled = false
            ProgressManager.getInstance().run(object : Backgroundable(project, "Cleaning empty sessions", true) {
                override fun run(indicator: ProgressIndicator) {
                    val rawList = OpenCodeApi.getRawSessions(project.basePath)
                    // 协议约定:OpenCode 自动生成的"未命名"session 标题前缀 = "New session - ",
                    // 见 OpenCodeApi.kt:138 同步过滤。该字面值修改需同步。
                    val emptyIds = when (rawList) {
                        is OpenCodeApiResult.Success<List<SessionInfo>> -> rawList.data
                            .filter { it.title.orEmpty().startsWith("New session - ", ignoreCase = true) }
                            .map { it.id }
                            .filter { it.isNotBlank() }
                        else -> emptyList()
                    }
                    if (emptyIds.isEmpty()) {
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                            cleanNewSessionsButton.isEnabled = true
                            Messages.showInfoMessage(project, "No empty sessions to clean.", "Clean Empty Sessions")
                        }
                        return
                    }
                    var deleted = 0
                    var failed = 0
                    for (id in emptyIds) {
                        when (OpenCodeApi.deleteSession(id)) {
                            is OpenCodeApiResult.Success -> deleted++
                            else -> failed++
                        }
                    }
                    log.info("[Dashboard] cleanEmptySessions: deleted=$deleted failed=$failed (of ${emptyIds.size})")
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        cleanNewSessionsButton.isEnabled = true
                        refreshSessions()
                        Messages.showInfoMessage(
                            project,
                            "Cleaned $deleted session(s)" + if (failed > 0) ", $failed failed" else "",
                            "Clean Empty Sessions",
                        )
                    }
                }
            })
        }
    }

    private fun refreshStatus() {
        val portOpen = OpenCodeApi.isServerPortOpen()
        applyStatus(if (portOpen) ServerStatus.Running else ServerStatus.Stopped)
    }

    private fun applyStatus(status: ServerStatus) {
        when (status) {
            ServerStatus.Stopped -> {
                statusDot.foreground = JBColor.GRAY
                statusText.text = "Server: stopped"
                stopButton.isEnabled = false
                restartButton.isEnabled = true
                cleanNewSessionsButton.isEnabled = false
            }
            ServerStatus.Starting -> {
                statusDot.foreground = JBColor.YELLOW
                statusText.text = "Server: starting..."
                stopButton.isEnabled = false
                restartButton.isEnabled = false
                cleanNewSessionsButton.isEnabled = false
            }
            ServerStatus.Running -> {
                statusDot.foreground = JBColor.GREEN
                statusText.text = "Server: running"
                stopButton.isEnabled = true
                restartButton.isEnabled = true
                cleanNewSessionsButton.isEnabled = true
            }
            ServerStatus.Stopping -> {
                statusDot.foreground = JBColor.YELLOW
                statusText.text = "Server: stopping..."
                stopButton.isEnabled = false
                restartButton.isEnabled = false
                cleanNewSessionsButton.isEnabled = false
            }
        }
    }

    internal enum class ServerStatus { Stopped, Starting, Running, Stopping }

    private fun wireSseStatusCallbacks() {
        log.info("[Dashboard] wireSseStatusCallbacks: registering SSE callbacks for project=${project.name}, projectBasePath=${project.basePath}")
        OpenCodeServerManager.getOrCreateConsumer(
            project = project,
            onConnectionLost = {
                log.info("[Dashboard] SSE onConnectionLost callback fired (background thread=${Thread.currentThread().name})")
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    refreshStatus()
                }
            },
            onConnectionEstablished = {
                log.info("[Dashboard] SSE onConnectionEstablished callback fired (background thread=${Thread.currentThread().name})")
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    refreshStatus()
                }
            }
        )
    }

    private fun refreshSessions() {
        setSessionsState(SessionsState.Loading)
        val worker = object : SwingWorker<List<SessionInfo>, Void>() {
            override fun doInBackground(): List<SessionInfo> {
                val result = OpenCodeApi.getSessions(project.basePath)
                return when (result) {
                    is OpenCodeApiResult.Success<List<SessionInfo>> -> result.data
                    else -> emptyList()
                }
            }
            override fun done() {
                try {
                    val sessions = get().sortedByDescending { it.timeCreated ?: 0L }.take(20)
                    setSessionsState(SessionsState.Sessions(sessions))
                } catch (e: Exception) {
                    log.warn("[Dashboard] fetch sessions failed: ${e.message}")
                    setSessionsState(SessionsState.Error(e.message ?: "Unknown error"))
                }
            }
        }
        worker.execute()
    }

    internal fun setSessionsState(state: SessionsState) {
        sessionsPanel.removeAll()
        when (state) {
            is SessionsState.Loading -> {
                val label = JBLabel("Loading sessions…")
                label.foreground = JBColor.GRAY
                label.border = EmptyBorder(24, 8, 24, 8)
                sessionsPanel.add(label)
            }
            is SessionsState.Sessions -> {
                sessionsPanel.add(buildNewSessionRow())
                sessionsPanel.add(Box.createVerticalStrut(2))
                if (state.sessions.isEmpty()) {
                    val emptyLabel = JBLabel("No sessions yet")
                    emptyLabel.foreground = JBColor.GRAY
                    emptyLabel.border = EmptyBorder(24, 8, 24, 8)
                    sessionsPanel.add(emptyLabel)
                } else {
                    state.sessions.forEach { session ->
                        sessionsPanel.add(buildSessionRow(session))
                        sessionsPanel.add(Box.createVerticalStrut(2))
                    }
                }
            }
            is SessionsState.Error -> {
                val label = JBLabel("Failed to load: ${state.message}")
                label.foreground = JBColor.RED
                label.border = EmptyBorder(24, 8, 24, 8)
                sessionsPanel.add(label)
            }
        }
        sessionsPanel.revalidate()
        sessionsPanel.repaint()
    }

    /**
     * 特殊的 "+ New Session" 行:点击后调 [launchEdgeForSession] 传空 sessionId,
     * Edge 打开项目 URL(无 /session/<id> 段),opencode Web UI 自己起新 session。
     * 与 [buildSessionRow] 同形(同 BorderLayout + 下边框 + Hand cursor),
     * 仅文案和 click handler 不同,视觉与普通 session 行对齐。
     */
    internal fun buildNewSessionRow(): JPanel {
        val row = JPanel(BorderLayout())
        row.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            EmptyBorder(6, 8, 6, 8)
        )
        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        row.isOpaque = false

        val title = JBLabel("+ New Session")
        title.font = title.font.deriveFont(Font.BOLD)
        val hint = JBLabel("open project →")
        hint.foreground = JBColor.GRAY
        hint.font = hint.font.deriveFont(Font.PLAIN, 10f)

        val topRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(title, BorderLayout.WEST)
            add(hint, BorderLayout.EAST)
        }

        val col = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(topRow)
        }
        row.add(col, BorderLayout.CENTER)

        row.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                launchEdgeForSession("")
            }
        })
        return row
    }

    internal sealed class SessionsState {
        object Loading : SessionsState()
        data class Sessions(val sessions: List<SessionInfo>) : SessionsState()
        data class Error(val message: String) : SessionsState()
    }

    internal fun buildSessionRow(session: SessionInfo): JPanel {
        val row = JPanel(BorderLayout())
        row.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            EmptyBorder(6, 8, 6, 8)
        )
        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        row.isOpaque = false

        val title = JBLabel(session.title?.takeIf { it.isNotBlank() } ?: "(untitled)")
        title.font = title.font.deriveFont(Font.BOLD)
        val timeAgoLabel = JBLabel(timeAgo(session.timeCreated))
        timeAgoLabel.foreground = JBColor.GRAY
        timeAgoLabel.font = timeAgoLabel.font.deriveFont(Font.PLAIN, 10f)

        val topRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(title, BorderLayout.WEST)
            add(timeAgoLabel, BorderLayout.EAST)
        }

        val col = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(topRow)
        }
        row.add(col, BorderLayout.CENTER)

        row.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                launchEdgeForSession(session.id)
            }
        })
        return row
    }

    private fun launchEdgeForSession(sessionId: String) {
        val edgeMissing = OpenCodeBrowserLauncher.checkEdgeInstalled()
        if (edgeMissing != null) {
            log.warn("[Dashboard] Edge not installed, showing install dialog")
            Messages.showErrorDialog(project, edgeMissing, "Microsoft Edge Required")
            return
        }
        val basePath = project.basePath ?: ""

        // CAS 守卫必须放在 hasEdgeWindowOpen 之前 — 否则两次快速点击都会各自跑完 pgrep+ps 扫描
        // (每次 ~200ms),只靠 finally 释放锁时已经开了第二个窗口。CAS 提前 → 第二次点击直接走 false 分支
        if (!isLaunchingEdge.compareAndSet(false, true)) {
            log.info("[Dashboard] Edge launch already in progress, skipping duplicate click")
            return
        }

        // 复用检查:如果该项目 Edge --app 窗口已活跃(renderer > 0),弹通知告知用户,不重复创建
        // 重要:不弹 dialog(阻塞用户),用 BALLOON 轻量通知
        if (basePath.isNotEmpty() && OpenCodeBrowserLauncher.hasEdgeWindowOpen(basePath)) {
            log.info("[Dashboard] Edge window already active for project=$basePath, showing notification (skip launch)")
            try {
                val notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup("com.shenyuanlaolarou.opencodewebui.OpenCodeWeb")
                    .createNotification(
                        "OpenCode Window Already Open",
                        "The OpenCode Web UI window for this project is already running. " +
                                "Check your existing Edge window instead of opening a new one.",
                        NotificationType.INFORMATION
                    )
                Notifications.Bus.notify(notification, project)
            } finally {
                // 命中活跃窗口时也要释放锁(后续 launch 流程不再跑)
                isLaunchingEdge.set(false)
            }
            return
        }

        val url = buildOpenUrl(sessionId)
        val extDir = runCatching { EdgeBootstrapExtension.prepare(basePath) }
            .onFailure { log.warn("[Dashboard] ext prepare failed: ${it.message}") }
            .getOrNull()

        val projectHash = EdgeBootstrapExtension.hash(basePath)
        val profilesRoot = File(System.getProperty("user.home"), ".config/opencode-web-ui/edge-profiles")
        val userDataDir = File(profilesRoot, "$projectHash/user-data")
        runCatching { userDataDir.mkdirs() }
            .onFailure { log.warn("[Dashboard] mkdirs failed for $userDataDir: ${it.message}") }

        // 包 Backgroundable 让用户看到 "Opening session..." 进度(和 stopButton / restartButton 同样的模式)
        // 固定 sleep 1.5s 让进度条明显停留 — launch 本身 ~100ms,如果不主动停留用户感知不到任何反馈
        ProgressManager.getInstance().run(object : Backgroundable(project, "Opening session in Edge", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Launching Microsoft Edge..."
                try {
                    runCatching { OpenCodeBrowserLauncher.launch(url, extDir, userDataDir) }
                        .onFailure {
                            log.warn("[Dashboard] Edge launch failed: ${it.message}")
                        }
                    // 1.5s 进度条停留 — 拆成 100ms 切片,期间检查 isCanceled 提前释放 CAS 锁
                    var remaining = 1500L
                    while (remaining > 0) {
                        if (indicator.isCanceled) {
                            log.info("[Dashboard] Edge launch cancelled by user")
                            return
                        }
                        val slice = minOf(100L, remaining)
                        try {
                            Thread.sleep(slice)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return
                        }
                        remaining -= slice
                    }
                } finally {
                    // 释放 CAS 锁,允许下次 launch
                    isLaunchingEdge.set(false)
                }
            }
        })
    }

    private fun timeAgo(epochMillis: Long?): String {
        if (epochMillis == null) return ""
        val diffSec = (System.currentTimeMillis() - epochMillis) / 1000
        return when {
            diffSec < 60 -> "just now"
            diffSec < 3600 -> "${diffSec / 60}m ago"
            diffSec < 86400 -> "${diffSec / 3600}h ago"
            diffSec < 7 * 86400 -> "${diffSec / 86400}d ago"
            else -> Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        }
    }
}