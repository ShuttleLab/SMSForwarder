package com.smsforwarder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 主界面 = 三步引导：① 登录/注册 → ② 绑定 Telegram → ③ 开启服务。每步完成打 ✓。
 * 开启服务时会自动申请短信/通知权限，并自动弹电池优化豁免（都不用进设置）。
 * 保活细项/关于/语言都在右上角「设置」里。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStep1: TextView
    private lateinit var tvStep2: TextView
    private lateinit var tvStep3: TextView
    private lateinit var btnStep1: Button
    private lateinit var btnStep2: Button
    private lateinit var btnToggle: Button
    private lateinit var llLog: LinearLayout
    private lateinit var tvBackend: TextView

    private val green = 0xFF00E676.toInt()
    private val grey = 0xFF888888.toInt()

    /** 用户点了「开启服务」但还缺权限，授权回来后继续启动 */
    private var pendingStart = false

    private val requiredPerms: Array<String>
        get() = buildList {
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyEdgeToEdgeInsets(findViewById(R.id.root))

        tvStep1 = findViewById(R.id.tvStep1)
        tvStep2 = findViewById(R.id.tvStep2)
        tvStep3 = findViewById(R.id.tvStep3)
        btnStep1 = findViewById(R.id.btnStep1)
        btnStep2 = findViewById(R.id.btnStep2)
        btnToggle = findViewById(R.id.btnToggle)
        llLog = findViewById(R.id.llLog)
        tvBackend = findViewById(R.id.tvBackend)
        tvBackend.setOnClickListener { pingBackend() }
        findViewById<Button>(R.id.btnBackendCheck).setOnClickListener { pingBackend() }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnStep1.setOnClickListener { loginOrLogout() }
        btnStep2.setOnClickListener { startActivity(Intent(this, ChannelsActivity::class.java)) }
        btnToggle.setOnClickListener { toggleService() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { ForwardLog.clear(this); refresh() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        pingBackend()   // 打开界面时自动检测一次；之后由用户点「检测」按钮手动刷新
    }

    /** 探测后端是否可达，把结果显示成 🟢/🔴（带本次检测时间）。网络在后台线程做。 */
    private fun pingBackend() {
        tvBackend.text = getString(R.string.backend_checking)
        tvBackend.setTextColor(grey)
        Thread {
            val ok = Api.health(this)
            runOnUiThread {
                val now = DateFormat.format("HH:mm", System.currentTimeMillis()).toString()
                if (ok) {
                    tvBackend.text = getString(R.string.backend_online_fmt, now)
                    tvBackend.setTextColor(green)
                } else {
                    tvBackend.text = getString(R.string.backend_offline_fmt, now)
                    tvBackend.setTextColor(0xFFFF5252.toInt())
                }
            }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (pendingStart) {
            pendingStart = false
            if (hasAllPerms()) startServiceNow()
            else Toast.makeText(this, R.string.toast_need_perm, Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    private fun hasAllPerms(): Boolean = requiredPerms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun isIgnoringBattery(): Boolean {
        // Android 7（API 24/25）没有电池优化 API，异常时按未豁免处理
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun refresh() {
        val loggedIn = Prefs.isLoggedIn(this)
        val running = Prefs.isRunning(this)

        if (loggedIn) {
            setStep(tvStep1, true, getString(R.string.step1_done_fmt, Prefs.username(this)))
            btnStep1.text = getString(R.string.btn_logout)
        } else {
            setStep(tvStep1, false, getString(R.string.step1_todo))
            btnStep1.text = getString(R.string.btn_login)
        }

        setStep(tvStep3, running, getString(if (running) R.string.step3_running else R.string.step3_stopped))
        btnToggle.text = getString(if (running) R.string.btn_stop else R.string.btn_start)

        btnStep2.isEnabled = loggedIn
        if (!loggedIn) {
            setStep(tvStep2, false, getString(R.string.step2_need_login))
        } else {
            setStep(tvStep2, false, getString(R.string.step2_checking))
            checkStep2()
        }

        renderLog()
    }

    private fun checkStep2() {
        Thread {
            val ready = when (val r = Api.listChannels(this)) {
                is ApiResp.Ok -> {
                    val arr = Api.channelsArray(r.body)
                    (0 until arr.length()).any { arr.optJSONObject(it)?.optBoolean("ready") == true }
                }
                is ApiResp.Err -> false
            }
            runOnUiThread {
                if (!Prefs.isLoggedIn(this)) return@runOnUiThread
                setStep(tvStep2, ready, getString(if (ready) R.string.step2_done else R.string.step2_todo))
            }
        }.start()
    }

    private fun setStep(tv: TextView, done: Boolean, text: String) {
        tv.text = (if (done) "✓ " else "○ ") + text
        tv.setTextColor(if (done) green else grey)
    }

    private fun loginOrLogout() {
        if (Prefs.isLoggedIn(this)) confirmLogout()
        else startActivity(Intent(this, LoginActivity::class.java))
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.logout_title)
            .setMessage(R.string.logout_msg)
            .setPositiveButton(R.string.logout) { _, _ ->
                // 先抓住当前 token，再清本地会话，最后用抓住的 token 去后端吊销这台设备，
                // 避免"清除会话"与"发吊销请求读 token"之间的竞态（否则可能带空 token → 401 → 没吊销）。
                val tok = Prefs.token(this)
                Prefs.get(this).edit().putBoolean(Prefs.KEY_RUNNING, false).apply()
                stopService(Intent(this, ForwardService::class.java))
                Prefs.clearSession(this)
                refresh()
                Thread { Api.logout(this, tok) }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleService() {
        if (!Prefs.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java)); return
        }
        if (Prefs.isRunning(this)) {
            Prefs.get(this).edit().putBoolean(Prefs.KEY_RUNNING, false).apply()
            stopService(Intent(this, ForwardService::class.java))
            refresh()
            return
        }
        // 启动前先确保权限；缺就自动申请，授权回来后继续启动
        if (!hasAllPerms()) {
            pendingStart = true
            requestPermissions(requiredPerms, 100)
            return
        }
        startServiceNow()
    }

    private fun startServiceNow() {
        val svc = Intent(this, ForwardService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
        else startService(svc)
        Prefs.get(this).edit().putBoolean(Prefs.KEY_RUNNING, true).apply()
        refresh()
        maybeRequestBattery() // 顺手自动弹电池优化豁免（未豁免时）
    }

    private fun maybeRequestBattery() {
        if (isIgnoringBattery()) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
        }
    }

    private fun renderLog() {
        llLog.removeAllViews()
        val logs = ForwardLog.all(this).take(100)
        if (logs.isEmpty()) {
            val tv = TextView(this).apply {
                text = getString(R.string.log_empty)
                setTextColor(grey)
                setPadding(0, 24, 0, 24)
            }
            llLog.addView(tv)
            return
        }
        val inflater = LayoutInflater.from(this)
        for (e in logs) {
            val row = inflater.inflate(R.layout.item_log, llLog, false)
            row.findViewById<TextView>(R.id.tvTime).text = DateFormat.format("MM-dd HH:mm:ss", e.time)
            row.findViewById<TextView>(R.id.tvSender).text = e.sender
            row.findViewById<TextView>(R.id.tvChannel).text = getString(R.string.log_to_server)
            val st = row.findViewById<TextView>(R.id.tvResult)
            if (e.ok) {
                st.text = getString(R.string.log_ok); st.setTextColor(green)
            } else {
                st.text = getString(R.string.log_fail); st.setTextColor(0xFFFF5252.toInt())
            }
            row.findViewById<TextView>(R.id.tvDetail).text = e.detail
            llLog.addView(row)
        }
    }
}
