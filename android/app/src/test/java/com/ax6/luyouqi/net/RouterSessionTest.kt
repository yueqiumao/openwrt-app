package com.ax6.luyouqi.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 双通道 RPC 的协议契约测试：这里锁死的两条规则一旦违反，路由器会直接报
 * -32700 Parse error 或 403，所以必须由测试守住。
 */
class RouterSessionTest {

    private lateinit var server: MockWebServer
    private lateinit var session: RouterSession

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        session = RouterSession()
        session.configure("127.0.0.1", server.port, false, "root", "secret")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun ok(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    @Test
    fun loginUsesAnonSessionAndCallCarriesSidInFourItemParams() = runBlocking {
        server.enqueue(ok("""{"jsonrpc":"2.0","id":1,"result":[0,{"ubus_rpc_session":"sid-1","timeout":3600}]}"""))
        server.enqueue(ok("""{"jsonrpc":"2.0","id":2,"result":[0,{"hostname":"ImmortalWrt","model":"Redmi AX6"}]}"""))

        assertEquals("sid-1", session.login())
        val payload = session.call("system", "board")

        val loginRequest = server.takeRequest()
        val callRequest = server.takeRequest()

        val loginParams = Json.parseToJsonElement(loginRequest.body.readUtf8())
            .jsonObject["params"]!!.jsonArray
        assertEquals(4, loginParams.size)
        assertEquals(ANON_SESSION, loginParams[0].jsonPrimitive.content)
        assertEquals("session", loginParams[1].jsonPrimitive.content)
        assertEquals("login", loginParams[2].jsonPrimitive.content)

        val callParams = Json.parseToJsonElement(callRequest.body.readUtf8())
            .jsonObject["params"]!!.jsonArray
        assertEquals(4, callParams.size)
        assertEquals("sid-1", callParams[0].jsonPrimitive.content)
        assertEquals("system", callParams[1].jsonPrimitive.content)
        assertEquals("board", callParams[2].jsonPrimitive.content)
        assertTrue(callParams[3] is JsonObject)

        assertEquals("ImmortalWrt", payload.jsonObject["hostname"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun loginFailureCode6IsLoginFailedAndNotRetried() {
        server.enqueue(ok("""{"jsonrpc":"2.0","id":1,"result":[6,{}]}"""))

        val error = assertThrows(RouterException::class.java) {
            runBlocking { session.login() }
        }
        assertEquals(ErrKind.LOGIN_FAILED, error.kind)
        assertEquals(6, error.ubusCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun luciCallUsesThreeItemParamsAndSysauthCookie() = runBlocking {
        server.enqueue(ok("""{"jsonrpc":"2.0","id":1,"result":[0,{"ubus_rpc_session":"sid-1","timeout":3600}]}"""))
        server.enqueue(ok("""{"jsonrpc":"2.0","id":2,"result":[0,{"hosts":[]}]}"""))

        session.login()
        session.luciCall("luci.vantage", "wired")

        val loginRequest = server.takeRequest()
        val luciRequest = server.takeRequest()

        assertEquals("sysauth=sid-1", luciRequest.getHeader("Cookie"))
        assertEquals("/cgi-bin/luci/admin/ubus", luciRequest.path)

        val params = Json.parseToJsonElement(luciRequest.body.readUtf8())
            .jsonObject["params"]!!.jsonArray
        assertEquals(3, params.size)
        assertEquals("luci.vantage", params[0].jsonPrimitive.content)
        assertEquals("wired", params[1].jsonPrimitive.content)
        assertTrue(params[2] is JsonObject)
        // 登录本身不能带 sysauth
        assertEquals(null, loginRequest.getHeader("Cookie"))
    }

    @Test
    fun http403MapsToAuthRequired() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("<html>Forbidden</html>"))

        val error = assertThrows(RouterException::class.java) {
            runBlocking { session.login() }
        }
        assertEquals(ErrKind.AUTH_REQUIRED, error.kind)
        assertEquals(403, error.httpCode)
    }

    @Test
    fun expiredSessionTriggersSingleReloginAndRetry() = runBlocking {
        server.enqueue(ok("""{"jsonrpc":"2.0","id":1,"result":[0,{"ubus_rpc_session":"sid-1","timeout":3600}]}"""))
        server.enqueue(ok("""{"jsonrpc":"2.0","id":2,"result":[5,{}]}"""))
        server.enqueue(ok("""{"jsonrpc":"2.0","id":3,"result":[0,{"ubus_rpc_session":"sid-2","timeout":3600}]}"""))
        server.enqueue(ok("""{"jsonrpc":"2.0","id":4,"result":[0,{"ok":true}]}"""))

        val payload = session.call("system", "info")

        assertEquals(4, server.requestCount)
        server.takeRequest() // 1) 首次登录
        server.takeRequest() // 2) 第一次调用，返回 status 5
        server.takeRequest() // 3) 重登
        val retriedCall = server.takeRequest() // 4) 重试调用
        val lastParams = Json.parseToJsonElement(retriedCall.body.readUtf8())
            .jsonObject["params"]!!.jsonArray
        assertTrue(payload.jsonObject.containsKey("ok"))
        assertEquals("sid-2", lastParams[0].jsonPrimitive.content)
    }

    @Test
    fun html502MapsToHttpError() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>"))

        val error = assertThrows(RouterException::class.java) {
            runBlocking { session.login() }
        }
        assertEquals(ErrKind.HTTP_ERROR, error.kind)
    }

    @Test
    fun nonJsonBodyMapsToBadResponse() {
        server.enqueue(ok("<html>LuCI login page</html>"))

        val error = assertThrows(RouterException::class.java) {
            runBlocking { session.login() }
        }
        assertEquals(ErrKind.BAD_RESPONSE, error.kind)
    }
}
