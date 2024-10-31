package com.gotokeep.keep.commonui.widget.pop

import android.animation.Animator
import android.animation.AnimatorSet
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.View
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.gotokeep.keep.common.extensions.dp
import com.gotokeep.keep.common.extensions.setVisible
import com.gotokeep.keep.common.extensions.updateLayoutParamsSafely
import com.gotokeep.keep.common.utils.ActivityUtils
import com.gotokeep.keep.common.utils.RR
import com.gotokeep.keep.common.utils.ViewUtils
import com.gotokeep.keep.commonui.R
import com.gotokeep.keep.commonui.utils.AnimatorUtils
import com.gotokeep.keep.commonui.view.TipsWindowCallbackProxy
import com.gotokeep.keep.logger.KLog
import kotlinx.android.synthetic.main.view_tips_pop.view.buttonNegative
import kotlinx.android.synthetic.main.view_tips_pop.view.buttonPositive
import kotlinx.android.synthetic.main.view_tips_pop.view.layoutButton
import java.lang.ref.WeakReference

class KeepToolTips private constructor(builder: Builder) {

    /**
     * 上下位置时箭头的宽度，左右时是高度
     */
    private val arrowSizeLong = 10.dp

    /**
     * 上下位置时箭头的高度，左右时是宽度
     */
    private val arrowSizeShort = 8.dp

    /**
     * 偏左 or 偏右 时，箭头离气泡边缘的距离，可由 arrowOffsetX 自定义
     */
    private var arrowDistanceFromEdge = 16.dp
    private val marginToAnchor = 12.dp
    private val animatorDuration = 200
    private val animatorAlphaMin = 0.0f
    private val animatorAlphaMax = 1.0f
    private val animatorScaleXMin = 0.0f
    private val animatorScaleXMax = 1.0f
    private val animatorScaleYMin = 0.0f
    private val animatorScaleYMax = 1.0f

    private var popupWindow: PopupWindow? = null
    val windowHeight: Int
    val windowWidth: Int
    private val direction: Int
    private val resident: Boolean
    private val focusable: Boolean
    private val quickAction: QuickAction?
    private val contentView: View?
    private val delayDismiss = Runnable { dismiss() }
    private val rootView: ViewGroup
    private val shouldShowCloseIcon: Boolean
    private val contentMaxLines: Int
    private val contentLineMaxLength: Int
    private var activity: Activity? = null
    private var delayTime = 3000L

