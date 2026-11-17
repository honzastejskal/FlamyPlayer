package io.github.flamy69.flamyplayer.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/** IPv4 address arithmetic for the network the phone is connected to. */
object Subnet {

    private fun mask(prefixLength: Int): Int = if (prefixLength <= 0) 0 else -1 shl (32 - prefixLength)

    /** The address that reaches every machine of the network. */
    fun broadcast(address: Int, prefixLength: Int): Int = address or mask(prefixLength).inv()

    /**
     * The addresses worth trying when looking for other machines. Home networks have at most
     * 254 of them; on a bigger network only the phone's own neighborhood of that size is used.
     */
    fun neighbors(address: Int, prefixLength: Int): List<Int> {
        val prefix = prefixLength.coerceAtLeast(24)
        if (prefix > 30) return emptyList()
        val network = address and mask(prefix)
        val size = 1 shl (32 - prefix)
        return (1 until size - 1).map { network + it }.filter { it != address }
    }

    /** Whether [other] lies in the network of [address]. */
    fun contains(address: Int, prefixLength: Int, other: Int): Boolean =
        (address and mask(prefixLength)) == (other and mask(prefixLength))

    /** The 254 addresses of the home sized neighborhood that [address] is in, itself included. */
    fun block(address: Int): List<Int> {
        val network = address and mask(BLOCK_PREFIX)
        return (1 until (1 shl (32 - BLOCK_PREFIX)) - 1).map { network + it }
    }

    private const val BLOCK_PREFIX = 24

    fun toInt(address: Inet4Address): Int =
        address.address.fold(0) { result, byte -> (result shl 8) or (byte.toInt() and 0xFF) }

    fun toAddress(value: Int): InetAddress = InetAddress.getByAddress(
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()),
    )
}

/** A computer or NAS on the local network that offers shared folders. */
class FoundDevice(
    /** The name it calls itself, if it told us. */
    val name: String?,
    val address: String,
) {
    /** What to save as the address: the name survives the device getting a new IP address. */
    val host: String get() = name ?: address

    /** Whether [host], as saved with a network folder, means this device. */
    fun isCalled(host: String): Boolean = host == address || host.equals(name, ignoreCase = true)
}

/** What a search of the local network turned up. */
class NetworkScan(val devices: List<FoundDevice>, val mediaServers: List<MediaServer>)

/**
 * Knows the phone's Wi-Fi network: finds the machines on it that share folders or stream
 * media, and turns their names into addresses.
 */
