package me.iacn.biliroaming.hook

import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.findClassOrNull
import me.iacn.biliroaming.utils.hookBeforeMethod
import me.iacn.biliroaming.utils.sPrefs
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 在 OkHttp 请求层拦截开屏广告接口，不依赖宿主任何混淆类名。
 *
 * [GsonHook] / [KotlinxJsonHook] 走的是反序列化后清空模型字段，依赖 `SplashListResponse`
 * 这类类名；宿主换模型或换序列化框架后会静默失效。这里的做法是在请求发出前直接返回
 * 一个 code=200 的空响应，只要接口路径不变就一直有效，与宿主版本无关。
 *
 * 三个路径取自 feSHt377/FuckBiliAds 在 9.18.0 上的实测值：
 * 商业开屏 / 展示上报 / 品牌开屏。
 */
class SplashAdRequestHook(classLoader: ClassLoader) : BaseHook(classLoader) {

    override fun startHook() {
        if (!enabled()) return
        if (!install()) Log.d("startHook: SplashAdRequest failed")
    }

    private fun enabled() =
        sPrefs.getBoolean("hidden", false) && sPrefs.getBoolean("purify_splash", false)

    private fun install(): Boolean = runCatching {
        val builderClass = "okhttp3.OkHttpClient\$Builder".findClassOrNull(mClassLoader)
            ?: return false
        // 从公开 API 的签名取接口类型，不依赖 Interceptor / Chain 的实现类名。
        val addInterceptor = builderClass.declaredMethods.firstOrNull {
            it.name == "addInterceptor" && it.parameterCount == 1 && it.parameterTypes[0].isInterface
        } ?: return false
        val clientClass = "okhttp3.OkHttpClient".findClassOrNull(mClassLoader)
            ?: return false
        val buildMethod = builderClass.declaredMethods.firstOrNull {
            it.name == "build" && it.parameterCount == 0 && it.returnType == clientClass
        } ?: return false

        val blocker = Proxy.newProxyInstance(
            addInterceptor.parameterTypes[0].classLoader,
            arrayOf(addInterceptor.parameterTypes[0]),
            SplashBlocker(mClassLoader)
        )

        // 必须在 build() 执行前插入，否则客户端已经构建完成，拦截器不会进入链路。
        // addInterceptor 是幂等的调用入口，但重复 build 会累积，这里按实例去重。
        val injected = java.util.Collections.newSetFromMap(
            java.util.WeakHashMap<Any, Boolean>()
        )
        buildMethod.hookBeforeMethod { param ->
            val owner = param.thisObject ?: return@hookBeforeMethod
            if (!injected.add(owner)) return@hookBeforeMethod
            runCatching {
                addInterceptor.invoke(owner, blocker)
            }.onFailure {
                injected.remove(owner)
                Log.e("SplashAdRequestHook: addInterceptor failed: $it")
            }
        }

        Log.d("startHook: SplashAdRequest")
        true
    }.getOrElse {
        Log.e("SplashAdRequestHook: install error: $it")
        false
    }

    /** 所有宿主对象都按反射访问，避免编译期绑定到宿主侧实现。 */
    private class SplashBlocker(private val mClassLoader: ClassLoader) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return when (method.name) {
                "equals" -> args?.getOrNull(0) === proxy
                "hashCode" -> System.identityHashCode(proxy)
                else -> NAME
            }
            // 只实现 intercept(chain)；其余成员理论上不会由 OkHttp 调用。
            val chain = args?.getOrNull(0) ?: return null
            val request = call(chain, "request") ?: return proceed(chain, null)
            val path = pathOf(request)
            if (path == null || path !in TARGETS || !enabled()) return proceed(chain, request)
            return emptyResponse(request) ?: throw java.io.IOException("splash blocked: $path")
        }

        private fun proceed(chain: Any, request: Any?): Any? =
            call(chain, "proceed", request?.let { arrayOf(it) } ?: arrayOfNulls<Any>(0))

        private fun pathOf(request: Any): String? = runCatching {
            val url = call(request, "url") ?: return null
            call(url, "encodedPath") as? String
        }.getOrNull()

        private fun emptyResponse(request: Any): Any? = runCatching {
            val builderClass = "okhttp3.Response\$Builder".findClassOrNull(mClassLoader)
                ?: return null
            val bodyClass = "okhttp3.ResponseBody".findClassOrNull(mClassLoader)
                ?: return null
            val mediaTypeClass = "okhttp3.MediaType".findClassOrNull(mClassLoader)
            val requestClass = "okhttp3.Request".findClassOrNull(mClassLoader) ?: return null
            val protocolClass = "okhttp3.Protocol".findClassOrNull(mClassLoader) ?: return null

            val body = emptyBody(bodyClass, mediaTypeClass) ?: return null
            // Response.Builder.build() 要求 protocol 非空，取 HTTP_1_1。
            val protocol = protocolClass.enumConstants?.firstOrNull {
                (it as Enum<*>).name == "HTTP_1_1"
            } ?: protocolClass.enumConstants?.firstOrNull() ?: return null

            val builder = builderClass.getConstructor().newInstance()
            builderClass.getMethod("request", requestClass).invoke(builder, request)
            builderClass.getMethod("protocol", protocolClass).invoke(builder, protocol)
            builderClass.getMethod("code", Int::class.javaPrimitiveType).invoke(builder, 200)
            builderClass.getMethod("message", String::class.java).invoke(builder, "OK")
            builderClass.getMethod("body", bodyClass).invoke(builder, body)
            builderClass.getMethod("build").invoke(builder)
        }.getOrElse {
            Log.e("SplashAdRequestHook: emptyResponse error: $it")
            null
        }

        private fun emptyBody(bodyClass: Class<*>, mediaTypeClass: Class<*>?): Any? = runCatching {
            val mediaType = mediaTypeClass?.let { type ->
                type.declaredMethods.firstOrNull {
                    it.isStatic && it.returnType == type && it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java
                }?.let { m ->
                    runCatching { m.invoke(null, "application/json; charset=utf-8") }.getOrNull()
                }
            }
            bodyClass.declaredMethods.firstOrNull {
                it.isStatic && it.returnType == bodyClass && it.parameterCount == 2 &&
                    it.parameterTypes[1] == String::class.java &&
                    (mediaTypeClass == null || it.parameterTypes[0] == mediaTypeClass)
            }?.let { m ->
                runCatching { m.invoke(null, mediaType, EMPTY_RESPONSE) }.getOrNull()
            }
        }.getOrNull()

        private fun call(target: Any, name: String, args: Array<Any?>? = null): Any? = runCatching {
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == (args?.size ?: 0) }
                ?.invoke(target, *args.orEmpty())
        }.getOrNull()

        companion object {
            const val NAME = "BiliRoamingSplashBlocker"
            val TARGETS = setOf(
                "/x/v2/splash/list",
                "/x/v2/splash/show",
                "/x/v2/splash/brand/list",
            )

            /** 宿主会当成"本轮没有广告"处理。 */
            const val EMPTY_RESPONSE =
                "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":{" +
                    "\"show\":[],\"splash_request_id\":\"\"," +
                    "\"disperse_interval\":0,\"list\":[],\"keep_ids\":[]}}"

            fun enabled() =
                sPrefs.getBoolean("hidden", false) && sPrefs.getBoolean("purify_splash", false)
        }
    }
}