    init {
        keepToolTipsRef = WeakReference(this)
        rootView = ViewUtils.newInstance(builder.context, R.layout.view_tips_pop) as ViewGroup
        rootView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        if (builder.context is Activity) {
            activity = builder.context
        }
        contentMaxLines = builder.contentMaxLines
        contentLineMaxLength = builder.contentLineMaxLength
        val layoutMessage = rootView.findViewById<RelativeLayout>(R.id.layoutMessage)
        val imageIcon = rootView.findViewById<ImageView>(R.id.imageTipIcon)
        if (builder.iconResource != 0) {
            imageIcon.setVisible()
            imageIcon.setImageResource(builder.iconResource)
            layoutMessage.updateLayoutParamsSafely<RelativeLayout.LayoutParams> {
                marginStart = 15.dp
                topMargin = 11.dp
                rootView.layoutParams = this
            }
        }
        contentView = when (builder.contentView) {
            null -> createTextView(builder.context, builder.message)
            else -> builder.contentView
        }
        if (contentView?.id == View.NO_ID) {
            contentView.id = R.id.tips_content
        }
        contentView?.measure(0, 0)
        layoutMessage.addView(contentView)

        when (builder.style) {
            DARK -> layoutMessage.setBackgroundResource(R.drawable.bg_shape_tips_dark)
            PURPLE -> layoutMessage.setBackgroundResource(R.drawable.bg_shape_tips_purple)
            LIGHT -> layoutMessage.setBackgroundResource(R.drawable.bg_shape_tips_green)
            RED -> layoutMessage.setBackgroundResource(R.drawable.bg_shape_tips_red)
        }

        direction = builder.direction
        resident = builder.resident
        focusable = builder.focusable
        shouldShowCloseIcon = builder.shouldShowCloseIcon
        if (builder.arrowOffsetX > 0) {
            arrowDistanceFromEdge = builder.arrowOffsetX
        }
        delayTime = builder.delayTime
        showArrow(builder.style)

        rootView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        when {
            resident -> {
                if (shouldShowCloseIcon && builder.onPositiveCallback == null) {
                    builder.onPositive(RR.getString(R.string.close), object: TipsButtonCallback {
                        override fun onClick(action: Action) = Unit
                    })
                }
                showButton(rootView, builder)
                rootView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            }
        }
        windowHeight = rootView.measuredHeight
        windowWidth = rootView.measuredWidth
        popupWindow = PopupWindow(rootView)
        quickAction = builder.quickAction
        if (builder.closeWhenTouchInside) {
            rootView.setOnClickListener {
                quickAction?.onQuickAction()
                dismiss()
            }
        }
        popupWindow?.width = windowWidth
        popupWindow?.height = windowHeight
        popupWindow?.setBackgroundDrawable(ColorDrawable(RR.getColor(R.color.transparent)))
        popupWindow?.isFocusable = focusable
        popupWindow?.isOutsideTouchable = true
        popupWindow?.setOnDismissListener(builder.onDismissListener)
        val closeWhenTouchOutside = builder.closeWhenTouchOutside
        popupWindow?.setTouchInterceptor { _, event ->
            when (event.action) {
                MotionEvent.ACTION_OUTSIDE -> {
                    return@setTouchInterceptor true
                }
                MotionEvent.ACTION_DOWN -> {
                    val x = event.x.toInt()
                    val y = event.y.toInt()

                    if (x < 0 || x >= windowWidth || y < 0 || y >= windowHeight) {
                        if (closeWhenTouchOutside) {
                            dismiss()
                        }
                        return@setTouchInterceptor true
                    }
                    return@setTouchInterceptor false
                }
                else -> return@setTouchInterceptor false
            }
        }
    }

    /**
     * 显示button(close按钮替换为button)
     * 只有一个按钮，居右显示；
     * 两个时候在文字下方
     */
    private fun showButton(rootView: ViewGroup, builder: Builder) {
        if (builder.onPositiveCallback == null) return

        builder.onPositiveCallback?.let {
            rootView.buttonPositive.visibility = VISIBLE
            rootView.buttonPositive.text = builder.positiveText
            rootView.buttonPositive.setOnClickListener {
                builder.onPositiveCallback?.onClick(Action.POSITIVE)
                dismiss()
            }

            var color = RR.getColor(R.color.light_green)
            when (builder.style) {
                DARK -> color = RR.getColor(R.color.three_black)
                PURPLE -> color = RR.getColor(R.color.slate_blue_light)
                RED -> color = RR.getColor(R.color.color_ff5461)
            }
            rootView.buttonPositive.setTextColor(color)
        }
        builder.onNegativeCallback?.let {
            rootView.buttonNegative.visibility = VISIBLE
            rootView.buttonNegative.text = builder.negativeText
            rootView.buttonNegative.setOnClickListener {
                builder.onNegativeCallback?.onClick(Action.NEGATIVE)
                dismiss()
            }
        }

        val singleButton = builder.onPositiveCallback != null && builder.onNegativeCallback == null
        val buttonLayoutParams = rootView.layoutButton.layoutParams as RelativeLayout.LayoutParams
        val contentParams = contentView?.layoutParams as RelativeLayout.LayoutParams
        if (contentView is TextView && contentView.lineCount == 1 && singleButton) {
            buttonLayoutParams.addRule(RelativeLayout.CENTER_VERTICAL)
            contentParams.addRule(RelativeLayout.CENTER_VERTICAL)
            buttonLayoutParams.addRule(RelativeLayout.END_OF, R.id.tips_content)
            buttonLayoutParams.marginStart = 12.dp
        } else {
            buttonLayoutParams.addRule(RelativeLayout.BELOW, R.id.tips_content)
            buttonLayoutParams.topMargin = 6.dp
        }
    }