class LocalNetworkScanner(context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Name → address of machines seen recently, for when a name cannot be looked up again. */
    private val known = ConcurrentHashMap<String, String>()

    /**
     * Looks for machines that share folders and for media servers. Returns `null` when the
     * phone is not on Wi-Fi (or a wired network). Takes three to four seconds.
     *
     * Two searches run side by side: the file sharing port of every address around the phone
     * is tried, and the network is asked who streams media, the way a TV finds its sources.
     * Machines that answer the second question get a second try at the first.
     */
    suspend fun scan(): NetworkScan? {
        val wifi = wifi() ?: return null
        val own = Subnet.toAddress(wifi.address)
        val neighbors = Subnet.neighbors(wifi.address, wifi.prefixLength).map(Subnet::toAddress)
        val found = withContext(Dispatchers.IO) {
            val far = async { farNeighbors(wifi) }
            val streaming = async {
                Ssdp.mediaServers(neighbors) { socket ->
                    try {
                        wifi.network.bindSocket(socket)
                    } catch (_: IOException) {
                        // Then the question goes out over whatever network Android prefers.
                    }
                }
            }
            val sharing = PortScan.open(neighbors, SMB_PORT, PROBE_TIMEOUT_MS) +
                PortScan.open(far.await(), SMB_PORT, PROBE_TIMEOUT_MS)
            val answers = streaming.await()
            val servers = answers.map { answer -> async { mediaServer(answer) } }
            val missed = answers.map { it.address }.distinct().filter { it != own && it !in sharing }
            val devices = (sharing + PortScan.open(missed, SMB_PORT, PROBE_TIMEOUT_MS))
                .map { target -> async { describe(target) } }
            NetworkScan(devices.awaitAll(), servers.awaitAll().distinctBy { it.id })
        }
        for (device in found.devices) {
            device.name?.let { known[it.uppercase()] = device.address }
        }
        return found
    }

    /**
     * On a network bigger than a home one, only the neighborhood of 254 addresses around the
     * device is tried, and a NAS elsewhere in it is missed. Browse masters, often the NAS
     * itself, answer from anywhere in the network: their neighborhoods are tried as well.
     */
    private fun farNeighbors(wifi: Wifi): List<InetAddress> {
        if (wifi.prefixLength >= FAR_PREFIX_LIMIT) return emptyList()
        val broadcast = Subnet.toAddress(Subnet.broadcast(wifi.address, wifi.prefixLength))
        val own = Subnet.block(wifi.address).toSet()
        return NetBios.browseMasters(broadcast)
            .filterIsInstance<Inet4Address>()
            .map(Subnet::toInt)
            .filter { it !in own && Subnet.contains(wifi.address, wifi.prefixLength, it) }
            .flatMap(Subnet::block)
            .distinct()
            .take(FAR_ADDRESSES_MAX)
            .map(Subnet::toAddress)
    }

    private fun mediaServer(answer: Ssdp.Answer): MediaServer {
        val address = answer.address.hostAddress.orEmpty()
        return try {
            Dlna.describe(answer.location, address)
        } catch (_: IOException) {
            // Listed all the same; opening it tries again and says what is wrong.
            MediaServer(answer.location, answer.location, address, address, controlUrl = null, serviceType = null)
        }
    }

    private fun describe(target: InetAddress): FoundDevice {
        val address = target.hostAddress.orEmpty()
        // A machine that keeps its name to itself may still be one that was connected to by name.
        val name = NetBios.nameOf(target) ?: known.entries.firstOrNull { it.value == address }?.key
        return FoundDevice(name, address)
    }

    /**
     * Turns what the user typed as the host into something a socket can connect to. Tries, in
     * this order: a literal IP address, the Windows name service, DNS, and the `.local` name.
     */
    @Throws(UnknownHostException::class)
    fun resolve(host: String): String {
        if (IPV4.matches(host)) return host

        if (NetBios.isValidName(host)) {
            val broadcast = wifi()?.let { Subnet.toAddress(Subnet.broadcast(it.address, it.prefixLength)) }
                ?: Subnet.toAddress(-1)
            NetBios.resolve(host, broadcast)?.hostAddress?.let { address ->
                known[host.uppercase()] = address
                return address
            }
        }

        val names = if ('.' in host) listOf(host) else listOf(host, "$host.local")
        for (name in names) {
            try {
                val addresses = InetAddress.getAllByName(name)
                // An IPv4 address goes into the share path as is; other answers keep the name.
                return addresses.firstOrNull { it is Inet4Address }?.hostAddress ?: name
            } catch (_: UnknownHostException) {
            }
        }
        return known[host.uppercase()] ?: throw UnknownHostException(host)
    }

    /** The local network of the phone: its IPv4 address there and the length of the prefix. */
    private class Wifi(val network: Network, val address: Int, val prefixLength: Int)

    /** The Wi-Fi or Ethernet network the phone is connected to. */
    private fun wifi(): Wifi? {
        val manager = connectivity ?: return null
        @Suppress("DEPRECATION") // The replacement only reports the default network, which may be a VPN.
        for (network in manager.allNetworks) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue
            val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!local) continue
            val link = manager.getLinkProperties(network)?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address }
                ?: continue
            return Wifi(network, Subnet.toInt(link.address as Inet4Address), link.prefixLength)
        }
        return null
    }

    private companion object {
        const val SMB_PORT = 445

        /** Long enough for a sleepy Wi-Fi device, whose address is asked for again after a second. */
        const val PROBE_TIMEOUT_MS = 1500

        /** Networks of this size or smaller are tried in full anyway. */
        const val FAR_PREFIX_LIMIT = 24

        /** Two more neighborhoods at most, so that the search does not take much longer. */
        const val FAR_ADDRESSES_MAX = 2 * 254
        val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }
}
