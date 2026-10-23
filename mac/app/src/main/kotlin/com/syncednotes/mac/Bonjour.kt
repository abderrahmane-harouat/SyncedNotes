package com.syncednotes.mac

import com.syncednotes.core.sync.Discovery
import com.syncednotes.core.sync.FoundDevice
import com.syncednotes.core.sync.SyncService
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandles
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * Finding the phone with Bonjour (Docs/Sync.md): macOS's own DNS Service Discovery, `dns_sd.h`
 * in libSystem, called through the FFM API as KeepOnThisMac calls AppKit. Multicast DNS on the
 * local network only; no Apple server is asked anything. The Mac announces itself as
 * `_syncednotes._tcp` and looks for `_syncednotes-phone._tcp`.
 *
 * Each operation gets a thread of its own that waits on the operation's socket and hands
 * replies to its callback, which is how dns_sd expects to be driven without a run loop.
 */
class BonjourDiscovery : Discovery {
    private var advertising: Operation? = null
    private var browsing: Operation? = null

    @Synchronized
    override fun advertise(port: Int, readyToPair: Boolean, name: String?) {
        stopAdvertising()
        advertising = Operation.start("announce") { ref ->
            Arena.ofConfined().use { arena ->
                DnsSd.register.invoke(
                    ref, 0, 0, arena.allocateFrom(SyncService.SERVICE_NAME), arena.allocateFrom(SyncService.MAC_SERVICE_TYPE),
                    MemorySegment.NULL, MemorySegment.NULL, java.lang.Short.reverseBytes(port.toShort()), 0.toShort(),
                    MemorySegment.NULL, DnsSd.registerReply, MemorySegment.NULL,
                ) as Int
            }
        }
    }

    @Synchronized
    override fun stopAdvertising() {
        advertising?.stop()
        advertising = null
    }

    @Synchronized
    override fun browse(found: (FoundDevice) -> Unit, lost: (String) -> Unit) {
        stopBrowsing()
        browsing = Operation.start("browse", onBrowse = { added, name, type, domain ->
            if (!added) lost(name)
            else thread(name = "SyncedNotes resolve", isDaemon = true) { resolve(name, type, domain)?.let(found) }
        }) { ref ->
            Arena.ofConfined().use { arena ->
                DnsSd.browse.invoke(ref, 0, 0, arena.allocateFrom(SyncService.PHONE_SERVICE_TYPE), MemorySegment.NULL, DnsSd.browseReply, MemorySegment.NULL) as Int
            }
        }
    }

    @Synchronized
    override fun stopBrowsing() {
        browsing?.stop()
        browsing = null
    }

    /** Where a phone found by name is, and what its announcement says. */
    private fun resolve(name: String, type: String, domain: String): FoundDevice? {
        var result: Triple<String, Int, Map<String, String>>? = null
        val operation = Operation.start("resolve", onResolve = { host, port, txt -> result = Triple(host, port, txt) }) { ref ->
            Arena.ofConfined().use { arena ->
                DnsSd.resolve.invoke(
                    ref, 0, 0, arena.allocateFrom(name), arena.allocateFrom(type), arena.allocateFrom(domain),
                    DnsSd.resolveReply, MemorySegment.NULL,
                ) as Int
            }
        } ?: return null
        val until = System.currentTimeMillis() + 5_000
        while (result == null && System.currentTimeMillis() < until) Thread.sleep(20)
        operation.stop()
        val (host, port, txt) = result ?: return null
        // The system resolver answers `.local` names by multicast DNS too.
        val address = runCatching { InetAddress.getAllByName(host).sortedBy { if (it is Inet4Address) 0 else 1 }.firstOrNull() }.getOrNull() ?: return null
        return FoundDevice(name, address, port, readyToPair = txt["pairing"] == "1", name = txt["name"])
    }

    /** One dns_sd operation and the thread that serves its replies. */
    private class Operation(private val ref: MemorySegment, val handler: Handler) {
        @Volatile private var running = true
        private val worker = thread(name = "SyncedNotes Bonjour", isDaemon = true) { serve() }

        private fun serve() {
            val fd = DnsSd.sockFd.invoke(ref) as Int
            Arena.ofConfined().use { arena ->
                val poll = arena.allocate(8)
                while (running) {
                    poll.set(JAVA_INT, 0, fd)
                    poll.set(JAVA_SHORT, 4, POLLIN)
                    poll.set(JAVA_SHORT, 6, 0)
                    val ready = DnsSd.poll.invoke(poll, 1, 250) as Int
                    if (!running) break
                    if (ready > 0) {
                        current.set(handler)
                        val error = DnsSd.processResult.invoke(ref) as Int
                        current.remove()
                        if (error != 0) break
                    }
                }
            }
            synchronized(this) { DnsSd.deallocate.invoke(ref) }
        }

        fun stop() {
            running = false
        }

        companion object {
            private const val POLLIN: Short = 1

            fun start(
                what: String,
                onBrowse: ((Boolean, String, String, String) -> Unit)? = null,
                onResolve: ((String, Int, Map<String, String>) -> Unit)? = null,
                call: (MemorySegment) -> Int,
            ): Operation? {
                val holder = Arena.global().allocate(ADDRESS)
                val error = call(holder)
                if (error != 0) {
                    System.err.println("Bonjour could not $what ($error)")
                    return null
                }
                return Operation(holder.get(ADDRESS, 0), Handler(onBrowse, onResolve))
            }
        }
    }

