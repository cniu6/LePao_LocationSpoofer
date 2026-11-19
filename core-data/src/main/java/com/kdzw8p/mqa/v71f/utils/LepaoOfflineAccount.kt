package com.kdzw8p.mqa.v71f.utils

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** 仅在宿主完全停止后写会话；不触碰历史数据库，不接受正在运行的宿主缓存。 */
object LepaoOfflineAccount {
    private const val PKG = "com.lptiyu.tanke"
    private val keys = setOf("user_id", "token", "access_token", "refresh_token", "refresh_expire",
        "role", "admin", "user_nickname", "user_name", "user_avatar_url", "user_phone",
        "user_num", "auth_num", "school_name", "school_id", "run_rule", "term_id", "course_id",
        "user_access_token", "user_openId", "student_status", "user_sex")

    /** 凭据只经过私有临时文件，不进入命令行或日志。失败时保持宿主停止。 */
    fun write(context: Context, profile: JSONObject): Boolean {
        val payload = File.createTempFile("lepao-account-", ".json", context.cacheDir)
        try {
            payload.writeText(profile.toString())
            val command = "CLASSPATH=${quote(context.applicationInfo.sourceDir)} /system/bin/app_process /system/bin " +
                "com.kdzw8p.mqa.v71f.utils.LepaoOfflineAccount ${quote(payload.absolutePath)} >/dev/null 2>&1"
            for (su in listOf("su", "/system/bin/su", "/system/xbin/su", "/su/bin/su", "/data/adb/ksu/bin/su", "/debug_ramdisk/su")) {
                val process = try { ProcessBuilder(su, "-c", command).start() } catch (_: Exception) { continue }
                try {
                    process.outputStream.close()
                    if (!process.waitFor(15, TimeUnit.SECONDS)) {
                        process.destroyForcibly().waitFor()
                        return false
                    }
                    if (process.exitValue() == 0) return true
                    // helper 已运行但校验/写入失败，不换 su 重写同一份数据。
                    if (process.exitValue() == 42) return false
                } finally { process.destroy() }
            }
            return false
        } finally { payload.delete() }
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            require(android.os.Process.myUid() == 0 && args.size == 1)
            val profile = JSONObject(File(args[0]).readText())
            val values = values(profile)
            val stop = ProcessBuilder("/system/bin/am", "force-stop", "--user", "0", PKG).start()
            stop.inputStream.close(); stop.errorStream.close()
            require(stop.waitFor() == 0)
            val ps = ProcessBuilder("/system/bin/ps", "-A", "-o", "NAME").start()
            val names = ps.inputStream.bufferedReader().use { it.readLines() }
            require(ps.waitFor() == 0 && names.none { it.trim() == PKG || it.trim().startsWith("$PKG:") })
            val root = File("/data/user/0/$PKG")
            val data = File(root, "files/mmkv/setting")
            val meta = File(root, "files/mmkv/setting.crc")
            val sp = File(root, "shared_prefs/setting.xml")
            require(data.isFile && meta.isFile && sp.isFile)
            val oldData = data.readBytes()
            val oldMeta = meta.readBytes()
            val oldSp = sp.readBytes()
            val updated = patch(oldData, oldMeta, values)
            // 乐跑 4.3.3 的 shared_prefs/setting.xml 是加密二进制（不足 100 字节的密文），
            // DocumentBuilder 必定抛 SAXParseException；只跳过它，MMKV 与 crc 照常写，
            // 否则整个切号 helper 会被这份不可解析的文件卡死（主函数统一 exitProcess(42)）。
            val xml = try { patchPreferences(oldSp, values) } catch (_: Throwable) { null }
            try {
                // 原文件就地写入，保留 UID、权限与 SELinux 标签；失败同步回滚三份文件。
                writeSynced(data, updated.first)
                writeSynced(meta, updated.second)
                if (xml != null) writeSynced(sp, xml)
                require(LepaoProfileSnapshot.decodeSession(data.readBytes()).userId == values.getValue("user_id"))
                File(root, "shared_prefs/setting.xml.bak").delete()
            } catch (e: Exception) {
                writeSynced(data, oldData); writeSynced(meta, oldMeta)
                if (xml != null) writeSynced(sp, oldSp)
                throw e
            } finally { oldData.fill(0); oldMeta.fill(0); oldSp.fill(0) }
            kotlin.system.exitProcess(0)
        } catch (_: Throwable) {
            kotlin.system.exitProcess(42)
        }
    }

    internal fun values(profile: JSONObject): Map<String, String> {
        val session = JSONObject(profile.getString("session"))
        val uid = profile.getString("user_id")
        val token = session.optString("token").ifBlank { session.optString("access_token") }
        require(LepaoRunData.isAccountId(uid) && session.optString("user_id") == uid && token.isNotBlank())
        // 备份包若已带算好的 MMKV 键值（web 导出的就是这种），直接用它：
        // 导入后切号写什么，导出时就定死了，不再依赖档案字段是否齐、也不再二次推导。
        profile.optJSONObject("mmkv")?.let { baked ->
            val out = linkedMapOf<String, String>()
            for (key in keys) {
                val value = baked.optString(key)
                if (value.isNotEmpty()) out[key] = value
            }
            // 拿一份写着别人 uid 的快照不去刷；属于本账号就补齐 token 并直接返回。
            if (out["user_id"] == uid) {
                if (out["token"].isNullOrEmpty()) out["token"] = token
                if (out["access_token"].isNullOrEmpty()) out["access_token"] = token
                return out
            }
        }
        return linkedMapOf("user_id" to uid, "token" to token, "access_token" to token,
            "refresh_token" to session.optString("refresh_token"), "refresh_expire" to session.optString("refresh_expire", "0"),
            "role" to session.optString("role").ifBlank { profile.optString("role", "1") },
            "admin" to profile.optString("is_admin", "0"), "user_nickname" to profile.optString("nickname"),
            "user_name" to profile.optString("nickname"), "user_avatar_url" to profile.optString("avatar_url"),
            "user_phone" to profile.optString("phone"), "user_num" to profile.optString("student_no"),
            "auth_num" to profile.optString("student_no"), "school_name" to profile.optString("school"),
            "school_id" to profile.optString("school_id"), "user_sex" to profile.optString("sex", "0")
        ).apply {
            LepaoRunData.objectOrNull(profile.optString("run_rule_json"))
                ?.takeIf { LepaoRunData.validRuleOwner(it, uid) }?.let { put("run_rule", it.toString()) }
        }
    }

    /** 保留所有非账号记录，只追加账户字段及删除标记；同步更新 MMKV 活动长度和 CRC。 */
    internal fun patch(data: ByteArray, meta: ByteArray, values: Map<String, String>): Pair<ByteArray, ByteArray> {
        require(meta.size >= 112 && data.size >= 8)
        fun int(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset)
        val size = int(data, 0)
        val version = int(meta, 4)
        require(version in 0..4 && size > 0 && size <= data.size - 4)
        require(meta.sliceArray(12..27).all { it == 0.toByte() }) // 加密实例不按明文处理。
        require(version < 4 || meta.sliceArray(104..111).all { it == 0.toByte() }) // 不处理过期值编码。
        if (version >= 3) require(int(meta, 28) == size)
        require(CRC32().apply { update(data, 4, size) }.value.toInt() == int(meta, 0))
        val body = ByteArrayOutputStream().apply {
            write(data, 4, size)
            for (key in keys) {
                val value = values[key]
                val encoded = when {
                    value == null -> byteArrayOf()
                    key in setOf("user_id", "refresh_expire", "role", "admin", "user_sex") -> varint(value.toLongOrNull() ?: 0)
                    else -> text(value)
                }
                write(text(key)); write(varint(encoded.size.toLong())); write(encoded)
            }
        }.toByteArray()
        require(body.size < LepaoProfileSnapshot.MAX_FILE_BYTES - 4)
        val result = data.copyOf(maxOf(data.size, ((body.size + 4 + 4095) / 4096) * 4096))
        body.copyInto(result, 4)
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(0, body.size)
        val crc = CRC32().apply { update(body) }.value.toInt()
        val metadata = meta.copyOf()
        ByteBuffer.wrap(metadata).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, crc); putInt(8, int(meta, 8) + 1)
            if (version >= 3) { putInt(28, body.size); putInt(32, body.size); putInt(36, crc) }
        }
        require(LepaoProfileSnapshot.decodeSession(result).userId == values.getValue("user_id"))
        return result to metadata
    }

    private fun text(value: String): ByteArray = value.toByteArray(Charsets.UTF_8).let { varint(it.size.toLong()) + it }
    private fun varint(value: Long): ByteArray {
        var n = value
        return ByteArrayOutputStream().apply {
            do { val b = (n and 127).toInt(); n = n ushr 7; write(if (n != 0L) b or 128 else b) } while (n != 0L)
        }.toByteArray()
    }

    private fun patchPreferences(bytes: ByteArray, values: Map<String, String>): ByteArray {
        val factory = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }
        val doc = factory.newDocumentBuilder().parse(bytes.inputStream())
        val root = doc.documentElement
        require(root.tagName == "map")
        for (i in root.childNodes.length - 1 downTo 0) {
            val node = root.childNodes.item(i)
            if (node.attributes?.getNamedItem("name")?.nodeValue in keys) root.removeChild(node)
        }
        for ((key, value) in values) {
            val type = when (key) { "user_id", "refresh_expire" -> "long"; "role", "admin", "user_sex" -> "int"; else -> "string" }
            val element = doc.createElement(type)
            element.setAttribute("name", key)
            if (type == "string") element.textContent = value else element.setAttribute("value", (value.toLongOrNull() ?: 0).toString())
            root.appendChild(element)
        }
        return ByteArrayOutputStream().apply {
            TransformerFactory.newInstance().newTransformer().transform(DOMSource(doc), StreamResult(this))
        }.toByteArray()
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        java.io.FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }
}
