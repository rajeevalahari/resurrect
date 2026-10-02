package com.example.resurrect

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * A draggable system-overlay window (TYPE_APPLICATION_OVERLAY) that floats over the
 * Settings app. The user reads the Wireless-Debugging pairing code + port from the
 * system dialog and types them here, taps Connect, and we pair() + autoConnect()
 * (mDNS discovery), then bring our app back to the foreground.
 */
object FloatingWindowController {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var statusView: TextView? = null

    fun isShowing(): Boolean = rootView != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        if (rootView != null) return
        val ctx = context.applicationContext
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val accent = Color.parseColor("#21C7A8")
        val cardBg = Color.parseColor("#10231F")
        val fieldBg = Color.parseColor("#1A2E2A")
        val onCard = Color.parseColor("#FFFFFF")
        val onCardDim = Color.parseColor("#A9C2BC")

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 16))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 18).toFloat()
                setColor(cardBg)
                setStroke(dp(ctx, 1), accent)
            }
            elevation = dp(ctx, 12).toFloat()
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(ctx).apply {
            text = "♻ Pair with ADB"
            setTextColor(onCard)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(ctx).apply {
            text = "✕"
            setTextColor(onCardDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
            setOnClickListener { hide() }
        }
        header.addView(title)
        header.addView(close)

        val subtitle = TextView(ctx).apply {
            text = "Open Wireless debugging ▸ Pair device with pairing code, then type the values below."
            setTextColor(onCardDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 10))
        }

        val codeField = EditText(ctx).apply {
            hint = "6-digit pairing code"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(onCard)
            setHintTextColor(onCardDim)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            background = fieldBackground(ctx, fieldBg, accent)
        }
        val portField = EditText(ctx).apply {
            hint = "5-digit pairing port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(onCard)
            setHintTextColor(onCardDim)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            background = fieldBackground(ctx, fieldBg, accent)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 10) }
        }

        val status = TextView(ctx).apply {
            text = "Ready to pair."
            setTextColor(onCardDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 12))
        }
        statusView = status

        val connectBtn = Button(ctx).apply {
            text = "Pair & Connect"
            isAllCaps = false
            setTextColor(Color.parseColor("#04211C"))
            setTypeface(Typeface.DEFAULT_BOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(accent)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 48)
            )
        }

        card.addView(header)
        card.addView(subtitle)
        card.addView(codeField)
        card.addView(portField)
        card.addView(status)
        card.addView(connectBtn)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(ctx, 12)
            y = dp(ctx, 80)
            width = wm.maximumWindowMetrics.bounds.width() - dp(ctx, 24)
        }

        header.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchRawX = 0f
            private var touchRawY = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchRawX = e.rawX
                        touchRawY = e.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (e.rawX - touchRawX).toInt()
                        params.y = startY + (e.rawY - touchRawY).toInt()
                        windowManager?.updateViewLayout(rootView, params)
                        return true
                    }
                }
                return false
            }
        })

        connectBtn.setOnClickListener {
            val code = codeField.text.toString().trim()
            val port = portField.text.toString().trim().toIntOrNull()
            if (code.length < 6 || port == null) {
                status.text = "Enter the 6-digit code and the pairing port."
                return@setOnClickListener
            }
            connectBtn.isEnabled = false
            setStatus("Pairing…")
            pairAndConnect(ctx, port, code, onDone = { connectBtn.isEnabled = true })
        }

        wm.addView(card, params)
        windowManager = wm
        rootView = card
    }

    fun hide() {
        main.post {
            rootView?.let { v -> runCatching { windowManager?.removeView(v) } }
            rootView = null
            windowManager = null
            statusView = null
        }
    }

    private fun pairAndConnect(ctx: Context, port: Int, code: String, onDone: () -> Unit) {
        executor.execute {
            try {
                val manager = AdbConnectionManager.getInstance(ctx)
                val paired = manager.pair("127.0.0.1", port, code)
                if (!paired) {
                    setStatus("Pairing failed — check the code & port.")
                    main.post(onDone)
                    return@execute
                }
                ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(MainActivity.KEY_PAIRED, true).apply()
                setStatus("Paired ✓  Discovering connection…")
                val connected = manager.autoConnect(ctx, 25_000L)
                if (!connected) {
                    setStatus("Paired, but auto-connect failed. Open the app and enter the connection port.")
                    main.post(onDone)
                    return@execute
                }
                setStatus("Connected ✓  Opening app…")
                AdbEvents.emit(mapOf("event" to "connected"))
                main.post {
                    bringAppToFront(ctx)
                    main.postDelayed({ hide() }, 700)
                }
            } catch (e: Throwable) {
                setStatus("Error: ${e.message ?: e.toString()}")
                main.post(onDone)
            }
        }
    }

    private fun bringAppToFront(ctx: Context) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        if (intent != null) runCatching { ctx.startActivity(intent) }
    }

    private fun setStatus(text: String) {
        main.post { statusView?.text = text }
    }

    private fun fieldBackground(ctx: Context, fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(ctx, 12).toFloat()
            setColor(fill)
            setStroke(dp(ctx, 1), stroke)
        }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()
}
