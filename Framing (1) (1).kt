package com.glassremote.data.network

import com.google.protobuf.MessageLite
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Both Remote v2 channels use varint-length-prefixed protobuf messages. */
object Framing {
    fun write(out: OutputStream, msg: MessageLite) {
        val bytes = msg.toByteArray()
        var x = bytes.size
        while ((x and 0x7F.inv()) != 0) {
            out.write((x and 0x7F) or 0x80)
            x = x ushr 7
        }
        out.write(x)
        out.write(bytes)
        out.flush()
    }

    fun <T> read(input: InputStream, parse: (ByteArray) -> T): T {
        var shift = 0
        var len = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Connection closed by TV")
            len = len or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
            if (shift > 28) throw IOException("Bad length prefix")
        }
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw EOFException("Connection closed mid-message")
            off += n
        }
        return parse(buf)
    }
}
