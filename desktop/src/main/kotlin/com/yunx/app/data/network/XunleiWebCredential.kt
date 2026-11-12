package com.yunx.app.data.network

import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI

/**
 * 迅雷网页登录凭据（pan.xunlei.com 的 localStorage 对象）。
 *
 * @param accessToken 云盘接口用的 access_token（网页版 token）
 * @param refreshToken 刷新令牌；网页 token 只能配**网页** client_id 刷新（见 [CLIENT_ID]）
 * @param deviceId 网页侧 device_id（取自 deviceid Cookie/脚本兜底；可能为空，调用方自行兜底）
 * @param captchaToken 网页侧 captcha_token（可能为空）
 * @param userId 用户 ID（JWT sub / user_id），用于后续换号检测
 * @param nickname 昵称（网页对象里通常没有，可能为空）
 */
data class XunleiWebTokens(
    val accessToken: String,
    val refreshToken: String,
    val deviceId: String,
    val captchaToken: String,
    val userId: String,
    val nickname: String
)

/**
 * 迅雷网页登录：站点私有格式的解析（纯逻辑，便于单测）+ JCEF 桥接脚本。
 *
 * ⚠️ 这依赖迅雷网页版的私有实现（键名、字段名）。改版失效时，用户仍可走「手动粘贴」兜底
 * （[parse] 同时支持整段 JSON、带引号的 JSON 字符串、裸 token 三种粘贴形态）。
 */
object XunleiWebCredential {

    /**
     * 网页版 OAuth client_id（抓包自 pan.xunlei.com，与 App 端 Xp6vsxz_7IYVw2BB 不同）。
     * 网页 token 只能用这个 client_id 刷新，且**不需要** client_secret。
     */
    const val CLIENT_ID = "Xqp0kJBXWhwaTpB6"

    /** 登录态在 localStorage 中的键名 */
    const val STORAGE_KEY = "credentials_$CLIENT_ID"

    /** 验证码 token 在 localStorage 中的键名（有效期由页面自己维护，过期就忽略） */
    const val CAPTCHA_KEY = "captcha_$CLIENT_ID"

    /** 网页登录页（未登录会自动进入登录流程；扫码 / 验证码都由官网页面自己处理） */
    const val LOGIN_URL = "https://pan.xunlei.com/"

    /** 落库时写入的认证方式：网页 token 的刷新路径与 App token 不同 */
    const val AUTH_TYPE = "webToken"

    /** 网页版桌面 UA（网页 token 的刷新请求必须带，否则被风控拒绝） */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /**
     * 与 [DESKTOP_UA] 配套的客户端提示头（Sec-CH-UA 系，版本号必须与 UA 里的 Chrome 大版本一致）。
     * 桌面 UA 若不同步这些头，服务端会按移动端 client hints 判定，与 UA 冲突导致页面降级/被拒。
     *
     * ⚠️ 源码里的 `\"` 是 **Kotlin 转义写法**：运行时字符串里就是普通双引号 `"`、**不含反斜杠**
     * （品牌名必须带引号，这是 client hints 的规范格式，与真实 Chrome 一致）。
     */
    const val DESKTOP_SEC_CH_UA = "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\""
    const val DESKTOP_SEC_CH_UA_MOBILE = "?0"
    const val DESKTOP_SEC_CH_UA_PLATFORM = "\"Windows\""

    // ---------- 网页版 captcha 签名（本地现签，不依赖隐藏浏览器） ----------

    /**
     * 网页版 captcha 签名参数：本地算 captcha_sign → 直接换 token。
     *
     * ⚠️ 与 App 通道**不是一套**：网页端 client_version 会被强制更新、9 条盐随 bundle 轮换
     * （约 20 天一次）。签名一旦被服务端拒绝，就只能回退网页会话
     * （见 `XunleiAccountRepository.refreshWebSession`）。替换时版本与盐必须整组更新，盐顺序敏感。
     */
    const val WEB_CLIENT_VERSION = "1.93.10"
    const val WEB_PACKAGE_NAME = "pan.xunlei.com"
    private const val WEB_SIGN_HOST = "pan.xunlei.com"
    private const val WEB_ALG_VERSION = "1"

    /** 9 条盐，顺序敏感（来自网页版 public-key 链） */
    private val WEB_CAPTCHA_SALTS = listOf(
        "DVM8BRQfJWDsIE0JbH5GCUM0z0jU1yGymBTj4Eyloazv1F+w0G7ig",
        "T",
        "a1fUxfjOO1/dW++DpGupRgw9P93L",
        "aktKbp+/lQc3MvlMHIupt65LjAgJOL6ttMFvTKK+7+WKubSf9rT",
        "SB/ne9FQUnQmWz4Wuv9KqKfGOBOCUubpHpECxOHfD4hNoMclYA2Yq",
        "sOZFBQ8IMFy3OFjAIf/eA65iqFvS9TIKzLyrhiT6",
        "ZhJdLgL7MGh2GanboB0yk",
        "Osf",
        "v"
    )

