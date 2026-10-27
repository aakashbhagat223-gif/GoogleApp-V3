package com.glassremote.data.network

import com.glassremote.data.security.CertificateManager
import com.glassremote.proto.pairing.*
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

class PairingException(message: String) : Exception(message)

class PairingClient(private val certs: CertificateManager) {

    /**
     * Runs the handshake up to the point where the TV displays its 6-char code.
     * Caller then asks the user for the code and calls [PairingSession.submitCode].
     */
    suspend fun begin(host: String): PairingSession = withContext(Dispatchers.IO) {
        val socket = certs.openSocket(host, PAIRING_PORT)
        try {
            val serverCert = socket.session.peerCertificates[0] as X509Certificate
            val out = socket.outputStream
            val inp = socket.inputStream

            // 1) Request -> Ack
            Framing.write(out, msg().setPairingRequest(
                PairingRequest.newBuilder().setServiceName("atvremote").setClientName("Glass Remote")
            ).build())
            expect(Framing.read(inp, PairingMessage::parseFrom)) { it.hasPairingRequestAck() }

            // 2) Options (hex, 6 symbols, we are the INPUT side) -> TV options
            val hex6 = PairingEncoding.newBuilder()
                .setType(PairingEncodingType.ENCODING_HEXADECIMAL).setSymbolLength(6)
            Framing.write(out, msg().setPairingOption(
                PairingOption.newBuilder().addInputEncodings(hex6)
                    .setPreferredRole(PairingRole.ROLE_INPUT)
            ).build())
            expect(Framing.read(inp, PairingMessage::parseFrom)) { it.hasPairingOption() }

            // 3) Configuration -> Ack; the TV now shows the code
            Framing.write(out, msg().setPairingConfiguration(
                PairingConfiguration.newBuilder().setEncoding(hex6).setClientRole(PairingRole.ROLE_INPUT)
            ).build())
            expect(Framing.read(inp, PairingMessage::parseFrom)) { it.hasPairingConfigurationAck() }

            PairingSession(socket, certs.clientCertificate(), serverCert)
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }

    private fun msg() = PairingMessage.newBuilder()
        .setProtocolVersion(2).setStatus(PairingStatus.STATUS_OK)

    private fun expect(m: PairingMessage, check: (PairingMessage) -> Boolean) {
        if (m.status != PairingStatus.STATUS_OK || !check(m)) {
            throw PairingException("TV rejected a pairing step (status ${m.status})")
        }
    }

    companion object { const val PAIRING_PORT = 6467 }
}

class PairingSession internal constructor(
    private val socket: SSLSocket,
    private val clientCert: X509Certificate,
    private val serverCert: X509Certificate,
) : Closeable {

    /** [code] is the 6 hex characters shown on the TV. Throws [PairingException] on mismatch. */
    suspend fun submitCode(code: String) = withContext(Dispatchers.IO) {
        val clean = code.trim().uppercase()
        if (clean.length != 6 || !clean.all { it in "0123456789ABCDEF" }) {
            throw PairingException("Enter the 6 characters (0-9, A-F) shown on the TV")
        }
        val codeBytes = ByteArray(3) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val secret = computeSecret(codeBytes)
        // The first code byte doubles as a checksum of the hash, so typos are caught locally.
        if (secret[0] != codeBytes[0]) throw PairingException("That code doesn't match. Check the TV and retry")

        Framing.write(socket.outputStream, PairingMessage.newBuilder()
            .setProtocolVersion(2).setStatus(PairingStatus.STATUS_OK)
            .setPairingSecret(PairingSecret.newBuilder().setSecret(ByteString.copyFrom(secret)))
            .build())
        val resp = Framing.read(socket.inputStream, PairingMessage::parseFrom)
        if (resp.status != PairingStatus.STATUS_OK || !resp.hasPairingSecretAck()) {
            throw PairingException("TV refused the secret (status ${resp.status})")
        }
    }

    // SHA-256( clientModulus | clientExponent | serverModulus | serverExponent | code[1..2] )
    private fun computeSecret(codeBytes: ByteArray): ByteArray {
        val c = clientCert.publicKey as RSAPublicKey
        val s = serverCert.publicKey as? RSAPublicKey
            ?: throw PairingException("TV certificate is not RSA")
        return MessageDigest.getInstance("SHA-256").run {
            update(c.modulus.unsigned()); update(c.publicExponent.unsigned())
            update(s.modulus.unsigned()); update(s.publicExponent.unsigned())
            update(codeBytes.copyOfRange(1, 3))
            digest()
        }
    }

    /** BigInteger.toByteArray() prepends a sign byte; the protocol hashes the minimal big-endian form. */
    private fun BigInteger.unsigned(): ByteArray {
        val b = toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    override fun close() { runCatching { socket.close() } }
}
