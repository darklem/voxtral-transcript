package cat.canigo.voxtral

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

class FloatingEmojiView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val emojis = listOf("🦄", "💜", "🎈", "💖", "🌸", "✨", "🎀", "💕")
    private val paint = Paint().apply { textSize = 60f }
    private val particles = mutableListOf<Particle>()
    private var startTime = System.currentTimeMillis()

    data class Particle(
        val emoji: String,
        val x: Float,
        val baseY: Float,
        val speed: Float,
        val phase: Float,
        val size: Float,
        val swayAmount: Float
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        particles.clear()
        val rng = Random(42)
        repeat(18) {
            particles.add(Particle(
                emoji = emojis[rng.nextInt(emojis.size)],
                x = rng.nextFloat() * w,
                baseY = rng.nextFloat() * h,
                speed = 0.3f + rng.nextFloat() * 0.4f,
                phase = rng.nextFloat() * Math.PI.toFloat() * 2,
                size = 40f + rng.nextFloat() * 40f,
                swayAmount = 20f + rng.nextFloat() * 30f
            ))
        }
    }

    override fun onDraw(canvas: Canvas) {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000f
        val h = height.toFloat()

        for (p in particles) {
            paint.textSize = p.size
            val y = ((p.baseY - elapsed * p.speed * 80) % h + h) % h
            val x = p.x + sin((elapsed * p.speed + p.phase).toDouble()).toFloat() * p.swayAmount
            canvas.drawText(p.emoji, x, y, paint)
        }
        postInvalidateDelayed(32) // ~30fps
    }
}