    private fun createTextView(context: Context, message: String?): View {
        val messageView = TextView(context)
        messageView.textSize = 14f
        messageView.setTextColor(RR.getColor(R.color.white))
        // 单行显示 12 字，最多显示 24 个字
        messageView.maxWidth = ViewUtils.spToPx(contentLineMaxLength * 14)
        messageView.maxLines = contentMaxLines
        messageView.includeFontPadding = false
        messageView.text = message
        return messageView
    }

    private fun showArrow(style: Int) {
        val arrowWidth: Int
        val arrowHeight: Int
        val arrowView = ImageView(rootView.context)
        arrowView.id = R.id.img_icon_arrow_up
        when (direction) {
            LEFT, RIGHT -> {
                arrowWidth = this.arrowSizeShort
                arrowHeight = this.arrowSizeLong
            }
            else -> {
                arrowWidth = this.arrowSizeLong
                arrowHeight = this.arrowSizeShort
            }
        }
        val arrowDrawable = ArrowDrawable(
            RR.getColor(
                when (style) {
                    DARK -> R.color.three_black
                    PURPLE -> R.color.slate_blue_light
                    RED -> R.color.color_ff5461
                    else -> R.color.light_green
                }
            ), direction
        )
        arrowView.background = arrowDrawable
        val arrowLayoutParams = RelativeLayout.LayoutParams(arrowWidth, arrowHeight)
        when {
            direction and TOP != 0 -> arrowLayoutParams.addRule(
                RelativeLayout.BELOW,
                R.id.layoutMessage
            )
            direction == LEFT -> arrowLayoutParams.addRule(
                RelativeLayout.RIGHT_OF,
                R.id.layoutMessage
            )
        }
        when {
            direction == BOTTOM || direction == TOP -> arrowLayoutParams.addRule(RelativeLayout.CENTER_HORIZONTAL)
            direction == LEFT || direction == RIGHT -> arrowLayoutParams.addRule(RelativeLayout.CENTER_VERTICAL)
            direction and KEEP_LEFT != 0 -> {
                arrowLayoutParams.leftMargin = arrowDistanceFromEdge
            }
            direction and KEEP_RIGHT != 0 -> {
                arrowLayoutParams.rightMargin = arrowDistanceFromEdge
                arrowLayoutParams.addRule(RelativeLayout.ALIGN_RIGHT, R.id.layoutMessage)
            }
        }
        rootView.addView(arrowView, arrowLayoutParams)

        val layoutMessage = rootView.findViewById<View>(R.id.layoutMessage)
        val messageLayoutParams = layoutMessage.layoutParams as RelativeLayout.LayoutParams
        when {
            direction == RIGHT -> messageLayoutParams.addRule(RelativeLayout.RIGHT_OF, arrowView.id)
            direction and BOTTOM != 0 -> messageLayoutParams.addRule(
                RelativeLayout.BELOW,
                arrowView.id
            )
        }
        layoutMessage.layoutParams = messageLayoutParams
    }

    /**
     * 外部动态更新contentView（TextView）中内容
     * */
    fun updateContent(updateFunc: (View) -> Unit) {
        contentView?.let {
            updateFunc(it)
        }
    }

    fun show(anchor: View, offsetX: Int? = null, offsetY: Int? = null, marginToAnchorOffsetY: Int? = null) {
        anchor.post {
            if (!ActivityUtils.isActivityAlive(rootView.context)) {
                KLog.COMMON.d("KeepToolTips", "Unable to add window; is your activity running?")
                return@post
            }
            try {
                popupWindow?.showAsDropDown(
                    anchor,
                    offsetX ?: calculateOffsetX(anchor),
                    offsetY ?: calculateOffsetY(anchor, marginToAnchorOffsetY)
                )
            } catch (ignore: Exception) {
                // ignore
            }
            when {
                !resident -> popupWindow?.contentView?.postDelayed(delayDismiss, delayTime)
            }
            animatorEnter()
        }
    }

    fun isShowing(): Boolean {
        return popupWindow?.isShowing == true
    }

    fun dismiss() {
        if (isShowing()) {
            animatorExit()
        }
        if (mTipCallback != null) {
            removeWindowTouch()
        }
    }

