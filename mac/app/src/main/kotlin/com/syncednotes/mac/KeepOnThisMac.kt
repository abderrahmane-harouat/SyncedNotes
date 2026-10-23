package com.syncednotes.mac

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * What Privacy.md asks of the Mac app, done from the JVM: the Swift app's KeepOnThisMac.swift.
 *
 * Java has no AppKit, so these talk to the Objective-C runtime and to Carbon directly through
 * the FFM API: no compiled glue, nothing beyond what macOS itself ships. Each is small, and
 * each says why it is needed.
 */
object KeepOnThisMac {
    /**
     * Everything at once, once the first window is up. [allowCapture] is for a run from the
     * build on its own try-out notes only, so the app's look can be checked in a screenshot;
     * the app never allows it for the real notes (see Main.kt).
     */
    fun install(allowCapture: Boolean = false) {
        onMainThread {
            removeServicesMenu()
            if (!allowCapture) keepWindowsOutOfScreenshots()
        }
    }

    /**
     * Windows are kept out of screenshots and screen recording (`sharingType = .none`), sheets
     * and every later window included: called again whenever a window opens.
     */
    fun keepWindowsOutOfScreenshots() = onMainThread {
        val windows = ObjC.send(ObjC.send(ObjC.cls("NSApplication"), "sharedApplication"), "windows")
        val count = ObjC.sendLong(windows, "count")
        for (i in 0 until count) {
            ObjC.sendVoidLong(ObjC.sendWithLong(windows, "objectAtIndex:", i), "setSharingType:", 0 /* NSWindowSharingNone */)
        }
    }

    /**
     * Services get nothing: the Services menu would offer whatever is selected to any app that
     * asks. The JDK puts one in the app menu; it is taken out, and AppKit is told there is none.
     */
    private fun removeServicesMenu() {
        val app = ObjC.send(ObjC.cls("NSApplication"), "sharedApplication")
        val services = ObjC.send(app, "servicesMenu")
        ObjC.sendVoidPointer(app, "setServicesMenu:", MemorySegment.NULL)
        val main = ObjC.send(app, "mainMenu")
        if (main == MemorySegment.NULL || ObjC.sendLong(main, "numberOfItems") == 0L) return
        val appMenu = ObjC.send(ObjC.sendWithLong(main, "itemAtIndex:", 0), "submenu")
        if (appMenu == MemorySegment.NULL) return
        var i = ObjC.sendLong(appMenu, "numberOfItems") - 1
        while (i >= 0) {
            val item = ObjC.sendWithLong(appMenu, "itemAtIndex:", i)
            val sub = ObjC.send(item, "submenu")
            if ((services != MemorySegment.NULL && sub == services) || ObjC.string(ObjC.send(item, "title")) == "Services") {
                ObjC.sendVoidLong(appMenu, "removeItemAtIndex:", i)
                // The separator the JDK put after it goes too, so no two separators meet.
                if (i < ObjC.sendLong(appMenu, "numberOfItems") && ObjC.sendBool(ObjC.sendWithLong(appMenu, "itemAtIndex:", i), "isSeparatorItem")) {
                    ObjC.sendVoidLong(appMenu, "removeItemAtIndex:", i)
                }
            }
            i--
        }
    }

