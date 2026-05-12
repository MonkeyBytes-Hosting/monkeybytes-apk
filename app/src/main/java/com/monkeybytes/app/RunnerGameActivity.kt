package com.monkeybytes.app

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class RunnerGameActivity : AppCompatActivity() {

    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val frame = FrameLayout(this).apply { setBackgroundColor(0xFF0D1F0A.toInt()) }

        gameView = GameView(this,
            onGameStart = { startRound() },
            onGameEnd   = { score, coins -> finishRound(score, coins) }
        )
        frame.addView(gameView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        val backBtn = TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(0xFFB0C4B1.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener { finish() }
        }
        frame.addView(backBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START })

        setContentView(frame)
    }

    private fun startRound() {
        thread {
            val result = PanelApi.runnerStart(this)
            runOnUiThread {
                if (result.isOk) {
                    val d = result.value!!
                    gameView.setRoundToken(d.roundToken)
                    gameView.setTimeSessionsRemaining(d.timeSessionsRemaining)
                    val hourlyLeft = d.hourlyMax - d.hourlyUsed - 1
                    val dailyLeft  = d.dailyMax  - d.dailyUsed  - 1
                    if (hourlyLeft <= 2 || dailyLeft <= 3) {
                        gameView.showLimitWarning("$hourlyLeft plays left this hour")
                    }
                } else {
                    // Silent — game still plays, tokens just won't be awarded at end
                }
            }
        }
    }

    private fun finishRound(score: Int, coins: Int) {
        val token = gameView.getRoundToken() ?: return
        thread {
            val result = PanelApi.runnerFinish(this, token, score, coins)
            runOnUiThread {
                if (result.isOk) {
                    gameView.setTokenResult(result.value!!)
                } else {
                    gameView.setTokenError(result.error ?: "Could not claim tokens")
                }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

private class GameView(
    context: Context,
    private val onGameStart: () -> Unit,
    private val onGameEnd: (score: Int, coins: Int) -> Unit,
) : View(context) {

    companion object {
        private const val GRAVITY         = 1600f
        private const val JUMP_VEL        = -1100f
        private const val BASE_SPEED      = 340f
        private const val SPEED_INC       = 12f
        private const val GROUND_FRAC     = 0.82f
        private const val PLAYER_SIZE     = 0.09f
        private const val OBS_W           = 0.055f
        private const val OBS_MIN_H       = 0.06f
        private const val OBS_MAX_H       = 0.13f
        private const val OBS_SPACING     = 0.75f
        private const val COIN_R          = 0.024f

        // Jungle palette
        private const val C_BG_SKY    = 0xFF0D2B0A.toInt()
        private const val C_BG_MID    = 0xFF0A1F08.toInt()
        private const val C_GROUND    = 0xFF3D2B1F.toInt()
        private const val C_GROUND2   = 0xFF2A1A10.toInt()
        private const val C_TREE_DARK = 0xFF0F3B0B.toInt()
        private const val C_TREE_MID  = 0xFF1A5C14.toInt()
        private const val C_TREE_LITE = 0xFF2E8025.toInt()
        private const val C_ROCK      = 0xFF5C4A3A.toInt()
        private const val C_ROCK_HI   = 0xFF7A6150.toInt()
        private const val C_VINE      = 0xFF4CAF50.toInt()
        private const val C_MONKEY_BODY = 0xFFA0522D.toInt()
        private const val C_MONKEY_FACE = 0xFFD2A679.toInt()
        private const val C_MONKEY_EAR  = 0xFF8B4513.toInt()
        private const val C_BANANA    = 0xFFFFE135.toInt()
        private const val C_BANANA_HI = 0xFFFFF176.toInt()
        private const val C_TEXT      = 0xFFE8F5E9.toInt()
        private const val C_MUTED     = 0xFF81C784.toInt()
        private const val C_ACCENT    = 0xFF66BB6A.toInt()
        private const val C_GOLD      = 0xFFFFD54F.toInt()
        private const val C_RED       = 0xFFEF5350.toInt()
        private const val C_CARD      = 0xDD0A2008.toInt()

        private const val PREFS  = "runner_prefs"
        private const val KEY_HI = "high_score"
    }

    private enum class State { IDLE, RUNNING, DEAD }

    private val paint     = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface   = Typeface.DEFAULT_BOLD
        textAlign  = Paint.Align.CENTER
        color      = C_TEXT
    }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var state   = State.IDLE
    private var score   = 0f
    private var hiScore = prefs.getInt(KEY_HI, 0)
    private var coins   = 0
    private var elapsed = 0f  // seconds since game started

    // Token state
    private var roundToken: String?           = null
    private var tokenResult: PanelApi.RunnerFinish? = null
    private var tokenError: String?           = null
    private var limitWarning: String?         = null
    private var apiError: String?             = null
    private var timeSessionsRemaining: Int    = 3

    // Player
    private var py       = 0f
    private var pvy      = 0f
    private var onGround = false
    private var jumpCount = 0
    private var runFrame  = 0f  // animation clock

    // Obstacles (rocks / logs)
    private data class Obs(var x: Float, val h: Float, val isLog: Boolean)
    private val obstacles = mutableListOf<Obs>()

    // Bananas
    private data class Banana(var x: Float, val y: Float, var collected: Boolean = false)
    private val bananas = mutableListOf<Banana>()

    // Background trees (parallax layers)
    private data class BgTree(var x: Float, val y: Float, val scale: Float, val speed: Float, val layer: Int)
    private val bgTrees = mutableListOf<BgTree>()

    private var lastTime = 0L
    private var speed    = BASE_SPEED

    // Layout
    private var groundY    = 0f
    private var playerX    = 0f
    private var playerSize = 0f
    private var obsW       = 0f

    // Shake on death
    private var shakeTime = 0f
    private var shakeMag  = 0f

    // ─── Public API ───────────────────────────────────────────────────────────

    fun setRoundToken(t: String)                       { roundToken = t }
    fun getRoundToken(): String?                       = roundToken
    fun setTimeSessionsRemaining(n: Int)               { timeSessionsRemaining = n }
    fun setTokenResult(r: PanelApi.RunnerFinish)       { tokenResult = r; invalidate() }
    fun setTokenError(msg: String)                     { tokenError  = msg; invalidate() }
    fun showLimitWarning(msg: String)                  { limitWarning = msg; invalidate() }
    fun showApiError(msg: String)                      { apiError    = msg; invalidate() }

    // ─── Init ─────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        groundY    = h * GROUND_FRAC
        playerX    = w * 0.16f
        playerSize = w * PLAYER_SIZE
        obsW       = w * OBS_W
        py         = groundY - playerSize

        bgTrees.clear()
        // baseY = top of canopy. Keep trees anchored near ground so trunks don't float.
        repeat(8)  { bgTrees += BgTree(Random.nextFloat() * w, groundY * 0.55f, Random.nextFloat() * 0.25f + 0.18f, 25f,  0) }
        repeat(6)  { bgTrees += BgTree(Random.nextFloat() * w, groundY * 0.65f, Random.nextFloat() * 0.22f + 0.28f, 60f,  1) }
        repeat(5)  { bgTrees += BgTree(Random.nextFloat() * w, groundY * 0.72f, Random.nextFloat() * 0.18f + 0.35f, 110f, 2) }
    }

    // ─── Input ────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        when (state) {
            State.IDLE    -> { state = State.RUNNING; lastTime = System.nanoTime(); onGameStart(); invalidate() }
            State.RUNNING -> jump()
            State.DEAD    -> reset()
        }
        return true
    }

    private fun jump() {
        if (jumpCount < 2) { pvy = JUMP_VEL; onGround = false; jumpCount++ }
    }

    private fun reset() {
        state    = State.RUNNING
        score    = 0f
        coins    = 0
        elapsed  = 0f
        speed    = BASE_SPEED
        py       = groundY - playerSize
        pvy      = 0f
        onGround = true
        jumpCount = 0
        runFrame  = 0f
        obstacles.clear()
        bananas.clear()
        lastTime      = System.nanoTime()
        roundToken    = null
        tokenResult   = null
        tokenError    = null
        limitWarning  = null
        apiError      = null
        onGameStart()
        invalidate()
    }

    private fun die() {
        state = State.DEAD
        val s = score.roundToInt()
        if (s > hiScore) { hiScore = s; prefs.edit().putInt(KEY_HI, hiScore).apply() }
        shakeTime = 0.4f
        shakeMag  = 20f
        onGameEnd(s, coins)
    }

    // ─── Draw ─────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt  = if (lastTime == 0L) 0f else ((now - lastTime) / 1e9f).coerceAtMost(0.05f)
        lastTime = now

        val w = width.toFloat()
        val h = height.toFloat()

        var sx = 0f; var sy = 0f
        if (shakeTime > 0f) {
            shakeTime -= dt; shakeMag *= 0.86f
            sx = (Random.nextFloat() * 2 - 1) * shakeMag
            sy = (Random.nextFloat() * 2 - 1) * shakeMag * 0.5f
        }
        canvas.save()
        canvas.translate(sx, sy)

        drawSky(canvas, w, h)
        drawBgTrees(canvas, w, dt)
        drawGround(canvas, w, h)

        when (state) {
            State.IDLE    -> { drawPlayer(canvas, dt); drawIdleCard(canvas, w, h) }
            State.RUNNING -> {
                update(dt, w, h)
                drawObstacles(canvas, h)
                drawBananas(canvas, w)
                drawPlayer(canvas, dt)
                drawHud(canvas, w)
                apiError?.let    { drawBanner(canvas, w, it, C_RED) }
                limitWarning?.let { drawBanner(canvas, w, it, C_GOLD) }
            }
            State.DEAD    -> {
                drawObstacles(canvas, h)
                drawBananas(canvas, w)
                drawPlayer(canvas, 0f)
                drawHud(canvas, w)
                drawDeadCard(canvas, w, h)
            }
        }

        canvas.restore()
        if (state == State.RUNNING) invalidate()
    }

    // ─── Update ───────────────────────────────────────────────────────────────

    private fun update(dt: Float, w: Float, h: Float) {
        elapsed += dt
        score   += dt * speed / 10f
        speed    = BASE_SPEED + score * SPEED_INC / 100f
        runFrame += dt

        pvy += GRAVITY * dt
        py  += pvy * dt
        if (py >= groundY - playerSize) {
            py = groundY - playerSize; pvy = 0f; onGround = true; jumpCount = 0
        }

        // Background trees
        bgTrees.forEach { t -> t.x -= t.speed * dt; if (t.x + 200 < 0) t.x = w + 50 }

        // Spawn obstacles
        if (obstacles.isEmpty() || obstacles.last().x < w - w * OBS_SPACING) {
            val obsH  = h * (OBS_MIN_H + Random.nextFloat() * (OBS_MAX_H - OBS_MIN_H))
            val isLog = Random.nextBoolean()
            obstacles += Obs(w + obsW, obsH, isLog)
            if (Random.nextFloat() > 0.35f) {
                val o = obstacles.last()
                // Place banana at a fixed reachable height above ground, not relative to obstacle height
                val bananaY = groundY - playerSize * 2.2f
                bananas += Banana(o.x + obsW * 3f, bananaY)
            }
        }
        obstacles.forEach { it.x -= speed * dt }
        obstacles.removeAll { it.x + obsW < -20f }

        bananas.forEach { it.x -= speed * dt }
        bananas.removeAll { it.x + 40f < 0f }

        // Collisions
        val pr = RectF(
            playerX + playerSize * 0.2f, py + playerSize * 0.1f,
            playerX + playerSize * 0.85f, py + playerSize * 0.9f
        )
        for (obs in obstacles) {
            val or = RectF(obs.x + obsW * 0.1f, groundY - obs.h, obs.x + obsW * 0.9f, groundY)
            if (RectF.intersects(pr, or)) { die(); return }
        }
        val cr = w * COIN_R
        bananas.filter { !it.collected }.forEach { b ->
            if (pr.left < b.x + cr && pr.right > b.x - cr &&
                pr.top  < b.y + cr && pr.bottom > b.y - cr) {
                b.collected = true; coins++
            }
        }
    }

    // ─── Draw helpers ─────────────────────────────────────────────────────────

    private fun drawSky(canvas: Canvas, w: Float, h: Float) {
        val shader = LinearGradient(0f, 0f, 0f, groundY,
            intArrayOf(0xFF071A05.toInt(), 0xFF0D2B0A.toInt(), 0xFF143D10.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        paint.shader = shader
        canvas.drawRect(0f, 0f, w, groundY, paint)
        paint.shader = null
    }

    private fun drawBgTrees(canvas: Canvas, w: Float, dt: Float) {
        val colors = listOf(C_TREE_DARK, C_TREE_MID, C_TREE_LITE)
        for (layer in 0..2) {
            bgTrees.filter { it.layer == layer }.forEach { t ->
                drawJungleTree(canvas, t.x, t.y, t.scale * 120f, t.scale * 200f, colors[layer])
            }
        }
    }

    private fun drawJungleTree(canvas: Canvas, x: Float, baseY: Float, tw: Float, th: Float, canopyColor: Int) {
        // Canopy layers
        paint.color = canopyColor
        for (i in 2 downTo 0) {
            val cy = baseY + i * th * 0.12f
            val cw = tw * (1f - i * 0.18f)
            val ch = th * 0.55f
            canvas.drawOval(x - cw / 2f, cy - ch * 0.5f, x + cw / 2f, cy + ch * 0.5f, paint)
        }
        // Short trunk below canopy
        paint.color = 0xFF3E2723.toInt()
        val trunkW    = tw * 0.13f
        val canopyBot = baseY + 2 * th * 0.12f + th * 0.55f * 0.5f
        canvas.drawRect(x - trunkW / 2f, canopyBot, x + trunkW / 2f, canopyBot + th * 0.22f, paint)
    }

    private fun drawGround(canvas: Canvas, w: Float, h: Float) {
        paint.color = C_GROUND
        canvas.drawRect(0f, groundY, w, groundY + 6f, paint)
        paint.color = C_GROUND2
        canvas.drawRect(0f, groundY + 6f, w, h, paint)
        // Ground line details
        paint.color = 0xFF5C3D1E.toInt()
        for (i in 0..5) {
            val gx = ((i * 180f) % w)
            canvas.drawCircle(gx, groundY + 10f, 4f, paint)
        }
    }

    private fun drawPlayer(canvas: Canvas, dt: Float) {
        val x = playerX
        val y = py
        val s = playerSize

        // Leg animation
        val legSwing = if (onGround) sin(runFrame.toDouble() * 10.0).toFloat() * s * 0.18f else 0f

        // Shadow
        paint.color = 0x33000000
        val shadowPct = 1f - ((groundY - playerSize - py) / groundY * 2f).coerceIn(0f, 0.7f)
        canvas.drawOval(x - s * 0.1f, groundY, x + s * 1.1f, groundY + s * 0.15f * shadowPct, paint)

        // Tail
        paint.color = C_MONKEY_BODY
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.1f
        val tailPath = Path().apply {
            moveTo(x + s * 0.1f, y + s * 0.7f)
            cubicTo(x - s * 0.4f, y + s * 0.9f, x - s * 0.5f, y + s * 1.3f, x - s * 0.2f, y + s * 1.2f)
        }
        canvas.drawPath(tailPath, paint)
        paint.style = Paint.Style.FILL

        // Back leg
        paint.color = C_MONKEY_BODY
        canvas.drawRoundRect(x + s * 0.3f, y + s * 0.75f + legSwing,
            x + s * 0.6f, y + s * 1.1f + legSwing, s * 0.1f, s * 0.1f, paint)
        // Front leg
        canvas.drawRoundRect(x + s * 0.4f, y + s * 0.75f - legSwing,
            x + s * 0.7f, y + s * 1.1f - legSwing, s * 0.1f, s * 0.1f, paint)

        // Arms (windmill when running)
        val armSwing = if (!onGround) s * 0.1f else sin(runFrame.toDouble() * 10.0).toFloat() * s * 0.15f
        canvas.drawRoundRect(x + s * 0.55f, y + s * 0.3f - armSwing,
            x + s * 0.9f, y + s * 0.55f - armSwing, s * 0.08f, s * 0.08f, paint)
        canvas.drawRoundRect(x + s * 0.05f, y + s * 0.3f + armSwing,
            x + s * 0.4f, y + s * 0.55f + armSwing, s * 0.08f, s * 0.08f, paint)

        // Body
        paint.color = C_MONKEY_BODY
        canvas.drawOval(x + s * 0.15f, y + s * 0.3f, x + s * 0.85f, y + s * 0.85f, paint)

        // Head
        canvas.drawOval(x + s * 0.1f, y, x + s * 0.9f, y + s * 0.65f, paint)

        // Ears
        paint.color = C_MONKEY_EAR
        canvas.drawOval(x,            y + s * 0.1f, x + s * 0.2f, y + s * 0.35f, paint)
        canvas.drawOval(x + s * 0.8f, y + s * 0.1f, x + s,        y + s * 0.35f, paint)

        // Face
        paint.color = C_MONKEY_FACE
        canvas.drawOval(x + s * 0.2f, y + s * 0.2f, x + s * 0.8f, y + s * 0.6f, paint)

        // Eyes
        paint.color = 0xFF1A1A1A.toInt()
        canvas.drawCircle(x + s * 0.35f, y + s * 0.28f, s * 0.07f, paint)
        canvas.drawCircle(x + s * 0.65f, y + s * 0.28f, s * 0.07f, paint)
        // Eye shine
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(x + s * 0.37f, y + s * 0.25f, s * 0.025f, paint)
        canvas.drawCircle(x + s * 0.67f, y + s * 0.25f, s * 0.025f, paint)

        // Smile
        paint.color = 0xFF5C3D1E.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.05f
        val smilePath = Path().apply {
            moveTo(x + s * 0.35f, y + s * 0.45f)
            quadTo(x + s * 0.5f,  y + s * 0.56f, x + s * 0.65f, y + s * 0.45f)
        }
        canvas.drawPath(smilePath, paint)
        paint.style = Paint.Style.FILL

        // Nose
        paint.color = 0xFF8B5E3C.toInt()
        canvas.drawOval(x + s * 0.42f, y + s * 0.36f, x + s * 0.58f, y + s * 0.44f, paint)
    }

    private fun drawObstacles(canvas: Canvas, h: Float) {
        obstacles.forEach { obs ->
            if (obs.isLog) {
                // Horizontal log
                paint.color = 0xFF795548.toInt()
                canvas.drawRoundRect(obs.x - 4f, groundY - obs.h, obs.x + obsW + 4f, groundY, 10f, 10f, paint)
                paint.color = 0xFF5D4037.toInt()
                // Log rings
                for (i in 0..2) {
                    val lx = obs.x + obsW * (0.25f + i * 0.25f)
                    paint.strokeWidth = 1.5f; paint.style = Paint.Style.STROKE
                    paint.color = 0xFF4E342E.toInt()
                    canvas.drawLine(lx, groundY - obs.h + 4f, lx, groundY - 4f, paint)
                    paint.style = Paint.Style.FILL
                }
                paint.color = C_ROCK_HI
                canvas.drawRoundRect(obs.x, groundY - obs.h, obs.x + obsW * 0.5f, groundY - obs.h + obsW * 0.4f, 4f, 4f, paint)
            } else {
                // Rock
                paint.color = C_ROCK
                canvas.drawRoundRect(obs.x, groundY - obs.h, obs.x + obsW, groundY, 10f, 10f, paint)
                paint.color = C_ROCK_HI
                canvas.drawRoundRect(obs.x + 3f, groundY - obs.h + 3f, obs.x + obsW * 0.55f, groundY - obs.h + obsW * 0.5f, 5f, 5f, paint)
                // Moss
                paint.color = 0xFF2E7D32.toInt()
                canvas.drawRoundRect(obs.x, groundY - obs.h, obs.x + obsW, groundY - obs.h + 6f, 6f, 6f, paint)
            }
        }
    }

    private fun drawBananas(canvas: Canvas, w: Float) {
        val cr = w * COIN_R
        bananas.filter { !it.collected }.forEach { b ->
            // Glow
            paint.color = C_BANANA; paint.alpha = 50
            canvas.drawCircle(b.x, b.y, cr * 1.8f, paint)
            paint.alpha = 255
            // Banana shape (arc)
            paint.color = C_BANANA
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = cr * 0.7f
            paint.strokeCap = Paint.Cap.ROUND
            val bPath = Path().apply {
                moveTo(b.x - cr * 0.7f, b.y + cr * 0.5f)
                quadTo(b.x, b.y - cr * 1.2f, b.x + cr * 0.7f, b.y + cr * 0.5f)
            }
            canvas.drawPath(bPath, paint)
            paint.color = C_BANANA_HI
            paint.strokeWidth = cr * 0.25f
            val bHi = Path().apply {
                moveTo(b.x - cr * 0.5f, b.y + cr * 0.2f)
                quadTo(b.x - cr * 0.1f, b.y - cr * 0.9f, b.x + cr * 0.3f, b.y + cr * 0.2f)
            }
            canvas.drawPath(bHi, paint)
            paint.style = Paint.Style.FILL
            paint.strokeCap = Paint.Cap.BUTT
        }
    }

    private fun drawHud(canvas: Canvas, w: Float) {
        textPaint.textSize = dp(22).toFloat()
        textPaint.color = C_TEXT
        canvas.drawText(score.roundToInt().toString(), w / 2f, dp(52).toFloat(), textPaint)

        textPaint.textSize = dp(12).toFloat()
        textPaint.color = C_BANANA
        canvas.drawText("🍌 $coins", w / 2f, dp(70).toFloat(), textPaint)

        textPaint.textSize = dp(11).toFloat()
        textPaint.color = C_MUTED
        canvas.drawText("BEST  $hiScore", w * 0.82f, dp(52).toFloat(), textPaint)

        // Timer
        val mins = (elapsed / 60).toInt()
        val secs = (elapsed % 60).toInt()
        val timeStr = "%d:%02d".format(mins, secs)
        textPaint.textSize = dp(11).toFloat()
        textPaint.color = C_ACCENT
        canvas.drawText(timeStr, w * 0.18f, dp(52).toFloat(), textPaint)
    }

    private fun drawBanner(canvas: Canvas, w: Float, msg: String, color: Int) {
        val bw = w * 0.88f; val bx = (w - bw) / 2f; val by = dp(82).toFloat()
        paint.color = C_CARD
        canvas.drawRoundRect(bx, by, bx + bw, by + dp(26).toFloat(), dp(8).toFloat(), dp(8).toFloat(), paint)
        textPaint.textSize = dp(11).toFloat(); textPaint.color = color
        canvas.drawText(msg, w / 2f, by + dp(18).toFloat(), textPaint)
    }

    private fun drawIdleCard(canvas: Canvas, w: Float, h: Float) {
        val cw = w * 0.88f; val cx = (w - cw) / 2f; val cy = h * 0.18f
        val ch = dp(260).toFloat()
        paint.color = C_CARD
        canvas.drawRoundRect(cx, cy, cx + cw, cy + ch, dp(22).toFloat(), dp(22).toFloat(), paint)
        paint.color = C_VINE; paint.alpha = 80; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1).toFloat()
        canvas.drawRoundRect(cx, cy, cx + cw, cy + ch, dp(22).toFloat(), dp(22).toFloat(), paint)
        paint.style = Paint.Style.FILL; paint.alpha = 255

        textPaint.textSize = dp(22).toFloat(); textPaint.color = C_ACCENT
        canvas.drawText("MonkeyRunner", w / 2f, cy + dp(38).toFloat(), textPaint)

        textPaint.textSize = dp(12).toFloat(); textPaint.color = 0xFFB0BEC5.toInt()
        canvas.drawText("Tap to start  •  Tap again to double jump", w / 2f, cy + dp(60).toFloat(), textPaint)

        textPaint.textSize = dp(11).toFloat(); textPaint.color = C_GOLD
        canvas.drawText("Best: $hiScore  •  $timeSessionsRemaining time sessions left today", w / 2f, cy + dp(78).toFloat(), textPaint)

        // Divider
        paint.color = C_VINE; paint.alpha = 40
        canvas.drawRect(cx + dp(20).toFloat(), cy + dp(92).toFloat(), cx + cw - dp(20).toFloat(), cy + dp(93).toFloat(), paint)
        paint.alpha = 255

        textPaint.textSize = dp(11).toFloat(); textPaint.color = C_MUTED
        canvas.drawText("🏆 SCORE REWARDS", w / 2f, cy + dp(108).toFloat(), textPaint)
        val scoreLines = listOf("10–29 pts → 5 tokens", "30–59 → 12", "60–99 → 25",
            "100–149 → 45", "150–199 → 70", "200+ → 120")
        scoreLines.forEachIndexed { i, line ->
            textPaint.textSize = dp(10).toFloat(); textPaint.color = 0xFF90A4AE.toInt()
            canvas.drawText(line, w / 2f, cy + dp(124 + i * 14).toFloat(), textPaint)
        }

        // Divider
        paint.color = C_VINE; paint.alpha = 40
        canvas.drawRect(cx + dp(20).toFloat(), cy + dp(212).toFloat(), cx + cw - dp(20).toFloat(), cy + dp(213).toFloat(), paint)
        paint.alpha = 255

        textPaint.textSize = dp(11).toFloat(); textPaint.color = C_GOLD
        canvas.drawText("⏱ TIME BONUS  (3 sessions/day)", w / 2f, cy + dp(226).toFloat(), textPaint)
        textPaint.textSize = dp(10).toFloat(); textPaint.color = 0xFF90A4AE.toInt()
        canvas.drawText("3m→+40  5m→+60  8m→+100  12m→+150  20m→+250", w / 2f, cy + dp(240).toFloat(), textPaint)
    }

    private fun drawDeadCard(canvas: Canvas, w: Float, h: Float) {
        paint.color = 0xCC071A05.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)

        val cw = w * 0.82f; val cx = (w - cw) / 2f; val cy = h * 0.18f
        val ch = dp(220).toFloat()
        paint.color = C_CARD
        canvas.drawRoundRect(cx, cy, cx + cw, cy + ch, dp(24).toFloat(), dp(24).toFloat(), paint)
        paint.color = C_RED; paint.alpha = 140; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1).toFloat()
        canvas.drawRoundRect(cx, cy, cx + cw, cy + ch, dp(24).toFloat(), dp(24).toFloat(), paint)
        paint.style = Paint.Style.FILL; paint.alpha = 255

        textPaint.textSize = dp(16).toFloat(); textPaint.color = C_RED
        canvas.drawText("Game Over", w / 2f, cy + dp(30).toFloat(), textPaint)

        textPaint.textSize = dp(34).toFloat(); textPaint.color = C_TEXT
        canvas.drawText(score.roundToInt().toString(), w / 2f, cy + dp(70).toFloat(), textPaint)

        // Time played
        val mins = (elapsed / 60).toInt(); val secs = (elapsed % 60).toInt()
        textPaint.textSize = dp(12).toFloat(); textPaint.color = C_MUTED
        canvas.drawText("Time: %d:%02d  •  🍌 $coins".format(mins, secs), w / 2f, cy + dp(90).toFloat(), textPaint)

        val s = score.roundToInt()
        if (s > 0 && s >= hiScore) {
            textPaint.textSize = dp(12).toFloat(); textPaint.color = C_GOLD
            canvas.drawText("★  NEW BEST  ★", w / 2f, cy + dp(110).toFloat(), textPaint)
        } else {
            textPaint.textSize = dp(12).toFloat(); textPaint.color = C_MUTED
            canvas.drawText("Best: $hiScore", w / 2f, cy + dp(110).toFloat(), textPaint)
        }

        // Token result
        val result = tokenResult
        when {
            result != null -> {
                paint.color = C_VINE; paint.alpha = 40
                canvas.drawRect(cx + dp(16).toFloat(), cy + dp(124).toFloat(), cx + cw - dp(16).toFloat(), cy + dp(125).toFloat(), paint)
                paint.alpha = 255

                if (result.tokensWon > 0) {
                    textPaint.textSize = dp(15).toFloat(); textPaint.color = C_ACCENT
                    canvas.drawText("+${result.tokensWon} tokens", w / 2f, cy + dp(144).toFloat(), textPaint)
                    val parts = mutableListOf<String>()
                    if (result.scoreTokens > 0) parts += "score +${result.scoreTokens}"
                    if (result.timeReward > 0)   parts += "time +${result.timeReward}"
                    if (parts.isNotEmpty()) {
                        textPaint.textSize = dp(10).toFloat(); textPaint.color = C_MUTED
                        canvas.drawText(parts.joinToString("  •  "), w / 2f, cy + dp(160).toFloat(), textPaint)
                    }
                    textPaint.textSize = dp(11).toFloat(); textPaint.color = C_GOLD
                    canvas.drawText("Balance: ${result.balance}  •  ${result.timeSessionsRemaining} time sessions left", w / 2f, cy + dp(176).toFloat(), textPaint)
                } else {
                    textPaint.textSize = dp(12).toFloat(); textPaint.color = C_MUTED
                    canvas.drawText("Score higher or play longer for tokens", w / 2f, cy + dp(148).toFloat(), textPaint)
                }
            }
            tokenError != null -> {
                textPaint.textSize = dp(11).toFloat(); textPaint.color = C_RED
                canvas.drawText(tokenError!!, w / 2f, cy + dp(148).toFloat(), textPaint)
            }
            else -> {
                textPaint.textSize = dp(11).toFloat(); textPaint.color = C_MUTED
                canvas.drawText("Claiming tokens...", w / 2f, cy + dp(148).toFloat(), textPaint)
            }
        }

        textPaint.textSize = dp(13).toFloat(); textPaint.color = C_ACCENT
        canvas.drawText("Tap anywhere to retry", w / 2f, cy + ch - dp(18).toFloat(), textPaint)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