    private fun animatorEnter() {
        val animatorSet = AnimatorSet()
        val pivotXPivotY = getPivotXY()
        val pivotX = pivotXPivotY.first
        val pivotY = pivotXPivotY.second
        val animatorAlpha =
            AnimatorUtils.fade(rootView, animatorAlphaMin, animatorAlphaMax, animatorDuration)
        val animatorScaleX = AnimatorUtils.scaleX(
            rootView,
            pivotX,
            pivotY,
            animatorScaleXMin,
            animatorScaleXMax,
            animatorDuration
        )
        val animatorScaleY = AnimatorUtils.scaleY(
            rootView,
            pivotX,
            pivotY,
            animatorScaleYMin,
            animatorScaleYMax,
            animatorDuration
        )
        animatorSet.playTogether(animatorAlpha, animatorScaleX, animatorScaleY)
        animatorSet.start()
    }

    fun calculateOffsetX(anchor: View): Int {
        return when {
            direction == BOTTOM || direction == TOP -> anchor.measuredWidth / 2 - windowWidth / 2
            direction and KEEP_RIGHT != 0 -> anchor.measuredWidth / 2 - (windowWidth - arrowDistanceFromEdge - arrowSizeLong / 2)
            direction and KEEP_LEFT != 0 -> anchor.measuredWidth / 2 - (arrowDistanceFromEdge + arrowSizeLong / 2)
            direction == RIGHT -> anchor.measuredWidth + marginToAnchor
            direction == LEFT -> -windowWidth - marginToAnchor
            else -> 0
        }
    }

    fun calculateOffsetY(anchor: View, marginToAnchorOffsetY: Int? = null): Int {
        return when {
            direction and TOP != 0 -> -windowHeight - anchor.measuredHeight - (marginToAnchorOffsetY ?: marginToAnchor)
            direction == LEFT || direction == RIGHT -> -anchor.measuredHeight / 2 - windowHeight / 2
            direction and BOTTOM != 0 -> marginToAnchor
            else -> 0
        }
    }

    private fun animatorExit() {
        val animatorSet = AnimatorSet()
        val pivotXPivotY = getPivotXY()
        val pivotX = pivotXPivotY.first
        val pivotY = pivotXPivotY.second
        val animatorAlpha =
            AnimatorUtils.fade(rootView, animatorAlphaMax, animatorAlphaMin, animatorDuration)
        val animatorScaleX = AnimatorUtils.scaleX(
            rootView,
            pivotX,
            pivotY,
            animatorScaleXMax,
            animatorScaleYMin,
            animatorDuration
        )
        val animatorScaleY = AnimatorUtils.scaleY(
            rootView,
            pivotX,
            pivotY,
            animatorScaleYMax,
            animatorScaleYMin,
            animatorDuration
        )
        animatorSet.playTogether(animatorAlpha, animatorScaleX, animatorScaleY)
        animatorSet.addListener(object: Animator.AnimatorListener {
            override fun onAnimationRepeat(animation: Animator?) {
            }

            override fun onAnimationCancel(animation: Animator?) {
            }

            override fun onAnimationStart(animation: Animator?) {
            }

            override fun onAnimationEnd(animation: Animator?) {
                try {
                    popupWindow?.dismiss()
                } catch (ignore: Exception) {
                }
            }
        })
        animatorSet.start()
    }

    var mTipCallback: TipsWindowCallbackProxy? = null
    var activityCallback: Window.Callback? = null

    /**
     * 通过代理监听window的事件分发，触发自动关闭
     */
    private fun listeningWindowTouch() {
        if (isShowing() && activity != null && ActivityUtils.isActivityAlive(activity)) {
            activityCallback = activity?.window?.callback
            activityCallback?.let {
                mTipCallback = ToolTipsWindowCallbackProxy(it)
                activity?.window?.callback = mTipCallback
            }
        }
    }

    inner class ToolTipsWindowCallbackProxy(callback: Window.Callback):
        TipsWindowCallbackProxy(callback) {

        override fun dispatchTouchEvent(event: MotionEvent?, windowCallback: Window.Callback?) {
            dismiss()
        }
    }

    private fun removeWindowTouch() {
        activityCallback?.let {
            if (activity != null && ActivityUtils.isActivityAlive(activity)) {
                activity?.window?.callback = activityCallback
            }
        }
        mTipCallback = null
    }

