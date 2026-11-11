package dev.kestrel.app.player

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.kestrel.app.KestrelApp
import dev.kestrel.app.MainActivity
import dev.kestrel.app.R
import kotlin.math.abs

/** Floating window that plays the current video on top of other apps. */
@UnstableApi
class PopupService : Service() {
    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var playerView: PlayerView? = null
    private lateinit var params: WindowManager.LayoutParams

    companion object {
        @Volatile var isShowing = false
            private set

        fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)

        fun start(ctx: Context) {
            ctx.startService(Intent(ctx, PopupService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PopupService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (root == null) show()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, KestrelApp.CH_POPUP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Popup player")
            .setContentText(PlayerManager.now.value?.video?.title ?: "Playing")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        ServiceCompat.startForeground(
            this, 42, notification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        this.wm = wm
        val width = (resources.displayMetrics.widthPixels * 0.6).toInt()
        params = WindowManager.LayoutParams(
            width, width * 9 / 16,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - width - 24
            y = resources.displayMetrics.heightPixels / 2
        }

        val pv = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
            player = PlayerManager.player
        }
        playerView = pv

        fun button(icon: Int, onClick: () -> Unit) = ImageButton(this).apply {
            setImageResource(icon)
            setBackgroundColor(0x66000000)
            setColorFilter(Color.WHITE)
            setOnClickListener { onClick() }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            gravity = Gravity.CENTER
        }
        val playPause = button(R.drawable.ic_pause) {}
        fun syncIcon() = playPause.setImageResource(if (PlayerManager.player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        playPause.setOnClickListener {
            val p = PlayerManager.player
            if (p.isPlaying) p.pause() else p.play()
            syncIcon()
        }
        bar.addView(button(R.drawable.ic_close) { PlayerManager.player.pause(); stopSelf() })
        bar.addView(playPause)
        bar.addView(button(R.drawable.ic_fullscreen) {
            startActivity(
                Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            stopSelf()
        })

        val frame = FrameLayout(this)
        frame.addView(pv, FrameLayout.LayoutParams(-1, -1))
        frame.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root = frame

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = syncIcon()
        }
        PlayerManager.player.addListener(listener)

        val scaler = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val max = resources.displayMetrics.widthPixels
                val w = (params.width * d.scaleFactor).toInt().coerceIn(max / 3, max)
                params.width = w
                params.height = w * 9 / 16
                wm.updateViewLayout(frame, params)
                return true
            }
        })
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        frame.setOnTouchListener { _, e ->
            scaler.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && e.pointerCount == 1) {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                    wm.updateViewLayout(frame, params)
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    syncIcon()
                    bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    if (bar.visibility == View.VISIBLE) bar.postDelayed({ bar.visibility = View.GONE }, 3500)
                }
            }
            true
        }
        frame.tag = listener
        wm.addView(frame, params)
        isShowing = true
        PlayerManager.player.play()
    }

    override fun onDestroy() {
        isShowing = false
        (root?.tag as? Player.Listener)?.let { PlayerManager.player.removeListener(it) }
        playerView?.player = null
        root?.let { runCatching { wm?.removeView(it) } }
        root = null
        super.onDestroy()
    }
}
