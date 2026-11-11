package dev.kestrel.app.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.provider.Settings
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Wraps the player view and adds gestures:
 * double tap left/right = seek 10 s, swipe up/down on the left = brightness, on the right = volume.
 * Single taps still reach the player controls.
 */
@SuppressLint("ViewConstructor")
class GestureFrame(context: Context, private val activity: Activity) : FrameLayout(context) {
    var onSeek: (forward: Boolean) -> Unit = {}
    var enabledGestures = true

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var mode = 0 // 0 none, 1 brightness, 2 volume
    private var startY = 0f
    private var startValue = 0f

    private val indicator = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 16f
        setPadding(36, 18, 36, 18)
        background = GradientDrawable().apply { cornerRadius = 40f; setColor(0xAA000000.toInt()) }
        visibility = GONE
    }

    init {
        post { addView(indicator, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)) }
    }

    private fun show(text: String) {
        indicator.text = text
        indicator.visibility = VISIBLE
        indicator.bringToFront()
        removeCallbacks(hide)
        postDelayed(hide, 700)
    }

    private val hide = Runnable { indicator.visibility = GONE }

    private fun currentBrightness(): Float {
        val w = activity.window.attributes.screenBrightness
        if (w >= 0) return w
        return runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
    }

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!enabledGestures) return false
            when {
                e.x < width / 3f -> { onSeek(false); show("« 10 s") }
                e.x > width * 2 / 3f -> { onSeek(true); show("10 s »") }
                else -> return false
            }
            return true
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!enabledGestures) return super.dispatchTouchEvent(ev)
        detector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = 0
                startY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = startY - ev.y
                if (mode == 0 && abs(dy) > slop * 2 && ev.pointerCount == 1) {
                    mode = if (ev.x < width / 2f) 1 else 2
                    startY = ev.y
                    startValue = if (mode == 1) currentBrightness()
                    else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    // Tell children the touch was taken over.
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
                if (mode != 0) {
                    val delta = (startY - ev.y) / (height.coerceAtLeast(1) * 0.8f)
                    val v = (startValue + delta).coerceIn(0f, 1f)
                    if (mode == 1) {
                        val lp = activity.window.attributes
                        lp.screenBrightness = v.coerceAtLeast(0.01f)
                        activity.window.attributes = lp
                        show("☀ ${(v * 100).toInt()}%")
                    } else {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).toInt(), 0)
                        show("🔊 ${(v * 100).toInt()}%")
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (mode != 0) {
                mode = 0
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