    private fun getPivotXY(): Pair<Int, Int> {
        val pivotX: Int
        val pivotY: Int
        when (direction) {
            LEFT -> {
                pivotX = windowWidth
                pivotY = windowHeight / 2
            }
            RIGHT -> {
                pivotX = 0
                pivotY = windowHeight / 2
            }
            TOP -> {
                pivotX = windowWidth / 2
                pivotY = windowHeight
            }
            BOTTOM -> {
                pivotX = windowWidth / 2
                pivotY = 0
            }
            (TOP or KEEP_LEFT) -> {
                pivotX = arrowDistanceFromEdge + arrowSizeLong / 2
                pivotY = windowHeight
            }
            (TOP or KEEP_RIGHT) -> {
                pivotX = windowWidth - arrowDistanceFromEdge - arrowSizeLong / 2
                pivotY = windowHeight
            }
            (BOTTOM or KEEP_LEFT) -> {
                pivotX = arrowDistanceFromEdge + arrowSizeLong / 2
                pivotY = 0
            }
            (BOTTOM or KEEP_RIGHT) -> {
                pivotX = windowWidth - arrowDistanceFromEdge - arrowSizeLong / 2
                pivotY = 0
            }
            else -> {
                pivotX = windowWidth / 2
                pivotY = windowHeight / 2
            }
        }
        return Pair(pivotX, pivotY)
    }

    class Builder(val context: Context) {
        var focusable: Boolean = true
        var message: String? = null
        var direction: Int = 0
        var resident: Boolean = false
        var style: Int = 0
        var quickAction: QuickAction? = null
        var contentView: View? = null
        var onDismissListener: PopupWindow.OnDismissListener? = null
        var shouldShowCloseIcon: Boolean = true
        var closeWhenTouchOutside: Boolean = true
        var closeWhenTouchInside: Boolean = true
        var contentMaxLines: Int = CONTENT_MAX_LINES
        var contentLineMaxLength = CONTENT_LINE_MAX_LENGTH
        var arrowOffsetX: Int = 0

        var positiveText: String? = null
        var negativeText: String? = null
        var onPositiveCallback: TipsButtonCallback? = null
        var onNegativeCallback: TipsButtonCallback? = null
        var delayTime = 3000L
        var iconResource: Int = 0

        fun message(message: String): Builder {
            this.message = message
            return this
        }

        fun delayTime(delayTime: Long): Builder {
            this.delayTime = delayTime
            return this
        }

        fun message(@StringRes message: Int): Builder {
            this.message = RR.getString(message)
            return this
        }

        fun contentView(contentView: View): Builder {
            this.contentView = contentView
            return this
        }

        fun direction(direction: Int): Builder {
            this.direction = direction
            return this
        }

        fun resident(resident: Boolean): Builder {
            this.resident = resident
            return this
        }

        fun contentMaxLines(contentMaxLines: Int): Builder {
            this.contentMaxLines = contentMaxLines
            return this
        }

        fun contentLineMaxLength(contentLineMaxLength: Int): Builder {
            this.contentLineMaxLength = contentLineMaxLength
            return this
        }

        fun focusable(focusable: Boolean): Builder {
            this.focusable = focusable
            return this
        }

        fun style(style: Int): Builder {
            this.style = style
            return this
        }

        fun shouldShowCloseIcon(shouldShowCloseIcon: Boolean): Builder {
            this.shouldShowCloseIcon = shouldShowCloseIcon
            return this
        }

        fun onDismissListener(onDismissListener: PopupWindow.OnDismissListener): Builder {
            this.onDismissListener = onDismissListener
            return this
        }

        fun onPositive(buttonText: String, callback: TipsButtonCallback): Builder {
            this.onPositiveCallback = callback
            this.positiveText = buttonText
            return this
        }

        fun onNegative(buttonText: String, callback: TipsButtonCallback): Builder {
            this.onNegativeCallback = callback
            this.negativeText = buttonText
            return this
        }

        fun quickAction(quickAction: QuickAction): Builder {
            this.quickAction = quickAction
            return this
        }

        fun closeWhenTouchOutside(closeWhenTouchOutside: Boolean): Builder {
            this.closeWhenTouchOutside = closeWhenTouchOutside
            return this
        }