    /** 网页 captcha_token 的保守有效期：服务端 expires_in=300s，页面本身再提前 30s 视为过期 */
    const val WEB_CAPTCHA_TOKEN_TTL_MS = 270_000L

    /**
     * 网页版 captcha_sign：`raw = client_id + version + host + device_id + timestamp_ms`
     * → 9 层 `md5(raw + salt)`，前缀 `1.`。
     *
     * 与 App 通道的区别：App 是 10 层、raw 里放 package_name；网页是 9 层、raw 里放 host。
     * 用 App 签名去换网页 token 会被服务端判 `client_id not match`（就是「验证码无效」的根因）。
     */
    fun computeCaptchaSign(deviceId: String, timestampMs: Long): String {
        var h = CLIENT_ID + WEB_CLIENT_VERSION + WEB_SIGN_HOST + deviceId + timestampMs
        WEB_CAPTCHA_SALTS.forEach { salt -> h = md5Hex(h + salt) }
        return "$WEB_ALG_VERSION.$h"
    }

    private const val HEX = "0123456789abcdef"

    private fun md5Hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        digest.forEach { b ->
            out.append(HEX[(b.toInt() shr 4) and 0x0f]).append(HEX[b.toInt() and 0x0f])
        }
        return out.toString()
    }

    /**
     * 网页会话保活的就绪判据（无头 JCEF 用，见 [com.yunx.app.data.network.video.HeadlessWebView]）：
     * 页面加载完成，且网页自己已经把 `captcha_<clientId>` 刷新成有效 token。
     *
     * 为什么不能只看 `readyState`：页面刚加载完时 localStorage 里可能还是**已过期**的旧 token，
     * 它的 JS 要再发一次 captcha/init 才会更新。这里直接等有效 token，等到即取。
     */
    val REFRESH_READY_JS: String = """
        (function(){try{
          if(document.readyState!=='complete')return false;
          var c=JSON.parse(localStorage.getItem('$CAPTCHA_KEY')||'{}');
          return !!(c&&c.token&&Date.parse(c.expires_at)>Date.now());
        }catch(e){return false}})()
    """.trimIndent()

    /**
     * 从网页会话里取凭据的脚本（返回 `encodeURIComponent(JSON)` 字符串，空串=还没登录）。
     *
     * 与 [COOKIE_BRIDGE_SCRIPT] 的区别：那条是给「有界面登录窗」用的（JCEF 拿不到 JS 返回值，
     * 只能写 Cookie 再读）；无头会话可以直接把字符串回传（走 cefQuery）。
     */
    val READ_SCRIPT: String = """
        (function(){try{
          var prefix='$STORAGE_KEY';
          var sub=localStorage.getItem('current_sub');
          var raw=sub?localStorage.getItem(prefix+'@'+sub):localStorage.getItem(prefix);
          if(!raw)return '';
          var data=JSON.parse(raw);
          if(!data||typeof data!=='object'||Array.isArray(data))return '';
          if(sub&&data.sub&&String(data.sub)!==sub)return '';
          try{
            var c=JSON.parse(localStorage.getItem('$CAPTCHA_KEY')||'{}');
            if(c&&c.token&&Date.parse(c.expires_at)>Date.now())data.captcha_token=c.token;
          }catch(e){}
          try{
            var all=document.cookie.split(';');
            for(var i=0;i<all.length;i++){
              var s=all[i].trim();
              if(s.indexOf('deviceid=')!==0)continue;
              var d=decodeURIComponent(s.slice(9));
              data.device_id=(d.indexOf('.')>-1&&d.length>32)?d.split('.')[1].slice(0,32):d;
              break;
            }
          }catch(e){}
          return encodeURIComponent(JSON.stringify(data));
        }catch(e){return ''}})()
    """.trimIndent()

    /** 允许 WebView 停留 / 跳转的域名（登录过程会经 i.xunlei.com 等官方域，统一放行 *.xunlei.com） */
    private val TRUSTED_HOSTS = listOf("xunlei.com")

    /** JCEF 桥接：把网页凭据写进 Cookie 的分片键前缀 / 分片数键（单条 Cookie 有 4KB 上限） */
    const val CRED_COOKIE_PREFIX = "yunx_xl_cred_"
    const val CRED_COOKIE_COUNT = "yunx_xl_cred_n"

    /**
     * 「添加账号」时清掉**网页自己**保存的登录态（localStorage）。
     *
     * ★ 只清 Cookie 是不够的：pan.xunlei.com 把 `credentials_<clientId>`（含 refresh_token）写在
     *   localStorage 里，页面一加载就会用它把旧账号静默登回来 —— 用户看到的正是「添加账号后
     *   登录页一闪就关 / 加进来的还是原来那个」。所以新账号登录前必须把这份凭据一起删掉。
     */
    val CLEAR_SESSION_SCRIPT: String = """
        (function(){try{
          localStorage.removeItem('$STORAGE_KEY');
          localStorage.removeItem('$CAPTCHA_KEY');
          localStorage.removeItem('current_sub');
        }catch(e){}})()
    """.trimIndent()

    /**
     * 桌面 JCEF 不支持取 executeJavaScript 的返回值，所以改为「把凭据写进 Cookie，再从 Cookie 读回」：
     * 该脚本从 localStorage 取 credentials_<clientId>（子账号 `...@<current_sub>`），补上
     * deviceid Cookie → device_id、captcha_<clientId> → captcha_token，再 URL 编码后分片
     * 写入 `yunx_xl_cred_*` Cookie；Kotlin 侧轮询 Cookie 拼回原文并解析。
     *
     * 分片是因为 access+refresh token 的 JSON 可能超过单条 Cookie 4KB 上限，直接写会被浏览器静默丢弃。
     */
    val COOKIE_BRIDGE_SCRIPT: String = """
        (function(){try{
          var prefix='$STORAGE_KEY';
          var sub=localStorage.getItem('current_sub');
          var raw=sub?localStorage.getItem(prefix+'@'+sub):localStorage.getItem(prefix);
          if(!raw)return;
          var data=JSON.parse(raw);
          if(!data||typeof data!=='object'||Array.isArray(data))return;
          if(sub&&data.sub&&String(data.sub)!==sub)return;
          try{
            var c=JSON.parse(localStorage.getItem('$CAPTCHA_KEY')||'{}');
            if(c&&c.token&&Date.parse(c.expires_at)>Date.now())data.captcha_token=c.token;
          }catch(e){}
          try{
            var all=document.cookie.split(';');
            for(var i=0;i<all.length;i++){
              var s=all[i].trim();
              if(s.indexOf('deviceid=')!==0)continue;
              var d=decodeURIComponent(s.slice(9));
              data.device_id=(d.indexOf('.')>-1&&d.length>32)?d.split('.')[1].slice(0,32):d;
              break;
            }
          }catch(e){}
          var payload=encodeURIComponent(JSON.stringify(data));
          var size=3000;
          var n=Math.ceil(payload.length/size); if(n<1)n=1;
          for(var j=0;j<n;j++){
            document.cookie='$CRED_COOKIE_PREFIX'+j+'='+payload.substr(j*size,size)+';path=/;max-age=600';
          }
          document.cookie='$CRED_COOKIE_COUNT='+n+';path=/;max-age=600';
        }catch(e){}})()
    """.trimIndent()

    /**
     * WebView 是否允许停留在该 URL：仅 https 的 xunlei.com 及其子域、443 端口（about:blank 放行）。
     *
     * ⚠️ `java.net.URI` 与 `android.net.Uri` 一致：URL 未写端口时 port 返回 **-1**（不补默认端口），
     * 所以必须把 -1 当成 443 放行，否则 `https://pan.xunlei.com/` 会被判成不可信、拦掉所有跳转。
     */
    fun isTrustedUrl(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        if (value.isEmpty() || value == "about:blank") return true
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        val port = uri.port
        if (port != -1 && port != 443) return false
        val host = uri.host?.lowercase().orEmpty()
        return TRUSTED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * 解析网页登录凭据。支持三种输入：
     * 1. 完整 JSON（localStorage 原文 / 抓包复制）；
     * 2. 被引号包起来的 JSON 字符串（某些工具复制出来是这种）；
     * 3. 裸 access_token（手动粘贴兜底，此时没有 refresh_token，过期后只能重新登录）。
     *
     * 解析失败（含字段校验不过）一律返回 null，由调用方继续轮询或提示用户。
     */
    fun parse(raw: String): XunleiWebTokens? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        // 形态 2：外层是 JSON 字符串 → 还原成里面的内容
        unwrapQuoted(text)?.let { text = it.trim() }
        if (text.isEmpty()) return null
        // 形态 3：不是 JSON 对象 → 按裸 token 处理（纯字符串逻辑，不碰 JSON）
        if (!text.startsWith("{")) return parseRawToken(text)

        val root = parseObject(text) ?: return null
        return fieldsFrom(toFieldMap(unwrapNested(root)))
    }

    /**
     * 裸 token（手动粘贴 access_token 的场景）：
     * 去掉 `Bearer ` 前缀后过一遍 token 校验，通过就只填 access（没有 refresh，过期只能重登）。
     */
    internal fun parseRawToken(text: String): XunleiWebTokens? {
        val token = stripBearer(text)
        return if (isValidToken(token)) XunleiWebTokens(token, "", "", "", "", "") else null
    }

    /**
     * 从「键 → 值」映射里挑出凭据字段（**纯逻辑**）。
     *
     * 兼容点：snake_case 与 camelCase 两种键名；任一 token 非法（长度/字符集不对）即整体判失败——
     * 网页登录过程中会把中间态写进 localStorage，不能当成登录成功。
     */
    internal fun fieldsFrom(fields: Map<String, String>): XunleiWebTokens? {
        val access = pick(fields, "access_token", "accessToken")
        val refresh = pick(fields, "refresh_token", "refreshToken")
        if (access.isNotEmpty() && !isValidToken(access)) return null
        if (refresh.isNotEmpty() && !isValidToken(refresh)) return null
        if (access.isEmpty() && refresh.isEmpty()) return null
        val captcha = pick(fields, "captcha_token", "captchaToken")
        return XunleiWebTokens(
            accessToken = access,
            refreshToken = refresh,
            deviceId = pick(fields, "device_id", "deviceId"),
            captchaToken = if (isValidToken(captcha)) captcha else "",
            userId = pick(fields, "user_id", "userId", "sub"),
            nickname = pick(fields, "nick_name", "nickname", "name", "user_name")
        )
    }

    /** 去掉 `Bearer ` 前缀与首尾空白（**必须先 trim 再匹配**，否则 "  Bearer xxx  " 匹配不上前缀） */
    private fun stripBearer(value: String): String =
        value.trim().replace(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "").trim()

    /** 把 JSONObject 拍平成「键 → 字符串值」；非字符串/数字的值直接丢掉（token 一定是字符串） */
    private fun toFieldMap(data: JSONObject): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = data.opt(key)
            if (value is String || value is Number) map[key] = value.toString()
        }
        return map
    }

    /** 外层是 JSON 字符串字面量时还原内容；不是则返回 null */
    private fun unwrapQuoted(text: String): String? {
        if (!text.startsWith("\"")) return null
        return runCatching {
            val parsed = JSONTokener(text).nextValue()
            parsed as? String
        }.getOrNull()
    }

    /** 解析成 JSONObject；不是对象（裸 token / 数组 / 非法 JSON）返回 null */
    private fun parseObject(text: String): JSONObject? {
        if (!text.startsWith("{")) return null
        return runCatching { JSONTokener(text).nextValue() as? JSONObject }.getOrNull()
    }

    /**
     * 兼容嵌套包装：有些版本把 token 包在 `credentials` / `token` / `data` 里。
     * 最多向下找两层，找到含 token 字段的那层为止。
     */
    private fun unwrapNested(root: JSONObject): JSONObject {
        var current = root
        repeat(2) {
            if (current.has("access_token") || current.has("accessToken") ||
                current.has("refresh_token") || current.has("refreshToken")
            ) {
                return current
            }
            val nested = current.optJSONObject("credentials")
                ?: current.optJSONObject("token")
                ?: current.optJSONObject("data")
                ?: return current
            current = nested
        }
        return current
    }

    /** 取第一个非空字符串字段（数字也接受） */
    private fun pick(fields: Map<String, String>, vararg keys: String): String {
        for (key in keys) {
            val text = fields[key]?.trim().orEmpty()
            if (text.isEmpty()) continue
            if (CONTROL_CHARS.containsMatchIn(text)) continue
            return text
        }
        return ""
    }

    /**
     * token 合法性：长度 16~16384、仅 JWT/Base64 允许的字符集、且不是 "null"/"undefined" 这类占位串。
     * 中间态写进 localStorage 的垃圾值不会当成登录成功。
     */
    fun isValidToken(value: String): Boolean =
        value.length in 16..16384 &&
            TOKEN_CHARS.matches(value) &&
            value != "null" &&
            value != "undefined"

    private val TOKEN_CHARS = Regex("^[A-Za-z0-9._~+/=-]+$")
    private val CONTROL_CHARS = Regex("[\\x00-\\x1f\\x7f]")
}
