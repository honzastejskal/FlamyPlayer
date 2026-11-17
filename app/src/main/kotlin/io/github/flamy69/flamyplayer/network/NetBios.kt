package io.github.flamy69.flamyplayer.network

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.Locale
import kotlin.random.Random

/**
 * The NetBIOS name service: how Windows machines and most NAS boxes find each other by name on
 * a home network that has no DNS entries for them. Android does not speak it, so a name like
 * `LIVINGROOM-PC` means nothing to the system resolver.
 *
 * Two requests are needed here: "who is called X?" (sent to everybody on the network) and
 * "what are you called?" (sent to one address).
 */
object NetBios {

    private const val PORT = 137
    private const val TYPE_NAME = 0x20
    private const val TYPE_STATUS = 0x21
    private const val FLAG_RESPONSE = 0x8000
    private const val FLAG_RECURSION_DESIRED = 0x0100
    private const val FLAG_BROADCAST = 0x0010
    private const val RESULT_CODE_MASK = 0x000F
    private const val NAME_FLAG_GROUP = 0x8000

    /** Marks the name under which a machine offers its shared folders. */
    private const val SUFFIX_FILE_SERVER = 0x20
    private const val SUFFIX_WORKSTATION = 0x00
    private const val SUFFIX_BROWSE_MASTER = 0x01
    private const val BROWSE_MASTER = "\u0001\u0002__MSBROWSE__\u0002"

    private const val NAME_LENGTH = 15
    private const val STATUS_ENTRY_SIZE = 18

    /** Whether [host] can be a NetBIOS name at all: short, one word, plain characters. */
    fun isValidName(host: String): Boolean =
        host.length in 1..NAME_LENGTH && host.all { it.code in 0x21..0x7E && it != '.' }

    /**
     * Asks the whole network which machine is called [name]. Returns its address, or `null` if
     * nobody answered. Blocks for up to [attempts] × [timeoutMs].
     */
    fun resolve(name: String, broadcast: InetAddress, timeoutMs: Int = 400, attempts: Int = 2): InetAddress? {
        if (!isValidName(name)) return null
        return exchange(broadcast, timeoutMs, attempts, broadcastQuery = true, { id -> nameQuery(id, name) }) { packet, id ->
            if (isNameResponse(packet.data, packet.length, id)) packet.address else null
        }
    }

    /**
     * Asks the whole network for its browse masters: in every workgroup one machine, often the
     * NAS, keeps the list of the others and answers to a special name. Returns all that answered
     * within [timeoutMs].
     */
    fun browseMasters(broadcast: InetAddress, timeoutMs: Int = 600): List<InetAddress> {
        val found = LinkedHashSet<InetAddress>()
        exchange<Unit>(broadcast, timeoutMs, attempts = 1, broadcastQuery = true, { id ->
            query(id, encodeName(BROWSE_MASTER, SUFFIX_BROWSE_MASTER), TYPE_NAME, FLAG_BROADCAST)
        }) { packet, id ->
            if (isNameResponse(packet.data, packet.length, id)) found += packet.address
            null // Keep listening for more.
        }
        return found.toList()
    }

    /** Asks the machine at [address] for its name. Returns `null` if it does not answer. */
    fun nameOf(address: InetAddress, timeoutMs: Int = 500): String? =
        exchange(address, timeoutMs, attempts = 1, broadcastQuery = false, ::statusQuery) { packet, id ->
            statusName(packet.data, packet.length, id)
        }

