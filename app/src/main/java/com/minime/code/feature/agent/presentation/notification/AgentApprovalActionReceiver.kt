package com.minime.code.feature.agent.presentation.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.minime.code.feature.agent.domain.permission.PermissionChoice
import com.minime.code.feature.agent.domain.tool.ToolPermissionManager
import com.minime.code.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.minime.code.feature.agent.domain.tool.mode.PlanApprovalManager
import com.minime.code.feature.agent.domain.workflow.CloudAgentWorkflow
import com.minime.code.feature.agent.presentation.notification.AgentApprovalNotificationManager.ApprovalKind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 审批通知上按钮的接收器：把用户在通知上的选择直接回传给挂起的审批请求，无需回到 App。
 *
 * 按通知绑定的请求 id 回传，过期请求不会影响其它待决审批。
 */
@AndroidEntryPoint
class AgentApprovalActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var toolPermissionManager: ToolPermissionManager

    @Inject
    lateinit var planApprovalManager: PlanApprovalManager

    @Inject
    lateinit var cloudAgentWorkflow: CloudAgentWorkflow

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val kind = intent.getStringExtra(EXTRA_KIND)?.let { value -> ApprovalKind.entries.find { it.name == value } } ?: return
        val choice = intent.getStringExtra(EXTRA_CHOICE) ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        when (kind) {
            ApprovalKind.CLOUD -> {
                // 云端决策是网络请求（POST /decisions），在 IO 协程里执行；requestId 即 decisionId。
                // 回传后云端续跑，App 下次回前台由 CloudAgentCatchUp 补拉结果（无需在此持有 ViewModel）。
                val approve = choice == "approve"
                if (choice != "approve" && choice != "reject") return
                // sessionId 随通知 extras 带来，用于定位该审批属于哪台云服务器。
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        cloudAgentWorkflow.decide(sessionId = sessionId, decisionId = requestId, approve = approve)
                    } catch (e: Exception) {
                        // 网络失败时用户仍可在 App 内重试；通知快捷操作不阻塞崩溃。
                    } finally {
                        pendingResult.finish()
                    }
                }
                return
            }
            ApprovalKind.PERMISSION -> {
                val permissionChoice = PermissionChoice.entries.find { it.name == choice } ?: return
                if (permissionChoice != PermissionChoice.ONCE && permissionChoice != PermissionChoice.REJECT) return
                toolPermissionManager.resolve(requestId, permissionChoice)
            }
            ApprovalKind.PLAN -> {
                val planChoice = PlanApprovalChoice.entries.find { it.name == choice } ?: return
                planApprovalManager.resolve(requestId, planChoice)
            }
            ApprovalKind.QUESTION -> Unit
        }
    }

    companion object {
        const val ACTION = "com.minime.code.action.AGENT_APPROVAL"
        const val EXTRA_REQUEST_ID = "extra_request_id"
        const val EXTRA_KIND = "extra_kind"
        const val EXTRA_CHOICE = "extra_choice"
        const val EXTRA_SESSION_ID = "extra_session_id"
    }
}