    /**
     * The windows' own frame (title bar, buttons, sheets) in light or dark, matching the app's
     * choice; null follows the Mac.
     */
    fun setAppearance(dark: Boolean?) = onMainThread {
        val appearance = if (dark == null) MemorySegment.NULL else Arena.ofConfined().use { arena ->
            ObjC.sendWithPointer(ObjC.cls("NSAppearance"), "appearanceNamed:", ObjC.nsString(if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua", arena))
        }
        val app = ObjC.send(ObjC.cls("NSApplication"), "sharedApplication")
        ObjC.sendVoidPointer(app, "setAppearance:", appearance)
    }

    /** Runs [block] on AppKit's main thread, which is not the window thread on the JVM. */
    fun onMainThread(block: () -> Unit) {
        MainThread.run(block)
    }
}

/**
 * Secure input while a passphrase field has focus: no other app can watch the keystrokes, as
 * with the Mac's own secure fields. The system counts each turn-on, so each needs exactly one
 * turn-off, or it stays on for the whole Mac after the app has gone.
 */
object SecureInput {
    private var held = false
    private val carbon by lazy { SymbolLookup.libraryLookup("/System/Library/Frameworks/Carbon.framework/Carbon", Arena.global()) }
    private val enable by lazy { downcall(carbon, "EnableSecureEventInput", FunctionDescriptor.of(ValueLayout.JAVA_INT)) }
    private val disable by lazy { downcall(carbon, "DisableSecureEventInput", FunctionDescriptor.of(ValueLayout.JAVA_INT)) }

    @Synchronized
    fun hold(needed: Boolean) {
        if (needed == held) return
        if (needed) enable.invoke() else disable.invoke()
        held = needed
    }
}

/**
 * The clipboard, for this Mac only. Everything the app copies is marked current-host-only, so
 * Universal Clipboard never carries it to another device (Privacy.md); AWT's own clipboard
 * cannot say so. [board] is the system's clipboard, except in tests, which use one of their own.
 */
class Pasteboard internal constructor(private val board: () -> MemorySegment) {
    fun copy(text: String) = Arena.ofConfined().use { arena ->
        val b = board()
        ObjC.sendLongReturnLong(b, "prepareForNewContentsWithOptions:", CURRENT_HOST_ONLY)
        ObjC.sendBoolTwoPointers(b, "setString:forType:", ObjC.nsString(text, arena), ObjC.nsString(PLAIN_TEXT, arena))
    }

    fun paste(): String? = string(PLAIN_TEXT)

    /**
     * What was copied as HTML, which browsers and Notion put beside the plain text: how a
     * paste keeps its formatting. Only read, here on the Mac; nothing it points to is fetched.
     */
    fun pasteHtml(): String? = string(HTML)

    private fun string(type: String): String? = Arena.ofConfined().use { arena ->
        ObjC.string(ObjC.sendWithPointer(board(), "stringForType:", ObjC.nsString(type, arena)))
    }

    /** Puts [html] on this pasteboard, as a browser would: for tests, on a private one. */
    internal fun copyHtml(html: String, text: String) = Arena.ofConfined().use { arena ->
        val b = board()
        ObjC.sendLongReturnLong(b, "prepareForNewContentsWithOptions:", CURRENT_HOST_ONLY)
        ObjC.sendBoolTwoPointers(b, "setString:forType:", ObjC.nsString(html, arena), ObjC.nsString(HTML, arena))
        ObjC.sendBoolTwoPointers(b, "setString:forType:", ObjC.nsString(text, arena), ObjC.nsString(PLAIN_TEXT, arena))
    }

    companion object {
        private const val CURRENT_HOST_ONLY = 1L
        private const val PLAIN_TEXT = "public.utf8-plain-text"
        private const val HTML = "public.html"

        /** The Mac's clipboard. */
        val general = Pasteboard { ObjC.send(ObjC.cls("NSPasteboard"), "generalPasteboard") }

        fun copy(text: String) = general.copy(text)
        fun paste(): String? = general.paste()
        fun pasteHtml(): String? = general.pasteHtml()

        /** A pasteboard of its own, which nothing else reads: for tests. */
        internal fun private(): Pasteboard {
            val board = ObjC.send(ObjC.cls("NSPasteboard"), "pasteboardWithUniqueName")
            return Pasteboard { board }
        }
    }
}

/** Just enough of the Objective-C runtime to send messages, through objc_msgSend. */
internal object ObjC {
    private val objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global())
    private val P = ValueLayout.ADDRESS
    private val J = ValueLayout.JAVA_LONG
    private val B = ValueLayout.JAVA_BOOLEAN
    private val getClass = downcall(objc, "objc_getClass", FunctionDescriptor.of(P, P))
    private val selector = downcall(objc, "sel_registerName", FunctionDescriptor.of(P, P))
    private val msgSend = objc.find("objc_msgSend").orElseThrow()
    private val handles = HashMap<FunctionDescriptor, MethodHandle>()
    private val selectors = HashMap<String, MemorySegment>()

    private fun handle(descriptor: FunctionDescriptor) = synchronized(handles) {
        handles.getOrPut(descriptor) { Linker.nativeLinker().downcallHandle(msgSend, descriptor) }
    }

    private fun sel(name: String): MemorySegment = synchronized(selectors) {
        selectors.getOrPut(name) { Arena.ofConfined().use { selector.invoke(it.allocateFrom(name)) as MemorySegment } }
    }

    fun cls(name: String): MemorySegment = Arena.ofConfined().use { getClass.invoke(it.allocateFrom(name)) as MemorySegment }

    fun send(receiver: MemorySegment, name: String): MemorySegment =
        handle(FunctionDescriptor.of(P, P, P)).invoke(receiver, sel(name)) as MemorySegment

    fun sendWithLong(receiver: MemorySegment, name: String, value: Long): MemorySegment =
        handle(FunctionDescriptor.of(P, P, P, J)).invoke(receiver, sel(name), value) as MemorySegment

    fun sendWithPointer(receiver: MemorySegment, name: String, value: MemorySegment): MemorySegment =
        handle(FunctionDescriptor.of(P, P, P, P)).invoke(receiver, sel(name), value) as MemorySegment

    fun sendLong(receiver: MemorySegment, name: String): Long =
        handle(FunctionDescriptor.of(J, P, P)).invoke(receiver, sel(name)) as Long

    fun sendLongReturnLong(receiver: MemorySegment, name: String, value: Long): Long =
        handle(FunctionDescriptor.of(J, P, P, J)).invoke(receiver, sel(name), value) as Long

    fun sendBool(receiver: MemorySegment, name: String): Boolean =
        handle(FunctionDescriptor.of(B, P, P)).invoke(receiver, sel(name)) as Boolean

    fun sendBoolTwoPointers(receiver: MemorySegment, name: String, a: MemorySegment, b: MemorySegment): Boolean =
        handle(FunctionDescriptor.of(B, P, P, P, P)).invoke(receiver, sel(name), a, b) as Boolean

    fun sendVoidLong(receiver: MemorySegment, name: String, value: Long) {
        handle(FunctionDescriptor.ofVoid(P, P, J)).invoke(receiver, sel(name), value)
    }

    fun sendVoidPointer(receiver: MemorySegment, name: String, value: MemorySegment) {
        handle(FunctionDescriptor.ofVoid(P, P, P)).invoke(receiver, sel(name), value)
    }

    /** An autoreleased NSString holding [text]. */
    fun nsString(text: String, arena: Arena): MemorySegment =
        sendWithPointer(cls("NSString"), "stringWithUTF8String:", arena.allocateFrom(text))

    /** The text of an NSString; null for nil. */
    fun string(ns: MemorySegment): String? {
        if (ns == MemorySegment.NULL) return null
        val utf8 = send(ns, "UTF8String")
        if (utf8 == MemorySegment.NULL) return null
        return utf8.reinterpret(Long.MAX_VALUE).getString(0)
    }
}

/**
 * AppKit's main thread. On the JVM the window thread is another, and AppKit must be touched
 * from its own; libdispatch's main queue gets there, with a Java function as the work.
 */
private object MainThread {
    private val system = SymbolLookup.libraryLookup("/usr/lib/libSystem.B.dylib", Arena.global())
    private val dispatchAsync = downcall(system, "dispatch_async_f", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
    // dispatch_get_main_queue() is a macro for the address of this symbol.
    private val mainQueue = system.find("_dispatch_main_q").orElseThrow()
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    private val work: MemorySegment = Linker.nativeLinker().upcallStub(
        MethodHandles.lookup().findStatic(MainThread::class.java, "drain", MethodType.methodType(Void.TYPE, MemorySegment::class.java)),
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
        Arena.global(),
    )

    @JvmStatic
    fun drain(@Suppress("UNUSED_PARAMETER") context: MemorySegment) {
        while (true) {
            val block = pending.poll() ?: return
            try {
                block()
            } catch (e: Throwable) {
                System.err.println("SyncedNotes: a step to keep notes on this Mac failed: $e")
            }
        }
    }

    fun run(block: () -> Unit) {
        pending.add(block)
        dispatchAsync.invoke(mainQueue, MemorySegment.NULL, work)
    }
}

private fun downcall(lookup: SymbolLookup, name: String, descriptor: FunctionDescriptor): MethodHandle =
    Linker.nativeLinker().downcallHandle(lookup.find(name).orElseThrow { IllegalStateException("macOS has no $name") }, descriptor)