    private fun <T> exchange(
        target: InetAddress,
        timeoutMs: Int,
        attempts: Int,
        broadcastQuery: Boolean,
        query: (transactionId: Int) -> ByteArray,
        parse: (DatagramPacket, transactionId: Int) -> T?,
    ): T? = try {
        DatagramSocket().use { socket ->
            socket.broadcast = broadcastQuery
            repeat(attempts) {
                val transactionId = Random.nextInt(0x10000)
                val request = query(transactionId)
                socket.send(DatagramPacket(request, request.size, target, PORT))

                val deadline = System.nanoTime() + timeoutMs * 1_000_000L
                val buffer = ByteArray(1024)
                while (true) {
                    val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                    if (remainingMs <= 0) break
                    socket.soTimeout = remainingMs
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    parse(packet, transactionId)?.let { return it }
                }
            }
            null
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    internal fun nameQuery(transactionId: Int, name: String): ByteArray =
        query(transactionId, encodeName(name, SUFFIX_FILE_SERVER), TYPE_NAME, FLAG_RECURSION_DESIRED or FLAG_BROADCAST)

    internal fun statusQuery(transactionId: Int): ByteArray {
        // The wildcard name: an asterisk followed by zero bytes.
        val wildcard = ByteArray(NAME_LENGTH + 1).also { it[0] = '*'.code.toByte() }
        return query(transactionId, encode(wildcard), TYPE_STATUS, 0)
    }

    /** A name padded with spaces to 15 characters plus the suffix byte, in the wire encoding. */
    internal fun encodeName(name: String, suffix: Int): ByteArray {
        val raw = ByteArray(NAME_LENGTH + 1) { ' '.code.toByte() }
        name.uppercase(Locale.ROOT).take(NAME_LENGTH).forEachIndexed { index, char -> raw[index] = char.code.toByte() }
        raw[NAME_LENGTH] = suffix.toByte()
        return encode(raw)
    }

    /** Every byte becomes two letters from A to P, one per half byte; framed by length and zero. */
    private fun encode(raw: ByteArray): ByteArray {
        val encoded = ByteArray(raw.size * 2 + 2)
        encoded[0] = (raw.size * 2).toByte()
        raw.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            encoded[1 + index * 2] = ('A'.code + (value shr 4)).toByte()
            encoded[2 + index * 2] = ('A'.code + (value and 0x0F)).toByte()
        }
        return encoded
    }

    private fun query(transactionId: Int, encodedName: ByteArray, type: Int, flags: Int): ByteArray {
        val packet = ByteArray(12 + encodedName.size + 4)
        putShort(packet, 0, transactionId)
        putShort(packet, 2, flags)
        putShort(packet, 4, 1) // one question
        encodedName.copyInto(packet, 12)
        putShort(packet, 12 + encodedName.size, type)
        putShort(packet, 14 + encodedName.size, 1) // class: internet
        return packet
    }

    internal fun isNameResponse(packet: ByteArray, length: Int, transactionId: Int): Boolean =
        answerData(packet, length, transactionId, TYPE_NAME) >= 0

    /** The name a machine reports for itself: the one it serves files under, if it has one. */
    internal fun statusName(packet: ByteArray, length: Int, transactionId: Int): String? {
        val data = answerData(packet, length, transactionId, TYPE_STATUS)
        if (data < 0) return null
        val count = packet[data].toInt() and 0xFF
        var workstation: String? = null
        for (i in 0 until count) {
            val entry = data + 1 + i * STATUS_ENTRY_SIZE
            if (entry + STATUS_ENTRY_SIZE > length) break
            // Group names are workgroups and domains, not the machine itself.
            if ((getShort(packet, entry + NAME_LENGTH + 1) and NAME_FLAG_GROUP) != 0) continue
            // Names are padded with spaces; some devices pad with zero bytes instead.
            val name = String(packet, entry, NAME_LENGTH, Charsets.US_ASCII).trim { it == ' ' || it == '\u0000' }
            if (name.isEmpty()) continue
            when (packet[entry + NAME_LENGTH].toInt() and 0xFF) {
                SUFFIX_FILE_SERVER -> return name
                SUFFIX_WORKSTATION -> if (workstation == null) workstation = name
            }
        }
        return workstation
    }

    /** Offset of the data of the first answer if [packet] is a positive answer of [type], else -1. */
    private fun answerData(packet: ByteArray, length: Int, transactionId: Int, type: Int): Int {
        if (length < 12 || getShort(packet, 0) != transactionId) return -1
        val flags = getShort(packet, 2)
        if ((flags and FLAG_RESPONSE) == 0 || (flags and RESULT_CODE_MASK) != 0) return -1
        if (getShort(packet, 6) < 1) return -1 // no answers

        var offset = 12
        repeat(getShort(packet, 4)) { // questions, normally absent from answers
            offset = skipName(packet, length, offset)
            if (offset < 0) return -1
            offset += 4
        }
        offset = skipName(packet, length, offset)
        if (offset < 0 || offset + 10 > length) return -1
        if (getShort(packet, offset) != type) return -1
        val dataLength = getShort(packet, offset + 8)
        val data = offset + 10
        return if (dataLength > 0 && data + dataLength <= length) data else -1
    }

    private fun skipName(packet: ByteArray, length: Int, start: Int): Int {
        var offset = start
        while (offset < length) {
            val labelLength = packet[offset].toInt() and 0xFF
            if (labelLength == 0) return offset + 1
            if ((labelLength and 0xC0) == 0xC0) return offset + 2 // pointer to an earlier name
            offset += 1 + labelLength
        }
        return -1
    }

    private fun getShort(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)

    private fun putShort(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value shr 8).toByte()
        packet[offset + 1] = value.toByte()
    }
}