        fun closeWhenTouchInside(closeWhenTouchInside: Boolean): Builder {
            this.closeWhenTouchInside = closeWhenTouchInside
            return this
        }

        fun arrowOffsetX(arrowOffsetX: Int): Builder {
            this.arrowOffsetX = arrowOffsetX
            return this
        }

        fun iconResource(@DrawableRes iconResource: Int): Builder {
            this.iconResource = iconResource
            return this
        }

        fun build(): KeepToolTips {
            return KeepToolTips(this)
        }

        fun show(anchor: View) {
            build().show(anchor)
        }
    }

    private class ArrowDrawable(
        @ColorInt foregroundColor: Int,
        private val mDirection: Int
    ): ColorDrawable() {
        private val mPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val mBackgroundColor: Int = Color.TRANSPARENT
        private var mPath: Path? = null

        init {
            this.mPaint.color = foregroundColor
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            updatePath(bounds)
        }

        @Synchronized
        private fun updatePath(bounds: Rect) {
            mPath = Path()

            when {
                mDirection == RIGHT -> {
                    mPath?.apply {
                        moveTo(bounds.width().toFloat(), bounds.height().toFloat())
                        lineTo(0f, (bounds.height() / 2).toFloat())
                        lineTo(bounds.width().toFloat(), 0f)
                        lineTo(bounds.width().toFloat(), bounds.height().toFloat())
                    }
                }
                mDirection and BOTTOM != 0 -> {
                    mPath?.apply {
                        moveTo(0f, bounds.height().toFloat())
                        lineTo((bounds.width() / 2).toFloat(), 0f)
                        lineTo(bounds.width().toFloat(), bounds.height().toFloat())
                        lineTo(0f, bounds.height().toFloat())
                    }
                }
                mDirection == LEFT -> {
                    mPath?.apply {
                        moveTo(0f, 0f)
                        lineTo(bounds.width().toFloat(), (bounds.height() / 2).toFloat())
                        lineTo(0f, bounds.height().toFloat())
                        lineTo(0f, 0f)
                    }
                }
                mDirection and TOP != 0 -> {
                    mPath?.apply {
                        moveTo(0f, 0f)
                        lineTo((bounds.width() / 2).toFloat(), bounds.height().toFloat())
                        lineTo(bounds.width().toFloat(), 0f)
                        lineTo(0f, 0f)
                    }
                }
            }

            mPath?.close()
        }

        override fun draw(canvas: Canvas) {
            canvas.drawColor(mBackgroundColor)
            when (mPath) {
                null -> updatePath(bounds)
            }
            canvas.drawPath(mPath!!, mPaint)
        }

        override fun setAlpha(alpha: Int) {
            mPaint.alpha = alpha
        }

        override fun setColor(@ColorInt color: Int) {
            mPaint.color = color
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            mPaint.colorFilter = colorFilter
        }

        override fun getOpacity(): Int {
            when {
                mPaint.colorFilter != null -> return PixelFormat.TRANSLUCENT
                else -> {
                    when (mPaint.color.ushr(24)) {
                        255 -> return PixelFormat.OPAQUE
                        0 -> return PixelFormat.TRANSPARENT
                        else -> {
                        }
                    }
                    return PixelFormat.TRANSLUCENT
                }
            }
        }
    }

    interface QuickAction {
        fun onQuickAction()
    }

    interface TipsButtonCallback {
        fun onClick(action: Action)
    }

    enum class Action {
        POSITIVE, NEGATIVE
    }

    companion object {
        const val KEEP_LEFT = 1
        const val KEEP_RIGHT = 1 shl 1
        const val TOP = 1 shl 2
        const val BOTTOM = 1 shl 3
        const val LEFT = 1 shl 4
        const val RIGHT = 1 shl 5

        const val LIGHT = 0
        const val DARK = 1
        const val PURPLE = 2
        const val RED = 3

        const val CONTENT_MAX_LINES = 2
        const val CONTENT_LINE_MAX_LENGTH = 12

        private var keepToolTipsRef: WeakReference<KeepToolTips>? = null

        /**
         * 是否有正在展示的 Tips
         */
        fun isKeepToolTipsShowing(): Boolean {
            return keepToolTipsRef?.get()?.isShowing() == true
        }
    }
}
