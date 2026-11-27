package com.ax6.luyouqi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ax6.luyouqi.ui.HomeScreen
import com.ax6.luyouqi.ui.LoginScreen
import com.ax6.luyouqi.ui.theme.Ax6Theme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ExitGate.attach(this)
        DiagLog.log("activity.create")
        setContent {
            val dark by viewModel.dark.collectAsStateWithLifecycle()
            val manualExit by viewModel.manualExit.collectAsStateWithLifecycle()
            val probe by viewModel.probe.collectAsStateWithLifecycle()
            Ax6Theme(darkTheme = dark) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                when (val current = state) {
                    is AppUiState.Login -> LoginScreen(
                        host = current.host,
                        port = current.port,
                        username = current.username,
                        password = current.password,
                        secure = current.secure,
                        busy = current.busy,
                        error = current.error,
                        onHostChange = viewModel::onHostChange,
                        onPortChange = viewModel::onPortChange,
                        onUsernameChange = viewModel::onUsernameChange,
                        onPasswordChange = viewModel::onPasswordChange,
                        onSecureChange = viewModel::onSecureChange,
                        onConnect = viewModel::connect,
                    )

                    is AppUiState.Connected -> HomeScreen(
                        state = current,
                        onRefresh = viewModel::refresh,
                        onRange = viewModel::selectRange,
                        onTab = viewModel::selectTab,
                        onDisconnect = viewModel::disconnect,
                        dark = dark,
                        onToggleTheme = viewModel::toggleTheme,
                        onScan = viewModel::scanWifi,
                        onReboot = viewModel::rebootRouter,
                        manualExit = manualExit,
                        onManualExitChange = viewModel::setManualExit,
                        onBackToDesktop = { moveTaskToBack(true) },
                        onQuit = { ExitGate.closeNow(this) },
                        probe = probe,
                        onProbe = viewModel::runProbe,
                        readLog = viewModel::logReport,
                        onRecheckVantage = viewModel::recheckVantage,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DiagLog.log("activity.start")
    }

    override fun onResume() {
        super.onResume()
        DiagLog.log("activity.resume")
        viewModel.setForeground(true)
        ExitGate.onForeground(this)
    }

    /**
     * 在 onPause 起前台服务：此时进程还带有可见窗口，属于允许启动 FGS 的状态。
     * 确认退出（isFinishing）时不再挂计时，否则会在关闭流程里多起一个服务。
     */
    override fun onPause() {
        if (!isFinishing && !isChangingConfigurations) {
            DiagLog.log("activity.pause manual=${viewModel.manualExit.value}")
            viewModel.setForeground(false)
            ExitGate.onBackground(this, viewModel.manualExit.value)
        }
        super.onPause()
    }

    override fun onStop() {
        DiagLog.log("activity.stop finishing=$isFinishing")
        super.onStop()
    }

    /** 退出前停掉轮询，避免关闭瞬间再打一次 ubus。 */
    fun stopForExit() {
        viewModel.stopPolling()
    }
}
