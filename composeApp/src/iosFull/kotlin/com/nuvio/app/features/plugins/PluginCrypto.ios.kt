package com.nuvio.app.features.plugins

import platform.Foundation.NSHMAC
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.CommonCrypto.CC_SHA256
import platform.CommonCrypto.CC_LONG

actual object PluginCrypto {
    actual fun sha256(input: String): String {
        val data = input.toNSData()
        val hash = UByteArray(32)
        CC_SHA256(data.bytes, data.length.toUInt(), hash.toCValues())
        return hash.joinToString("") { "%02x".format(it.toByte()) }
    }

    actual fun hmacSha256(input: String, key: String): String {
        val inputData = input.toNSData()
        val keyData = key.toNSData()
        val hash = UByteArray(32)
        NSHMAC(NSTypeEncodings(rawValue = "HmacSHA256"), keyData, inputData, hash.toCValues())
        return hash.joinToString("") { "%02x".format(it.toByte()) }
    }
}

// Helper para converter String -> NSData
private fun String.toNSData() = NSString.create(string = this).dataUsingEncoding(NSUTF8StringEncoding)!!