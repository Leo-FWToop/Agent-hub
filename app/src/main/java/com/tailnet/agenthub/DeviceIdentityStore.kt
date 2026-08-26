package com.tailnet.agenthub

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject

/**
 * OpenClaw 设备身份：Ed25519 密钥对，用于 Gateway 握手时签名 connect 参数。
 *
 * 背景（openclaw v2026.4.x 源码 gateway/server/ws-connection）：
 * 仅用 token/password 认证且不带设备签名的客户端，服务端会在握手成功后
 * 清空其自声明的 scopes（防止自我授权），导致 agents.list / chat.send 报
 * "missing scope"。带上设备签名并完成一次配对批准后，scopes 才会生效。
 * 这也是官方 Android 客户端（apps/android）采用的同一套流程。
 */
class DeviceIdentityStore(context: Context) {

    class DeviceIdentity(
        val deviceId: String,
        val publicKeyRawBase64: String,
        val privateKeyPkcs8Base64: String,
    )

    private val identityFile = File(context.filesDir, "openclaw/identity/device.json")

    @Volatile
    private var cached: DeviceIdentity? = null

    @Synchronized
    fun loadOrCreate(): DeviceIdentity {
        cached?.let { return it }
        val existing = readIdentity()
        if (existing != null) {
            cached = existing
            return existing
        }
        val fresh = generate()
        writeIdentity(fresh)
        cached = fresh
        return fresh
    }

    /** 用设备私钥对 payload 做 Ed25519 签名，返回 base64url（无填充） */
    fun signPayload(payload: String, identity: DeviceIdentity): String? = try {
        val privateKeyBytes = Base64.decode(identity.privateKeyPkcs8Base64, Base64.DEFAULT)
        val pkInfo = org.bouncycastle.asn1.pkcs.PrivateKeyInfo.getInstance(privateKeyBytes)
        val parsed = pkInfo.parsePrivateKey()
        val rawPrivate = org.bouncycastle.asn1.DEROctetString.getInstance(parsed).octets
        val privateKey = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(rawPrivate, 0)
        val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
        signer.init(true, privateKey)
        val bytes = payload.toByteArray(Charsets.UTF_8)
        signer.update(bytes, 0, bytes.size)
        base64UrlEncode(signer.generateSignature())
    } catch (_: Throwable) {
        null
    }

    /** 原始公钥（32 字节）的 base64url 编码，握手时放进 device.publicKey */
    fun publicKeyBase64Url(identity: DeviceIdentity): String? = try {
        val raw = Base64.decode(identity.publicKeyRawBase64, Base64.DEFAULT)
        base64UrlEncode(raw)
    } catch (_: Throwable) {
        null
    }

    private fun readIdentity(): DeviceIdentity? {
        return try {
            if (!identityFile.exists()) return null
            val obj = JSONObject(identityFile.readText(Charsets.UTF_8))
            val id = obj.optString("deviceId")
            val pub = obj.optString("publicKeyRawBase64")
            val priv = obj.optString("privateKeyPkcs8Base64")
            if (id.isBlank() || pub.isBlank() || priv.isBlank()) null
            else DeviceIdentity(id, pub, priv)
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeIdentity(identity: DeviceIdentity) {
        try {
            identityFile.parentFile?.mkdirs()
            identityFile.writeText(
                JSONObject()
                    .put("deviceId", identity.deviceId)
                    .put("publicKeyRawBase64", identity.publicKeyRawBase64)
                    .put("privateKeyPkcs8Base64", identity.privateKeyPkcs8Base64)
                    .toString(),
                Charsets.UTF_8
            )
        } catch (_: Throwable) {
            // 尽力而为：下次会重新生成（换设备 ID，需重新配对）
        }
    }

    private fun generate(): DeviceIdentity {
        // 直接用 BC 轻量 API，避免 JCA provider 在 Android 上的兼容问题
        val gen = org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator()
        gen.init(org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = gen.generateKeyPair()
        val pub = kp.public as org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
        val priv = kp.private as org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
        val rawPublic = pub.encoded // 32 字节
        val pkcs8 = org.bouncycastle.crypto.util.PrivateKeyInfoFactory
            .createPrivateKeyInfo(priv).encoded
        return DeviceIdentity(
            deviceId = sha256Hex(rawPublic),
            publicKeyRawBase64 = Base64.encodeToString(rawPublic, Base64.NO_WRAP),
            privateKeyPkcs8Base64 = Base64.encodeToString(pkcs8, Base64.NO_WRAP),
        )
    }

    /** 与服务端一致：deviceId = sha256(原始公钥) 的 hex */
    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun base64UrlEncode(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
