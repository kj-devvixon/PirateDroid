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

    // ─── Shell ───────────────────────────────────────────────────────
    private var shellProcess: Process?       = null
    private var shellWriter:  BufferedWriter? = null
    private val executor     = Executors.newCachedThreadPool()
    private val mainHandler  = Handler(Looper.getMainLooper())

    // ─── State ───────────────────────────────────────────────────────
    private var isDarkMode       = true
    private var isRooted         = false
    private val commandHistory   = mutableListOf<String>()
    private var historyIndex     = 0

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
        printBootSequence()   // 1. banner
        initShell()           // 2. shell as user (temporary)
        checkBatteryLevel()   // 3. battery
        setupListeners()
        openKeyboardWithDelay()
        checkRootAsync()      // 4. async root test — result = proper banner
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyShell()
        executor.shutdownNow()
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
    // ROOT DETECTION — asynchronous, no hardcoding
    // ════════════════════════════════════════════════════════════════

    /**
     * Real two-stage test:
     *   1. Static  — does su binary exist on disk?
     *   2. Dynamic — does OS actually accept "su -c id"?
     *
     * Only the system's response decides the banner.
     * No assumptions made beforehand.
     */
    private fun checkRootAsync() {
        printColored("[ROOT] Querying OS for su permissions...\n", C_CYAN)

        executor.execute {
            // ── Stage 1: static su presence ───────────────────────
            val suPaths = listOf(
                "/system/xbin/su",
                "/system/bin/su",
                "/sbin/su",
                "/su/bin/su",
                "/magisk/.core/bin/su",
                "/data/adb/ksu/bin/su",
                "/data/adb/magisk/su"
            )
            val staticFound = suPaths.firstOrNull { File(it).exists() }

            // ── Stage 2: dynamic execution test ───────────────────
            var dynamicUid0   = false
            var dynamicOutput = ""
            var dynamicError  = ""

            try {
                val proc = ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(false)
                    .start()

                // wait max 4 seconds — Magisk may need a moment
                val finished = proc.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)

                dynamicOutput = proc.inputStream.bufferedReader().readText().trim()
                dynamicError  = proc.errorStream.bufferedReader().readText().trim()

                if (finished && dynamicOutput.contains("uid=0")) {
                    dynamicUid0 = true
                }
            } catch (e: Exception) {
                dynamicError = e.message ?: "Exception: unknown error"
            }

            isRooted = dynamicUid0

            // ── Result on UI thread ────────────────────────────────────
            mainHandler.post {
                showRootBanner(staticFound, dynamicUid0, dynamicOutput, dynamicError)
            }
        }
    }

    /**
     * Banner depends EXCLUSIVELY on OS response.
     * If root OK → congrats + reinit shell as root.
     * If root NOT OK → specific diagnosis why.
     */
    private fun showRootBanner(
        staticPath:    String?,
        dynamicUid0:   Boolean,
        dynamicOutput: String,
        dynamicError:  String
    ) {
        if (dynamicUid0) {
            // ════════════════════════════════════
            // ✅ OS accepted su — root works
            // ════════════════════════════════════
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
            // ════════════════════════════════════
            // ❌ OS rejected su — diagnosis
            // ════════════════════════════════════
            printColored("""
┌──────────────────────────────────────────────────┐
│   ⚓  ROOT FAILED                                 │
│   OS rejected su or did not respond.             │
└──────────────────────────────────────────────────┘
""", C_YELLOW)

            printColored("── Diagnosis ──────────────────────────────────\n", C_ORANGE)

            // Binary
            if (staticPath == null) {
                printColored("✗ No su binary in known paths.\n", C_RED)
                printColored("  → Phone is not rooted, OR\n", C_WHITE)
                printColored("  → Magisk / KernelSU not installed.\n\n", C_WHITE)
            } else {
                printColored("✓ su binary found: $staticPath\n", C_GREEN)
                printColored("  → But OS did not execute it correctly.\n\n", C_ORANGE)
            }

            // System error
            if (dynamicError.isNotEmpty()) {
                printColored("✗ OS Error: $dynamicError\n", C_RED)
                when {
                    dynamicError.contains("Permission denied", ignoreCase = true) ->
                        printColored("  → SELinux Enforcing blocks su.\n" +
                                "    Check: adb shell getenforce\n", C_YELLOW)
                    dynamicError.contains("not found", ignoreCase = true) ->
                        printColored("  → su does not exist in system PATH.\n", C_YELLOW)
                    dynamicError.contains("No such file", ignoreCase = true) ->
                        printColored("  → su not installed.\n", C_YELLOW)
                    else ->
                        printColored("  → Unknown error — check Magisk logs.\n", C_YELLOW)
                }
                printColored("\n", C_WHITE)
            }

            // Partial response (uid != 0)
            if (dynamicOutput.isNotEmpty() && !dynamicUid0) {
                printColored("✗ su responded, but uid != 0: $dynamicOutput\n", C_ORANGE)
                printColored("  → Fake su, or partial root.\n\n", C_YELLOW)
            }

            // Timeout — no response at all
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
                val out = "$line\n"
                mainHandler.post { printColored(out, C_WHITE) }
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
            // getExternalFilesDir doesn't require MANAGE_EXTERNAL_STORAGE on Android 11+
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
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚓ PirateDroid Reboot Manager")
            .setMessage("Battery: ${getBatteryLevel()}% — choose mode:")
            .setPositiveButton("⬇️ Download") { _, _ -> rebootTo("reboot download") }
            .setNeutralButton("🔄 Reboot") { _, _ -> rebootTo("reboot") }
            .setNegativeButton("🔧 Recovery") { _, _ -> rebootTo("reboot recovery") }
            .create()
        dialog.show()
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
║   ⚓ MODDER'S GOLDEN RULES — Samsung A51 5G ⚓  ║
╠══════════════════════════════════════════════════╣
║  ⚡ #1 BATTERY — min. 50%, optimal 80%+         ║
║                                                  ║
║  📱 #2 A51 5G — patch init_boot.img!           ║
║     1. Download firmware SM-A516B                ║
║     2. Extract init_boot.img from AP_*.tar.md5   ║
║     3. Magisk → Install → Patch a File           ║
║     4. Heimdall flash --INIT_BOOT patched.img    ║
║                                                  ║
║  🐧 #3 LINUX — Heimdall (open-source Odin)      ║
║     sudo apt install heimdall-flash              ║
║                                                  ║
║  🔐 #4 SECURITY                                 ║
║     Backup EFS before modding (IMEI!):           ║
║     dd if=/dev/block/by-name/efs \               ║
║        of=/sdcard/efs_backup.img                 ║
║     Knox Counter = IRREVERSIBLE!                 ║
║                                                  ║
║  📚 #5 EDUCATION                                ║
║     XDA: xda-developers.com/samsung-a51-5g      ║
║     Magisk: github.com/topjohnwu/Magisk          ║
║     Heimdall: gitlab.com/BenjaminDobell/Heimdall ║
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
            cb.setPrimaryClip(
                android.content.ClipData.newPlainText("PirateDroid", terminalOutput.text)
            )
            Toast.makeText(this, "⚓ Log copied!", Toast.LENGTH_SHORT).show()
            true
        }
    }
}