    private class Handler(
        val onBrowse: ((Boolean, String, String, String) -> Unit)?,
        val onResolve: ((String, Int, Map<String, String>) -> Unit)?,
    )

    /** dns_sd's functions, and the callbacks it calls back into, found once. */
    private object DnsSd {
        private val linker = Linker.nativeLinker()
        private val system = SymbolLookup.libraryLookup("/usr/lib/libSystem.B.dylib", Arena.global())

        private fun downcall(name: String, descriptor: FunctionDescriptor) =
            linker.downcallHandle(system.find(name).orElseThrow(), descriptor)

        val register = downcall(
            "DNSServiceRegister",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_SHORT, JAVA_SHORT, ADDRESS, ADDRESS, ADDRESS),
        )
        val browse = downcall("DNSServiceBrowse", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
        val resolve = downcall(
            "DNSServiceResolve",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
        )
        val sockFd = downcall("DNSServiceRefSockFD", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val processResult = downcall("DNSServiceProcessResult", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val deallocate = downcall("DNSServiceRefDeallocate", FunctionDescriptor.ofVoid(ADDRESS))
        val poll = downcall("poll", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT))

        private fun upcall(name: String, descriptor: FunctionDescriptor): MemorySegment {
            val method = MethodHandles.lookup().findStatic(Callbacks::class.java, name, descriptor.toMethodType())
            return linker.upcallStub(method, descriptor, Arena.global())
        }

        val registerReply = upcall("registered", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
        val browseReply = upcall("browsed", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
        val resolveReply = upcall(
            "resolved",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_SHORT, JAVA_SHORT, ADDRESS, ADDRESS),
        )
    }

    /** What dns_sd calls, on the operation's own thread, which says whose reply it is. */
    private object Callbacks {
        private const val FLAG_ADD = 0x2

        @JvmStatic
        fun registered(ref: MemorySegment, flags: Int, error: Int, name: MemorySegment, type: MemorySegment, domain: MemorySegment, context: MemorySegment) {}

        @JvmStatic
        fun browsed(
            ref: MemorySegment, flags: Int, index: Int, error: Int,
            name: MemorySegment, type: MemorySegment, domain: MemorySegment, context: MemorySegment,
        ) {
            if (error != 0) return
            current.get()?.onBrowse?.invoke(flags and FLAG_ADD != 0, name.text(), type.text(), domain.text())
        }

        @JvmStatic
        fun resolved(
            ref: MemorySegment, flags: Int, index: Int, error: Int, fullName: MemorySegment, host: MemorySegment,
            port: Short, txtLength: Short, txt: MemorySegment, context: MemorySegment,
        ) {
            if (error != 0) return
            val length = txtLength.toInt() and 0xFFFF
            val record = if (length == 0) ByteArray(0) else txt.reinterpret(length.toLong()).toArray(ValueLayout.JAVA_BYTE)
            current.get()?.onResolve?.invoke(host.text(), java.lang.Short.reverseBytes(port).toInt() and 0xFFFF, txtRecord(record))
        }
    }

    companion object {
        private val current = ThreadLocal<Handler>()

        private fun MemorySegment.text(): String = reinterpret(Long.MAX_VALUE).getString(0)

        /** A TXT record: strings each preceded by its length, `key=value`. */
        fun txtRecord(bytes: ByteArray): Map<String, String> {
            val entries = mutableMapOf<String, String>()
            var i = 0
            while (i < bytes.size) {
                val length = bytes[i].toInt() and 0xFF
                val end = minOf(bytes.size, i + 1 + length)
                val entry = String(bytes, i + 1, end - i - 1, Charsets.UTF_8)
                entries[entry.substringBefore('=')] = entry.substringAfter('=', "")
                i = end
            }
            return entries
        }
    }
}

/**
 * The Mac's name as the phone shows it when pairing: the one in System Settings (Sharing),
 * from SystemConfiguration, else its network name. Asked once.
 */
fun computerName(): String = ComputerName.value

private object ComputerName {
    val value: String by lazy { fromSystemConfiguration() ?: fromHostName() ?: "Mac" }

    private fun fromSystemConfiguration(): String? = runCatching {
        val linker = Linker.nativeLinker()
        val configuration = SymbolLookup.libraryLookup("/System/Library/Frameworks/SystemConfiguration.framework/SystemConfiguration", Arena.global())
        val foundation = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", Arena.global())
        val copyName = linker.downcallHandle(configuration.find("SCDynamicStoreCopyComputerName").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
        val getCString = linker.downcallHandle(foundation.find("CFStringGetCString").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ADDRESS, ADDRESS, ValueLayout.JAVA_LONG, JAVA_INT))
        val release = linker.downcallHandle(foundation.find("CFRelease").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS))
        val name = copyName.invoke(MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
        if (name == MemorySegment.NULL) return null
        try {
            Arena.ofConfined().use { arena ->
                val buffer = arena.allocate(512)
                // kCFStringEncodingUTF8
                if ((getCString.invoke(name, buffer, 512L, 0x08000100) as Byte).toInt() == 0) null else buffer.getString(0)
            }
        } finally {
            release.invoke(name)
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun fromHostName(): String? = runCatching {
        Arena.ofConfined().use { arena ->
            val linker = Linker.nativeLinker()
            val gethostname = linker.downcallHandle(linker.defaultLookup().find("gethostname").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ValueLayout.JAVA_LONG))
            val buffer = arena.allocate(256)
            if (gethostname.invoke(buffer, 256L) as Int != 0) null else buffer.getString(0).removeSuffix(".local").replace('-', ' ')
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
