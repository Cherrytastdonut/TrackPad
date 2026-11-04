package com.example.wintrackpad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.util.SparseArray
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 흰 화면 전체가 트랙패드. 터치를 맥 트랙패드식 제스처로 해석해 HID 리포트로 바꾼다.
 *
 *  1손가락 이동        커서 이동 (가속 적용)
 *  1손가락 탭          왼쪽 클릭
 *  탭 → 바로 눌러 끌기  드래그 (왼쪽 버튼 누른 채 이동)
 *  2손가락 탭          오른쪽 클릭
 *  2손가락 이동        스크롤 (관성 포함)
 *  2손가락 벌리기/모으기 확대/축소 (Ctrl + 휠)
 *  3손가락 탭          가운데 클릭
 *  3손가락 좌/우       앱 전환 (Alt+Tab, 손 떼면 선택)
 *  3/4손가락 위        작업 보기 (Win+Tab)
 *  3/4손가락 아래      바탕 화면 보기 (Win+D)
 *  4손가락 좌/우       가상 데스크톱 전환 (Ctrl+Win+←/→)
 *  4손가락 탭          알림 센터 (Windows 11: Win+N, Windows 10: Win+A)
 *
 *  관성 스크롤 중 첫 접촉은 '멈춤 전용'이며 클릭을 만들지 않는다.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class TrackpadView(
    context: Context,
    private val hid: HidManager,
    settings: Settings
) : View(context) {

    private enum class Mode {
        IDLE, POINTER, DRAG, TWO_PENDING, SCROLL, PINCH,
        SWIPE_PENDING, SWIPE_SWITCH, SWIPE_DONE, WAIT_LIFT,
        /** 관성을 멈추려고 닿은 손가락. 움직이면 POINTER로 바뀌지만 클릭은 하지 않는다. */
        STOP_TOUCH
    }

    private enum class Axis { FREE, VERTICAL, HORIZONTAL }

    private companion object {
        const val TAP_MS = 200L
        const val MULTI_TAP_MS = 280L
        const val DOUBLE_TAP_MS = 300L

        /** 포인터: dp당 HID 카운트 기본값 */
        const val POINTER_BASE = 1.4f
        /** 스크롤: 손가락 1dp 당 1/120칸 단위 수 */
        const val SCROLL_UNITS_PER_DP = 3.2f
        /** 관성 스크롤 감쇠 시간상수(초) */
        const val FLING_TAU = 0.42f
    }

    private val dp = resources.displayMetrics.density

    private val pointerSpeed = settings.pointerSpeed
    private val scrollSpeed = settings.scrollSpeed
    private val natural = settings.naturalScroll
    private val tapToClick = settings.tapToClick
    private val tapDrag = settings.tapDrag
    private val twoFingerRightClick = settings.twoFingerRightClick
    private val threeFingerMiddleClick = settings.threeFingerMiddleClick
    private val windows11 = settings.windows11
    private val inertia = settings.inertia
    private val haptics = settings.hapticFeedback

    /** true면 모든 터치를 무시 (연결은 유지) */
    var paused = false
        set(v) {
            if (v && !field) cancelAll()
            field = v
        }

    /** 화면 아래 '끌기 고정' 버튼이 왼쪽 버튼을 누르고 있는 상태 */
    var dragLocked = false

    /** 끌기 고정 중 탭으로 놓았을 때 알림 (버튼 UI 갱신용) */
    var onDragLockReleased: (() -> Unit)? = null

    // 임계값 (px)
    private val tapSlop = 10 * dp
    private val multiTapSlop = 18 * dp
    private val scrollStart = 10 * dp
    private val pinchStart = 20 * dp
    private val pinchStep = 45 * dp
    private val swipeStart = 40 * dp
    private val switchStep = 80 * dp
    private val doubleTapMaxDist = 40 * dp
    private val flingMinStart = 250 * dp      // px/s
    private val flingMinKeep = 20 * dp        // px/s

    private var mode = Mode.IDLE
    private var committed = false

    private var activeId = -1
    private var lastX = 0f
    private var lastY = 0f
    private var lastMoveTime = 0L
    private var smoothV = 0f
    private var resX = 0f
    private var resY = 0f

    private var maxPointers = 0
    private var downTime = 0L
    private var maxDisp = 0f
    private val downPos = SparseArray<PointF>()

    private var lastTapUp = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var tapDragArmed = false

    private var startCx = 0f
    private var startCy = 0f
    private var lastCx = 0f
    private var lastCy = 0f
    private var startSpan = 0f
    private var lastSpan = 0f
    private var pinchAcc = 0f
    private var axis = Axis.FREE

    private var swipeFingers = 0
    private var switchAcc = 0f

    private var vt: VelocityTracker? = null
    private var flingVx = 0f
    private var flingVy = 0f
    private var pendingFling = false
    private var scrollLiftTime = 0L

    private val choreographer = Choreographer.getInstance()
    private var flinging = false
    private var lastFrameNs = 0L

    init {
        setBackgroundColor(Color.WHITE)
        isHapticFeedbackEnabled = true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 제스처 내비게이션의 좌우 '뒤로가기' 스와이프를 가능한 범위에서 막는다
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopFling()
        cancelGesture()
        vt?.recycle()
        vt = null
    }

    // ------------------------------------------------------------------
    // 터치 처리
    // ------------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (paused) return true
        val tracker = vt ?: VelocityTracker.obtain().also { vt = it }
        if (e.actionMasked == MotionEvent.ACTION_DOWN) tracker.clear()
        tracker.addMovement(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onFirstDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(e)
            MotionEvent.ACTION_UP -> onLastUp(e)
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        return true
    }

    private fun onFirstDown(e: MotionEvent) {
        val wasFlinging = flinging
        stopFling()
        downPos.clear()
        recordDown(e, 0)
        activeId = e.getPointerId(0)
        lastX = e.x
        lastY = e.y
        lastMoveTime = e.eventTime
        smoothV = 0f
        resX = 0f
        resY = 0f
        maxPointers = 1
        downTime = e.eventTime
        maxDisp = 0f
        committed = false
        pendingFling = false
        if (wasFlinging) {
            // 관성만 멈추는 접촉: 이번 제스처에서는 클릭·드래그를 만들지 않는다
            tapDragArmed = false
            committed = true
            lastTapUp = 0L
            mode = Mode.STOP_TOUCH
            return
        }
        tapDragArmed = tapDrag && !dragLocked &&
            e.eventTime - lastTapUp < DOUBLE_TAP_MS &&
            hypot(e.x - lastTapX, e.y - lastTapY) < doubleTapMaxDist
        mode = Mode.POINTER
    }

    private fun onPointerDown(e: MotionEvent) {
        recordDown(e, e.actionIndex)
        maxPointers = max(maxPointers, e.pointerCount)
        val n = e.pointerCount

        when (mode) {
            // 드래그 중이거나 스와이프 동작이 이미 실행됐다면 손가락 추가는 무시
            Mode.DRAG, Mode.SWIPE_SWITCH, Mode.SWIPE_DONE -> return
            Mode.PINCH -> {
                hid.keyboard(0)
            }
            else -> {}
        }

        val c = centroid(e)
        startCx = c.x; startCy = c.y
        lastCx = c.x; lastCy = c.y
        hid.resetScrollRemainder()

        if (n == 2) {
            mode = Mode.TWO_PENDING
            startSpan = span(e)
            lastSpan = startSpan
        } else if (n >= 3) {
            mode = Mode.SWIPE_PENDING
            swipeFingers = min(n, 4)
        }
    }

    private fun onMove(e: MotionEvent) {
        updateMaxDisp(e)

        when (mode) {
            Mode.STOP_TOUCH -> {
                if (maxDisp > tapSlop) {
                    // 멈춘 뒤 그대로 움직이면 커서 이동 (클릭은 여전히 억제)
                    val i = e.findPointerIndex(activeId)
                    if (i >= 0) {
                        lastX = e.getX(i); lastY = e.getY(i)
                    }
                    lastMoveTime = e.eventTime
                    mode = Mode.POINTER
                }
            }

            Mode.POINTER -> {
                if (tapDragArmed && maxDisp > tapSlop) {
                    tapDragArmed = false
                    committed = true
                    mode = Mode.DRAG
                    hid.setButtons(HidManager.BTN_LEFT)
                    haptic(Haptic.DRAG_START)
                }
                movePointer(e)
            }

            Mode.DRAG -> movePointer(e)

            Mode.TWO_PENDING -> {
                if (e.pointerCount < 2) return
                val c = centroid(e)
                val s = span(e)
                val cm = hypot(c.x - startCx, c.y - startCy)
                val sm = abs(s - startSpan)
                if (sm > pinchStart && sm > cm * 1.2f) {
                    mode = Mode.PINCH
                    committed = true
                    lastSpan = s
                    pinchAcc = 0f
                    hid.keyboard(HidManager.MOD_CTRL)
                } else if (cm > scrollStart && cm > sm * 0.8f) {
                    mode = Mode.SCROLL
                    committed = true
                    val ddx = abs(c.x - startCx)
                    val ddy = abs(c.y - startCy)
                    axis = when {
                        ddy > ddx * 2.2f -> Axis.VERTICAL
                        ddx > ddy * 2.2f -> Axis.HORIZONTAL
                        else -> Axis.FREE
                    }
                    // 임계값까지 움직인 만큼도 반영
                    doScroll(c.x - startCx, c.y - startCy)
                    lastCx = c.x; lastCy = c.y
                }
            }

            Mode.SCROLL -> {
                if (e.pointerCount < 2) return
                val c = centroid(e)
                doScroll(c.x - lastCx, c.y - lastCy)
                lastCx = c.x; lastCy = c.y
            }

            Mode.PINCH -> {
                if (e.pointerCount < 2) return
                val s = span(e)
                pinchAcc += (s - lastSpan) / pinchStep
                lastSpan = s
                val notches = pinchAcc.toInt()
                if (notches != 0) {
                    hid.scrollNotches(notches) // 벌리면 +(확대), 모으면 -(축소)
                    pinchAcc -= notches
                }
            }

            Mode.SWIPE_PENDING -> {
                if (e.pointerCount < 3) return
                val c = centroid(e)
                val dx = c.x - startCx
                val dy = c.y - startCy
                if (max(abs(dx), abs(dy)) > swipeStart) commitSwipe(dx, dy, c)
            }

            Mode.SWIPE_SWITCH -> {
                if (e.pointerCount < 3) return
                val c = centroid(e)
                switchAcc += c.x - lastCx
                lastCx = c.x
                while (switchAcc > switchStep) {
                    altTab(forward = true)
                    switchAcc -= switchStep
                }
                while (switchAcc < -switchStep) {
                    altTab(forward = false)
                    switchAcc += switchStep
                }
            }

            else -> {}
        }
    }

    private fun onPointerUp(e: MotionEvent) {
        val idx = e.actionIndex
        val id = e.getPointerId(idx)
        val remaining = e.pointerCount - 1

        when (mode) {
            Mode.DRAG -> if (id == activeId) {
                endDrag()
                mode = Mode.WAIT_LIFT
            }
            Mode.TWO_PENDING -> mode = Mode.WAIT_LIFT
            Mode.SCROLL -> {
                computeFling(e)
                scrollLiftTime = e.eventTime
                pendingFling = true
                mode = Mode.WAIT_LIFT
            }
            Mode.PINCH -> {
                hid.keyboard(0)
                mode = Mode.WAIT_LIFT
            }
            Mode.SWIPE_PENDING -> {
                if (remaining >= 3) {
                    val c = centroid(e, skip = idx)
                    startCx = c.x; startCy = c.y
                    swipeFingers = min(remaining, 4)
                } else {
                    mode = Mode.WAIT_LIFT
                }
            }
            Mode.SWIPE_SWITCH -> {
                val c = centroid(e, skip = idx)
                lastCx = c.x
            }
            else -> {}
        }
    }

    private fun onLastUp(e: MotionEvent) {
        val dur = e.eventTime - downTime

        when (mode) {
            Mode.POINTER -> {
                val isTap = !committed && maxPointers == 1 && dur < TAP_MS && maxDisp < tapSlop
                if (isTap && dragLocked) {
                    // 끌기 고정 중의 탭 = 놓기
                    dragLocked = false
                    hid.setButtons(0)
                    haptic(Haptic.DRAG_END)
                    onDragLockReleased?.invoke()
                } else if (isTap && tapToClick) {
                    hid.click(HidManager.BTN_LEFT)
                    haptic(Haptic.CLICK)
                    lastTapUp = e.eventTime
                    lastTapX = e.x
                    lastTapY = e.y
                }
            }
            Mode.DRAG -> endDrag()
            Mode.PINCH -> hid.keyboard(0)
            Mode.SWIPE_SWITCH -> hid.keyboard(0) // Alt 떼기 → 선택한 창으로 전환
            Mode.SCROLL -> {
                computeFling(e)
                startFling()
            }
            Mode.TWO_PENDING, Mode.SWIPE_PENDING, Mode.WAIT_LIFT -> {
                if (!committed && dur < MULTI_TAP_MS && maxDisp < multiTapSlop) {
                    multiFingerTap(maxPointers)
                }
                if (pendingFling && e.eventTime - scrollLiftTime < 120) startFling()
            }
            else -> {}
        }
        pendingFling = false
        mode = Mode.IDLE
    }

    /** 진행 중인 제스처·관성을 모두 끝내고 눌린 버튼·키를 뗀다 (앱 전환, 일시정지 등) */
    fun cancelAll() {
        stopFling()
        cancelGesture()
        if (dragLocked) {
            dragLocked = false
            onDragLockReleased?.invoke()
        }
        hid.releaseAll()
    }

    private fun endDrag() {
        hid.setButtons(0)
        haptic(Haptic.DRAG_END)
    }

    private fun cancelGesture() {
        when (mode) {
            Mode.DRAG -> hid.setButtons(0)
            Mode.PINCH, Mode.SWIPE_SWITCH -> hid.keyboard(0)
            else -> {}
        }
        pendingFling = false
        mode = Mode.IDLE
    }

    // ------------------------------------------------------------------
    // 동작
    // ------------------------------------------------------------------

    private fun movePointer(e: MotionEvent) {
        val i = e.findPointerIndex(activeId)
        if (i < 0) return
        val x = e.getX(i)
        val y = e.getY(i)
        val dx = x - lastX
        val dy = y - lastY
        lastX = x
        lastY = y

        val dt = max(1L, e.eventTime - lastMoveTime).toFloat()
        lastMoveTime = e.eventTime
        val v = hypot(dx, dy) / dp / dt          // dp/ms
        smoothV = smoothV * 0.5f + v * 0.5f

        // 느리게 움직이면 정밀하게, 빠르게 움직이면 멀리 (포인터 가속)
        val gain = pointerSpeed * POINTER_BASE * (0.75f + 1.25f * min(smoothV, 3f))
        resX += dx / dp * gain
        resY += dy / dp * gain
        val ix = resX.toInt()
        val iy = resY.toInt()
        resX -= ix
        resY -= iy
        hid.move(ix, iy)
    }

    private fun doScroll(dxPx: Float, dyPx: Float) {
        val k = scrollSpeed * SCROLL_UNITS_PER_DP / dp
        // HID 휠: +는 위로 스크롤. 자연 스크롤이면 손가락을 올릴 때 아래로 스크롤된다.
        var v = dyPx * k
        var h = -dxPx * k
        if (!natural) {
            v = -v; h = -h
        }
        when (axis) {
            Axis.VERTICAL -> h = 0f
            Axis.HORIZONTAL -> v = 0f
            Axis.FREE -> {}
        }
        hid.scroll(v, h)
    }

    private fun multiFingerTap(fingers: Int) {
        when (fingers) {
            2 -> if (twoFingerRightClick) hid.click(HidManager.BTN_RIGHT) else return
            3 -> if (threeFingerMiddleClick) hid.click(HidManager.BTN_MIDDLE) else return
            // 알림 센터: Windows 11은 Win+N, Windows 10은 Win+A
            4 -> hid.keyCombo(HidManager.MOD_WIN, if (windows11) HidManager.KEY_N else HidManager.KEY_A)
            else -> return
        }
        haptic(Haptic.CLICK)
    }

    private fun commitSwipe(dx: Float, dy: Float, c: PointF) {
        committed = true
        val horizontal = abs(dx) > abs(dy)
        if (horizontal) {
            if (swipeFingers >= 4) {
                // 왼쪽으로 밀면 오른쪽 데스크톱으로 (맥/Windows 기본 동작과 동일)
                val key = if (dx < 0) HidManager.KEY_RIGHT else HidManager.KEY_LEFT
                hid.keyCombo(HidManager.MOD_CTRL or HidManager.MOD_WIN, key)
                mode = Mode.SWIPE_DONE
            } else {
                hid.keyboard(HidManager.MOD_ALT)
                altTab(forward = dx > 0)
                switchAcc = 0f
                lastCx = c.x
                mode = Mode.SWIPE_SWITCH
            }
        } else {
            if (dy < 0) hid.keyCombo(HidManager.MOD_WIN, HidManager.KEY_TAB)   // 작업 보기
            else hid.keyCombo(HidManager.MOD_WIN, HidManager.KEY_D)            // 바탕 화면
            mode = Mode.SWIPE_DONE
        }
        haptic(Haptic.GESTURE)
    }

    private fun altTab(forward: Boolean) {
        val alt = HidManager.MOD_ALT
        if (forward) {
            hid.keySequence(alt to HidManager.KEY_TAB, alt to 0)
        } else {
            hid.keySequence(
                (alt or HidManager.MOD_SHIFT) to HidManager.KEY_TAB,
                alt to 0
            )
        }
    }

    // ------------------------------------------------------------------
    // 관성 스크롤
    // ------------------------------------------------------------------

    private fun computeFling(e: MotionEvent) {
        val t = vt ?: return
        t.computeCurrentVelocity(1000, 12000 * dp)
        var sx = 0f
        var sy = 0f
        val n = e.pointerCount
        for (i in 0 until n) {
            val id = e.getPointerId(i)
            sx += t.getXVelocity(id)
            sy += t.getYVelocity(id)
        }
        flingVx = sx / n
        flingVy = sy / n
    }

    private fun startFling() {
        if (!inertia || hypot(flingVx, flingVy) < flingMinStart) return
        flinging = true
        lastFrameNs = System.nanoTime()
        choreographer.removeFrameCallback(frameCallback)
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopFling() {
        flinging = false
        choreographer.removeFrameCallback(frameCallback)
        hid.resetScrollRemainder()
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!flinging) return
            val dt = ((frameTimeNanos - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.05f)
            lastFrameNs = frameTimeNanos
            val decay = exp(-dt / FLING_TAU)
            flingVx *= decay
            flingVy *= decay
            doScroll(flingVx * dt, flingVy * dt)
            if (hypot(flingVx, flingVy) < flingMinKeep) {
                flinging = false
                return
            }
            choreographer.postFrameCallback(this)
        }
    }

    // ------------------------------------------------------------------
    // 유틸
    // ------------------------------------------------------------------

    private fun haptic(kind: Haptic) = Haptics.perform(this, kind, haptics)

    private fun recordDown(e: MotionEvent, index: Int) {
        downPos.put(e.getPointerId(index), PointF(e.getX(index), e.getY(index)))
    }

    private fun updateMaxDisp(e: MotionEvent) {
        for (i in 0 until e.pointerCount) {
            val p = downPos.get(e.getPointerId(i)) ?: continue
            val d = hypot(e.getX(i) - p.x, e.getY(i) - p.y)
            if (d > maxDisp) maxDisp = d
        }
    }

    private fun centroid(e: MotionEvent, skip: Int = -1): PointF {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            sx += e.getX(i); sy += e.getY(i); n++
        }
        return if (n == 0) PointF() else PointF(sx / n, sy / n)
    }

    /** 처음 두 손가락 사이 거리 */
    private fun span(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        return hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    }
}
