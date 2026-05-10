package com.piratedroid.terminal

import android.content.Context
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // ─── UI ──────────────────────────────────────────────────────────
    private lateinit var terminalOutput: TextView
    private lateinit var commandInput:   EditText
    private lateinit var scrollView:     ScrollView
    private lateinit var btnExecute:     Button
    private lateinit var btnGuide:       Button
    private lateinit var btnQuickDump:   Button
    private lateinit var btnReboot:      Button
    private lateinit var btnToggleTheme: Button

    // ─── Extra Keys ──────────────────────────────────────────────────
    private lateinit var btnKeyCtrl:  Button
    private lateinit var btnKeyAlt:   Button
    private lateinit var btnKeyEsc:   Button
    private lateinit var btnKeyTab:   Button
    private lateinit var btnKeyUp:    Button
    private lateinit var btnKeyDown:  Button
    private lateinit var btnKeyLeft:  Button
    private lateinit var btnKeyRight: Button
    private lateinit var btnKeyHome:  Button
    private lateinit var btnKeyEnd:   Button
    private lateinit var btnKeyPgUp:  Button
    private lateinit var btnKeyPgDn:  Button

    // ─── Shell ───────────────────────────────────────────────────────
    private var shellProcess: Process?       = null
    private var shellWriter:  BufferedWriter? = null
    private val executor     = Executors.newCachedThreadPool()
    private val mainHandler  = Handler(Looper.getMainLooper())

    // ─── State ───────────────────────────────────────────────────────
    private var isDarkMode     = true
    private var isRooted       = false
    private val commandHistory = mutableListOf<String>()
    private var historyIndex   = 0

    // ─── Autocomplete ────────────────────────────────────────────────
    private val autocompleteCache = mutableListOf<String>()

    // ─── Colors ──────────────────────────────────────────────────────
    private val C_GREEN  = 0xFF00FF41.toInt()
    private val C_RED    = 0xFFFF3131.toInt()
    private val C_YELLOW = 0xFFFFD700.toInt()
    private val C_CYAN   = 0xFF00FFFF.toInt()
    private val C_WHITE  = 0xFFE0E0E0.toInt()
    private val C_ORANGE = 0xFFFF8C00.toInt()

    // ════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        setContentView(R.layout.activity_main)
        bindViews()
        applyDarkTheme()
        printBootSequence()
        initShell()
        checkBatteryLevel()
        setupListeners()
        openKeyboardWithDelay()
        checkRootAsync()
        buildAutocompleteCache()
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyShell()
        executor.shutdownNow()
    }

    // ════════════════════════════════════════════════════════════════
    // AUTOCOMPLETE
    // ════════════════════════════════════════════════════════════════

    private fun buildAutocompleteCache() {
        executor.execute {
            val bins = mutableListOf<String>()
            val searchDirs = listOf(
                "/system/bin", "/system/xbin",
                "/sbin", "/vendor/bin",
                filesDir.absolutePath
            )
            searchDirs.forEach { dir ->
                try {
                    File(dir).listFiles()?.forEach { f ->
                        if (f.canExecute()) bins.add(f.name)
                    }
                } catch (_: Exception) {}
            }
            // Dodajemy też built-in komendy shella
            bins.addAll(listOf(
                "cd", "ls", "pwd", "echo", "cat", "grep", "find",
                "chmod", "chown", "mkdir", "rm", "mv", "cp", "touch",
                "ps", "kill", "top", "df", "du", "mount", "umount",
                "reboot", "su", "sh", "exit", "clear", "env", "export",
                "which", "uname", "id", "whoami", "date", "uptime",
                "getprop", "setprop", "logcat", "dmesg", "netstat",
                "ping", "ifconfig", "ip", "wget", "curl", "tar",
                "unzip", "zip", "dd", "hexdump", "strings"
            ))
            bins.sort()
            mainHandler.post {
                autocompleteCache.clear()
                autocompleteCache.addAll(bins.distinct())
                printColored("[TAB] Autocomplete ready — ${autocompleteCache.size} commands indexed.\n\n", C_CYAN)
            }
        }
    }

    private fun handleTabComplete() {
        val input = commandInput.text.toString()
        val cursorPos = commandInput.selectionStart

        // Wyciągamy ostatnie słowo przed kursorem
        val beforeCursor = input.substring(0, cursorPos)
        val lastWord = beforeCursor.substringAfterLast(" ")

        if (lastWord.isEmpty()) {
            // Pokaż wszystkie komendy jeśli input pusty
            printColored("[TAB] ${autocompleteCache.size} commands available. Type something first.\n", C_YELLOW)
            return
        }

        // Szukamy w ścieżkach plików jeśli lastWord zawiera /
        if (lastWord.contains("/")) {
            handlePathComplete(input, beforeCursor, lastWord)
            return
        }

        // Szukamy w cache komend
        val matches = autocompleteCache.filter { it.startsWith(lastWord) }

        when {
            matches.isEmpty() -> {
                printColored("[TAB] No match for: $lastWord\n", C_RED)
            }
            matches.size == 1 -> {
                // Jeden wynik — uzupełniamy
                val completed = beforeCursor.dropLast(lastWord.length) + matches[0] + " "
                commandInput.setText(completed + input.substring(cursorPos))
                commandInput.setSelection(completed.length)
            }
            else -> {
                // Wiele wyników — pokazujemy listę i uzupełniamy wspólny prefix
                val commonPrefix = matches.reduce { a, b ->
                    a.commonPrefixWith(b)
                }
                if (commonPrefix.length > lastWord.length) {
                    val completed = beforeCursor.dropLast(lastWord.length) + commonPrefix
                    commandInput.setText(completed + input.substring(cursorPos))
                    commandInput.setSelection(completed.length)
                }
                // Wyświetlamy opcje
                printColored("[TAB] ${matches.size} matches:\n", C_YELLOW)
                val row = matches.chunked(4).joinToString("\n") { chunk ->
                    chunk.joinToString("  ") { it.padEnd(16) }
                }
                printColored("$row\n\n", C_WHITE)
            }
        }
    }

    private fun handlePathComplete(input: String, beforeCursor: String, lastWord: String) {
        val dir = if (lastWord.contains("/")) {
            lastWord.substringBeforeLast("/").let {
                if (it.isEmpty()) "/" else it
            }
        } else "."
        val prefix = lastWord.substringAfterLast("/")

        try {
            val matches = File(dir).listFiles()
                ?.filter { it.name.startsWith(prefix) }
                ?.map { if (it.isDirectory) it.name + "/" else it.name }
                ?.sorted() ?: emptyList()

            when {
                matches.isEmpty() -> printColored("[TAB] No match in $dir\n", C_RED)
                matches.size == 1 -> {
                    val completed = beforeCursor.dropLast(prefix.length) + matches[0]
                    commandInput.setText(completed + input.substring(beforeCursor.length))
                    commandInput.setSelection(completed.length)
                }
                else -> {
                    val commonPrefix = matches.reduce { a, b -> a.commonPrefixWith(b) }
                    if (commonPrefix.length > prefix.length) {
                        val completed = beforeCursor.dropLast(prefix.length) + commonPrefix
                        commandInput.setText(completed + input.substring(beforeCursor.length))
                        commandInput.setSelection(completed.length)
                    }
                    printColored("[TAB] ${matches.size} matches in $dir:\n", C_YELLOW)
                    val row = matches.chunked(3).joinToString("\n") { chunk ->
                        chunk.joinToString("  ") { it.padEnd(20) }
                    }
                    printColored("$row\n\n", C_WHITE)
                }
            }
        } catch (e: Exception) {
            printColored("[TAB] Error: ${e.message}\n", C_RED)
        }
    }

    // ════════════════════════════════════════════════════════════════
    // BOOT SEQUENCE
    // ════════════════════════════════════════════════════════════════

    private fun printBootSequence() {
        printColored("""
 ██████╗ ██╗██████╗  █████╗ ████████╗███████╗
 ██╔══██╗██║██╔══██╗██╔══██╗╚══██╔══╝██╔════╝
 ██████╔╝██║██████╔╝███████║   ██║   █████╗  
 ██╔═══╝ ██║██╔══██╗██╔══██║   ██║   ██╔══╝  
 ██║     ██║██║  ██║██║  ██║   ██║   ███████╗
 ╚═╝     ╚═╝╚═╝  ╚═╝╚═╝  ╚═╝   ╚═╝   ╚══════╝
 ██████╗ ██████╗  ██████╗ ██╗██████╗
 ██╔══██╗██╔══██╗██╔═══██╗██║██╔══██╗
 ██║  ██║██████╔╝██║   ██║██║██║  ██║
 ██║  ██║██╔══██╗██║   ██║██║██║  ██║
 ██████╔╝██║  ██║╚██████╔╝██║██████╔╝
 ╚═════╝ ╚═╝  ╚═╝ ╚═════╝ ╚═╝╚═════╝
 PirateDroid Shell & Root Checker
 ⚓ "Knowledge is the only treasure you cannot lose." ⚓

""", C_GREEN)
        printColored("[BOOT] Initializing...\n", C_CYAN)
    }

    // ════════════════════════════════════════════════════════════════
    // ROOT DETECTION
    // ════════════════════════════════════════════════════════════════

    private fun checkRootAsync() {
        printColored("[ROOT] Querying OS for su permissions...\n", C_CYAN)
        executor.execute {
            val suPaths = listOf(
                "/system/xbin/su", "/system/bin/su", "/sbin/su",
                "/su/bin/su", "/magisk/.core/bin/su",
                "/data/adb/ksu/bin/su", "/data/adb/magisk/su"
            )
            val staticFound = suPaths.firstOrNull { File(it).exists() }
            var dynamicUid0   = false
            var dynamicOutput = ""
            var dynamicError  = ""
            try {
                val proc = ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(false).start()
                val finished = proc.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)
                dynamicOutput = proc.inputStream.bufferedReader().readText().trim()
                dynamicError  = proc.errorStream.bufferedReader().readText().trim()
                if (finished && dynamicOutput.contains("uid=0")) dynamicUid0 = true
            } catch (e: Exception) {
                dynamicError = e.message ?: "Exception: unknown error"
            }
            isRooted = dynamicUid0
            mainHandler.post {
                showRootBanner(staticFound, dynamicUid0, dynamicOutput, dynamicError)
            }
        }
    }

    private fun showRootBanner(
        staticPath: String?, dynamicUid0: Boolean,
        dynamicOutput: String, dynamicError: String
    ) {
        if (dynamicUid0) {
            printColored("""
╔══════════════════════════════════════════════════╗
║                                                  ║
║   ☠   SYSTEM HIJACKED — ROOT CONFIRMED   ☠      ║
║                                                  ║
║   Congratulations, Captain!                      ║
║   OS accepted su without blinking.               ║
║                                                  ║
║   Binary: ${'$'}{staticPath ?: "Magisk internal (su in PATH)"}
║   id Result: $dynamicOutput
║                                                  ║
║   Full control over the ship belongs to you.     ║
║   ⚠  Knox Counter might be already tripped.      ║
║   ⚠  With great power comes great responsibility ║
║                                                  ║
╚══════════════════════════════════════════════════╝
""", C_RED)
            printColored("[ROOT] Restarting shell with root privileges...\n", C_YELLOW)
            initShellAs(useRoot = true)
        } else {
            printColored("""
┌──────────────────────────────────────────────────┐
│   ⚓  ROOT FAILED                                 │
│   OS rejected su or did not respond.             │
└──────────────────────────────────────────────────┘
""", C_YELLOW)
            printColored("── Diagnosis ──────────────────────────────────\n", C_ORANGE)
            if (staticPath == null) {
                printColored("✗ No su binary in known paths.\n", C_RED)
                printColored("  → Phone is not rooted, OR\n", C_WHITE)
                printColored("  → Magisk / KernelSU not installed.\n\n", C_WHITE)
            } else {
                printColored("✓ su binary found: $staticPath\n", C_GREEN)
                printColored("  → But OS did not execute it correctly.\n\n", C_ORANGE)
            }
            if (dynamicError.isNotEmpty()) {
                printColored("✗ OS Error: $dynamicError\n", C_RED)
                when {
                    dynamicError.contains("Permission denied", ignoreCase = true) ->
                        printColored("  → SELinux Enforcing blocks su.\n    Check: adb shell getenforce\n", C_YELLOW)
                    dynamicError.contains("not found", ignoreCase = true) ->
                        printColored("  → su does not exist in system PATH.\n", C_YELLOW)
                    dynamicError.contains("No such file", ignoreCase = true) ->
                        printColored("  → su not installed.\n", C_YELLOW)
                    else ->
                        printColored("  → Unknown error — check Magisk logs.\n", C_YELLOW)
                }
                printColored("\n", C_WHITE)
            }
            if (dynamicOutput.isNotEmpty() && !dynamicUid0) {
                printColored("✗ su responded, but uid != 0: $dynamicOutput\n", C_ORANGE)
                printColored("  → Fake su, or partial root.\n\n", C_YELLOW)
            }
            if (dynamicError.isEmpty() && dynamicOutput.isEmpty()) {
                printColored("✗ No response (timeout 4s).\n", C_RED)
                printColored("  Possible causes:\n", C_YELLOW)
                printColored("  1. Magisk requires TAP in Magisk app\n", C_WHITE)
                printColored("  2. init_boot.img not patched by Magisk\n", C_WHITE)
                printColored("  3. Knox blocks su execution\n", C_WHITE)
                printColored("  4. SELinux in Enforcing mode\n", C_WHITE)
                printColored("  5. Reboot after Magisk installation not done\n\n", C_WHITE)
            }
            printColored("──────────────────────────────────────────────\n", C_ORANGE)
            printColored("Acting as a common sailor. ⚓\n\n", C_YELLOW)
        }
    }

    // ════════════════════════════════════════════════════════════════
    // SHELL ENGINE
    // ════════════════════════════════════════════════════════════════

    private fun initShell() = initShellAs(useRoot = false)

    private fun initShellAs(useRoot: Boolean) {
        try {
            destroyShell()
            val binary = if (useRoot) "su" else "/system/bin/sh"
            shellProcess = ProcessBuilder(binary)
                .redirectErrorStream(true)
                .apply {
                    environment()["TERM"]   = "xterm-256color"
                    environment()["HOME"]   = filesDir.absolutePath
                    environment()["TMPDIR"] = cacheDir.absolutePath
                }.start()
            shellWriter = BufferedWriter(OutputStreamWriter(shellProcess!!.outputStream))
            executor.execute { readShellOutputStream() }
            printColored("[SHELL] Session: $binary\n\n", C_GREEN)
        } catch (e: IOException) {
            printColored("[ERROR] Shell: ${e.message}\n", C_RED)
        }
    }

    private fun readShellOutputStream() {
        try {
            val reader = BufferedReader(InputStreamReader(shellProcess!!.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                mainHandler.post { printColored("$line\n", C_WHITE) }
            }
        } catch (e: IOException) {
            mainHandler.post { printColored("\n[SESSION TERMINATED]\n", C_ORANGE) }
        }
    }

    private fun executeCommand(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return
        commandHistory.add(trimmed)
        historyIndex = commandHistory.size
        val prompt = if (isRooted) "☠ root@PirateDroid:~# " else "⚓ user@PirateDroid:~$ "
        printColored("$prompt$trimmed\n", C_CYAN)
        try {
            shellWriter?.apply { write("$trimmed\n"); flush() }
                ?: run { printColored("[ERROR] Shell is not running.\n", C_RED); initShell() }
        } catch (e: IOException) {
            printColored("[ERROR] ${e.message}\n", C_RED); initShell()
        }
        commandInput.setText("")
    }

    private fun sendEscapeSequence(seq: String) {
        try {
            shellWriter?.apply { write(seq); flush() }
        } catch (e: IOException) {
            printColored("[ERROR] ${e.message}\n", C_RED)
        }
    }

    private fun destroyShell() {
        try { shellWriter?.close(); shellProcess?.destroyForcibly() } catch (_: Exception) {}
        shellWriter = null; shellProcess = null
    }

    // ════════════════════════════════════════════════════════════════
    // BATTERY GUARD
    // ════════════════════════════════════════════════════════════════

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun checkBatteryLevel() {
        val lvl = getBatteryLevel()
        when {
            lvl < 50 -> printColored("⚡ WARNING: Battery $lvl%! Flashing DISABLED!\n\n", C_RED)
            lvl < 70 -> printColored("⚡ Battery $lvl%. Recommended 70%+ for modding.\n\n", C_ORANGE)
            else     -> printColored("⚡ Battery $lvl% — green light for modding! ⚓\n\n", C_GREEN)
        }
    }

    // ════════════════════════════════════════════════════════════════
    // QUICKDUMP™
    // ════════════════════════════════════════════════════════════════

    private fun performQuickDump() {
        if (getBatteryLevel() < 30) {
            printColored("[QuickDump] ABORT: Battery too low!\n", C_RED); return
        }
        printColored("\n[QuickDump™] Collecting data...\n", C_YELLOW)
        executor.execute {
            val ts   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val dir  = getExternalFilesDir(null) ?: filesDir
            val file = File(dir, "dump_$ts.txt")
            val sections = linkedMapOf(
                "DEVICE"  to "echo \"Model: \$(getprop ro.product.model)\\nAndroid: \$(getprop ro.build.version.release)\\nBuild: \$(getprop ro.build.display.id)\"",
                "KERNEL"  to "uname -a",
                "PROPS"   to "getprop",
                "DISK"    to "df -h",
                "MEMORY"  to "free -m",
                "MOUNTS"  to "cat /proc/mounts",
                "DMESG"   to "dmesg",
                "LOGCAT"  to "logcat -d -v time *:V 2>&1 | tail -5000"
            )
            try {
                file.bufferedWriter().use { w ->
                    w.write("PirateDroid QuickDump — $ts\n${"═".repeat(50)}\n\n")
                    sections.forEach { (name, cmd) ->
                        mainHandler.post { printColored("[QuickDump] → $name\n", C_CYAN) }
                        w.write("\n[$name]\n${"─".repeat(40)}\n")
                        try {
                            val sh = if (isRooted) arrayOf("su", "-c", cmd)
                            else arrayOf("/system/bin/sh", "-c", cmd)
                            val p = Runtime.getRuntime().exec(sh)
                            w.write(p.inputStream.bufferedReader().readText())
                            p.waitFor()
                        } catch (e: Exception) { w.write("ERROR: ${e.message}\n") }
                    }
                }
                mainHandler.post {
                    printColored("""
╔══════════════════════════════════════════════╗
║  ✅ QuickDump™ READY                         ║
║  ${file.absolutePath}
║  Size: ${file.length() / 1024} KB
╚══════════════════════════════════════════════╝
""", C_GREEN)
                }
            } catch (e: IOException) {
                mainHandler.post { printColored("[QuickDump] ERROR: ${e.message}\n", C_RED) }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // REBOOT MANAGER
    // ════════════════════════════════════════════════════════════════

    private fun showRebootMenu() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚓ PirateDroid Reboot Manager")
            .setMessage("Battery: ${getBatteryLevel()}% — choose mode:")
            .setPositiveButton("⬇️ Download") { _, _ -> rebootTo("reboot download") }
            .setNeutralButton("🔄 Reboot")    { _, _ -> rebootTo("reboot") }
            .setNegativeButton("🔧 Recovery") { _, _ -> rebootTo("reboot recovery") }
            .create().show()
    }

    private fun rebootTo(mode: String) {
        if (!isRooted) { printColored("[REBOOT] No root access!\n", C_RED); return }
        if (getBatteryLevel() < 50 && mode != "reboot") {
            printColored("[REBOOT] BLOCKED: Battery < 50%!\n", C_RED); return
        }
        printColored("[REBOOT] Executing: $mode\n", C_YELLOW)
        executor.execute {
            try { Runtime.getRuntime().exec(arrayOf("su", "-c", mode)).waitFor() }
            catch (e: Exception) {
                mainHandler.post { printColored("[REBOOT] Error: ${e.message}\n", C_RED) }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // GUIDE
    // ════════════════════════════════════════════════════════════════

    private fun showGuide() {
        printColored("""
╔══════════════════════════════════════════════════╗
║   ⚓ MODDER'S GOLDEN RULES — Samsung A51 5G ⚓     ║
╠══════════════════════════════════════════════════╣
║  ⚡ #1 BATTERY — min. 50%, optimal 80%+           ║
║                                                  ║
║  📱 #2 A51 5G — patch init_boot.img!             ║
║     1. Download firmware SM-A516B                ║
║     2. Extract init_boot.img from AP_*.tar.md5   ║
║     3. Magisk → Install → Patch a File           ║
║     4. Heimdall flash --INIT_BOOT patched.img    ║
║                                                  ║
║  🐧 #3 LINUX — Heimdall (open-source Odin)       ║
║     sudo apt install heimdall-flash              ║
║                                                  ║
║  🔐 #4 SECURITY                                  ║
║     Backup EFS before modding (IMEI!):           ║
║     dd if=/dev/block/by-name/efs \               ║
║        of=/sdcard/efs_backup.img                 ║
║     Knox Counter = IRREVERSIBLE!                 ║
║                                                  ║
║  📚 #5 EDUCATION                                 ║
║     XDA: xda-developers.com/samsung-a51-5g       ║
║     Magisk: github.com/topjohnwu/Magisk          ║
║     Heimdall: github.com/benjamin-dobell/heimdall║
╚══════════════════════════════════════════════════╝
""", C_YELLOW)
    }

    // ════════════════════════════════════════════════════════════════
    // UI HELPERS
    // ════════════════════════════════════════════════════════════════

    private fun bindViews() {
        terminalOutput = findViewById(R.id.terminalOutput)
        commandInput   = findViewById(R.id.commandInput)
        scrollView     = findViewById(R.id.scrollView)
        btnExecute     = findViewById(R.id.btnExecute)
        btnGuide       = findViewById(R.id.btnGuide)
        btnQuickDump   = findViewById(R.id.btnQuickDump)
        btnReboot      = findViewById(R.id.btnReboot)
        btnToggleTheme = findViewById(R.id.btnToggleTheme)
        btnKeyCtrl     = findViewById(R.id.btnKeyCtrl)
        btnKeyAlt      = findViewById(R.id.btnKeyAlt)
        btnKeyEsc      = findViewById(R.id.btnKeyEsc)
        btnKeyTab      = findViewById(R.id.btnKeyTab)
        btnKeyUp       = findViewById(R.id.btnKeyUp)
        btnKeyDown     = findViewById(R.id.btnKeyDown)
        btnKeyLeft     = findViewById(R.id.btnKeyLeft)
        btnKeyRight    = findViewById(R.id.btnKeyRight)
        btnKeyHome     = findViewById(R.id.btnKeyHome)
        btnKeyEnd      = findViewById(R.id.btnKeyEnd)
        btnKeyPgUp     = findViewById(R.id.btnKeyPgUp)
        btnKeyPgDn     = findViewById(R.id.btnKeyPgDn)
        terminalOutput.typeface = Typeface.MONOSPACE
        commandInput.typeface   = Typeface.MONOSPACE
    }

    private fun printColored(text: String, color: Int) {
        val sp = SpannableString(text)
        sp.setSpan(ForegroundColorSpan(color), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        terminalOutput.append(sp)
        mainHandler.postDelayed({ scrollView.fullScroll(ScrollView.FOCUS_DOWN) }, 50)
    }

    private fun applyDarkTheme() {
        findViewById<LinearLayout>(R.id.rootLayout).setBackgroundColor(0xFF0A0A0A.toInt())
        terminalOutput.setBackgroundColor(0xFF0A0A0A.toInt())
        terminalOutput.setTextColor(C_GREEN)
        commandInput.setBackgroundColor(0xFF1A1A1A.toInt())
        commandInput.setTextColor(C_GREEN)
        commandInput.setHintTextColor(0xFF3D3D3D.toInt())
        isDarkMode = true
        btnToggleTheme.text = "☀ LIGHT"
    }

    private fun applyLightTheme() {
        findViewById<LinearLayout>(R.id.rootLayout).setBackgroundColor(0xFFF5F5F5.toInt())
        terminalOutput.setBackgroundColor(0xFFF5F5F5.toInt())
        terminalOutput.setTextColor(0xFF1A1A1A.toInt())
        commandInput.setBackgroundColor(0xFFFFFFFF.toInt())
        commandInput.setTextColor(0xFF1A1A1A.toInt())
        commandInput.setHintTextColor(0xFF9E9E9E.toInt())
        isDarkMode = false
        btnToggleTheme.text = "🌙 DARK"
    }

    private fun openKeyboardWithDelay() {
        commandInput.postDelayed({
            commandInput.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(commandInput, InputMethodManager.SHOW_IMPLICIT)
        }, 400)
    }

    // ════════════════════════════════════════════════════════════════
    // LISTENERS
    // ════════════════════════════════════════════════════════════════

    private fun setupListeners() {
        btnExecute.setOnClickListener { executeCommand(commandInput.text.toString()) }

        commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_DONE) {
                executeCommand(commandInput.text.toString()); true
            } else false
        }

        commandInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        if (historyIndex > 0) {
                            historyIndex--
                            commandInput.setText(commandHistory[historyIndex])
                            commandInput.setSelection(commandInput.text.length)
                        }; true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (historyIndex < commandHistory.size - 1) {
                            historyIndex++
                            commandInput.setText(commandHistory[historyIndex])
                        } else {
                            historyIndex = commandHistory.size
                            commandInput.setText("")
                        }
                        commandInput.setSelection(commandInput.text.length); true
                    }
                    else -> false
                }
            } else false
        }

        btnGuide.setOnClickListener     { showGuide() }
        btnQuickDump.setOnClickListener { performQuickDump() }
        btnReboot.setOnClickListener    { showRebootMenu() }
        btnToggleTheme.setOnClickListener {
            if (isDarkMode) applyLightTheme() else applyDarkTheme()
        }

        terminalOutput.setOnLongClickListener {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cb.setPrimaryClip(android.content.ClipData.newPlainText("PirateDroid", terminalOutput.text))
            Toast.makeText(this, "⚓ Log copied!", Toast.LENGTH_SHORT).show()
            true
        }

        // ══ EXTRA KEYS ══════════════════════════════════════════════
        btnKeyCtrl.setOnClickListener  { sendEscapeSequence("\u0003") }
        btnKeyAlt.setOnClickListener   { sendEscapeSequence("\u001b") }
        btnKeyEsc.setOnClickListener   { sendEscapeSequence("\u001b") }
        btnKeyTab.setOnClickListener   { handleTabComplete() }
        btnKeyUp.setOnClickListener    {
            if (historyIndex > 0) {
                historyIndex--
                commandInput.setText(commandHistory[historyIndex])
                commandInput.setSelection(commandInput.text.length)
            } else sendEscapeSequence("\u001b[A")
        }
        btnKeyDown.setOnClickListener  {
            if (historyIndex < commandHistory.size - 1) {
                historyIndex++
                commandInput.setText(commandHistory[historyIndex])
                commandInput.setSelection(commandInput.text.length)
            } else {
                historyIndex = commandHistory.size
                commandInput.setText("")
                sendEscapeSequence("\u001b[B")
            }
        }
        btnKeyLeft.setOnClickListener  {
            val pos = commandInput.selectionStart
            if (pos > 0) commandInput.setSelection(pos - 1)
        }
        btnKeyRight.setOnClickListener {
            val pos = commandInput.selectionStart
            if (pos < commandInput.text.length) commandInput.setSelection(pos + 1)
        }
        btnKeyHome.setOnClickListener  { commandInput.setSelection(0) }
        btnKeyEnd.setOnClickListener   { commandInput.setSelection(commandInput.text.length) }
        btnKeyPgUp.setOnClickListener  { scrollView.smoothScrollBy(0, -600) }
        btnKeyPgDn.setOnClickListener  { scrollView.smoothScrollBy(0, 600) }
    }
}