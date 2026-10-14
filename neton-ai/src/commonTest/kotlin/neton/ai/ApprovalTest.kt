package neton.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import neton.ai.protocol.ir.ToolCallBlock
import neton.ai.testing.AiScript
import neton.ai.testing.ScriptedAiProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApprovalTest {
    private var executed = mutableListOf<String>()

    private fun tool(name: String, needsApproval: Boolean = false) = AiTool(
        name, name,
        needsApproval = if (needsApproval) AiNeedsApproval { _, _ -> true } else null,
        execute = AiToolExecutor { input, _ -> executed += name; AiToolOutput.Text("did $name $input") },
    )

    @Test
    fun userApprovalPausesAndResumesAcrossCalls() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(
            AiScript.toolCalls(ToolCallBlock("r1", "read", "{}"), ToolCallBlock("p1", "pay", """{"amount":5}""")),
            AiScript.text("paid"),
        ))
        val ai = p.client()
        val tools = listOf(tool("read"), tool("pay", needsApproval = true))
        val history = mutableListOf(AiMessage.user("pay 5"))
        val first = ai.generate(AiRequest(messages = history, tools = tools))

        // 不需要审批的调用照常执行；需要审批的挂起，循环停下
        assertEquals(listOf("read"), executed)
        val pending = first.pendingApprovals.single()
        assertEquals("p1", pending.toolCallId)
        assertEquals(1, first.steps.size)
        assertEquals(1, p.requests.size)

        history += first.responseMessages
        history += AiMessage.approvals(AiContent.ToolApprovalResponse(pending.approvalId, approved = true))
        val second = ai.generate(AiRequest(messages = history, tools = tools))
        assertEquals(listOf("read", "pay"), executed)
        assertEquals("paid", second.text)
        // 调用开始时先执行被批准的调用，结果在第一步之前进入历史
        assertEquals(AiRole.Tool, second.responseMessages.first().role)
        assertEquals("p1", second.responseMessages.first().toolResults.single().toolCallId)
    }

    @Test
    fun deniedApprovalTellsTheModelAndPolicyDenialOverridesUserApproval() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("p1", "pay"), AiScript.text("ok, not paying"), AiScript.text("x")))
        val ai = p.client()
        val tools = listOf(tool("pay", needsApproval = true))
        val history = mutableListOf(AiMessage.user("pay"))
        val first = ai.generate(AiRequest(messages = history, tools = tools))
        history += first.responseMessages
        history += AiMessage.approvals(AiContent.ToolApprovalResponse(first.pendingApprovals.single().approvalId, false, "user said no"))
        val second = ai.generate(AiRequest(messages = history, tools = tools))
        val denied = second.responseMessages.first().toolResults.single().output
        assertEquals(AiToolOutput.Denied("user said no"), denied)
        assertTrue(executed.isEmpty())

        // 批准后重新执行审批策略：策略拒绝优先
        val p2 = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("p2", "pay"), AiScript.text("blocked")))
        val h2 = mutableListOf(AiMessage.user("pay"))
        val r1 = p2.client().generate(AiRequest(messages = h2, tools = tools))
        h2 += r1.responseMessages
        h2 += AiMessage.approvals(AiContent.ToolApprovalResponse(r1.pendingApprovals.single().approvalId, true))
        val r2 = p2.client().generate(AiRequest(messages = h2, tools = tools,
            toolApproval = AiToolApproval { _, _, _ -> AiApproval.Denied("over budget") }))
        assertEquals(AiToolOutput.Denied("over budget"), r2.responseMessages.first().toolResults.single().output)
        assertTrue(executed.isEmpty())
    }

    @Test
    fun automaticDecisionsAreRecordedAsPairs() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCalls(ToolCallBlock("a", "safe", "{}"), ToolCallBlock("b", "danger", "{}")), AiScript.text("done")))
        val r = p.client().generate {
            user("go"); tools(tool("safe"), tool("danger"))
            toolApproval = AiToolApproval.perTool(mapOf("safe" to AiApproval.Approved("whitelisted"), "danger" to AiApproval.Denied("blocked")))
        }
        val step = r.steps.first()
        val requests = step.content.filterIsInstance<AiContent.ToolApprovalRequest>()
        val responses = step.content.filterIsInstance<AiContent.ToolApprovalResponse>()
        assertTrue(requests.all { it.isAutomatic })
        assertEquals(listOf(true, false), responses.map { it.approved })
        assertEquals(listOf("safe"), executed)
        assertEquals(AiToolOutput.Denied("blocked"), step.toolResults.first { it.toolName == "danger" }.output)
        assertTrue(r.pendingApprovals.isEmpty())
        assertEquals("done", r.text)
    }

    @Test
    fun signedApprovalsRejectTampering() = runTest {
        val secret = "s3cret"
        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("p1", "pay", """{"amount":5}"""), AiScript.text("ok")))
        val tools = listOf(tool("pay", needsApproval = true))
        val h = mutableListOf(AiMessage.user("pay"))
        val r1 = p.client().generate(AiRequest(messages = h, tools = tools, toolApprovalSecret = secret))
        val req = r1.pendingApprovals.single()
        assertTrue(req.signature != null)

        // 篡改参数：签名不再匹配
        val tampered = r1.responseMessages.map { m ->
            m.copy(content = m.content.map { c ->
                if (c is AiContent.ToolCall) c.copy(call = c.call.copy(argumentsJson = """{"amount":5000}""")) else c
            })
        }
        val e = assertFailsWith<AiException> {
            p.client().generate(AiRequest(messages = h + tampered + AiMessage.approvals(AiContent.ToolApprovalResponse(req.approvalId, true)),
                tools = tools, toolApprovalSecret = secret))
        }
        assertIs<AiError.InvalidRequest>(e.error)
        assertTrue(executed.isEmpty())

        // 原样答复：通过
        val ok = p.client().generate(AiRequest(messages = h + r1.responseMessages + AiMessage.approvals(AiContent.ToolApprovalResponse(req.approvalId, true)),
            tools = tools, toolApprovalSecret = secret))
        assertEquals(listOf("pay"), executed)
        assertEquals("ok", ok.text)
    }

    @Test
    fun unknownApprovalIdIsRejected() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.text("x")))
        val e = assertFailsWith<AiException> {
            p.client().generate(AiRequest(messages = listOf(AiMessage.user("hi"), AiMessage.approvals(AiContent.ToolApprovalResponse("nope", true)))))
        }
        assertIs<AiError.InvalidRequest>(e.error)
    }

    @Test
    fun netonIdentityGatesTools() = runTest {
        val admin = object : neton.core.interfaces.Identity {
            override val id = "a1"; override val roles = setOf("admin"); override val permissions = setOf("user:delete")
        }
        val guest = object : neton.core.interfaces.Identity {
            override val id = "g1"; override val roles = emptySet<String>(); override val permissions = emptySet<String>()
        }
        suspend fun runAs(identity: neton.core.interfaces.Identity): AiToolOutput {
            val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("1", "delete_user"), AiScript.text("done")))
            return p.client().generate {
                user("go"); tool(tool("delete_user"))
                principal = AiPrincipal.of(identity)
                authorizer = AiToolAuthorizers.requirePermissions(mapOf("delete_user" to "user:delete"))
            }.toolResults.single().output
        }
        assertFalse(runAs(admin).isError)
        assertIs<AiToolOutput.Denied>(runAs(guest))
    }

    @Test
    fun handlerSeesContext() = runTest {
        val p = ScriptedAiProvider(scripts = listOf(AiScript.toolCall("1", "whoami", """{"x":1}"""), AiScript.text("done")))
        val whoami = AiTool("whoami", execute = AiToolExecutor { input, ctx ->
            AiToolOutput.Text("${ctx.principal?.id}@${ctx.requestMetadata["tenant"]}#${ctx.stepNumber}:${(input.jsonObject["x"] as JsonPrimitive).content}")
        })
        val r = p.client().generate { user("go"); tool(whoami); metadata = mapOf("tenant" to "t9"); principal = AiPrincipal("u7") }
        assertEquals("u7@t9#1:1", r.toolResults.single().output.asText())
    }
}
