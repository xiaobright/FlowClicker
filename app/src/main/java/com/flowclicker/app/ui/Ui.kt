package com.flowclicker.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import com.flowclicker.app.MainActivity
import com.flowclicker.app.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Small native-view vocabulary shared by the four screens; no routing or domain state here. */
object Ui {
    fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    fun Context.color(id: Int) = ContextCompat.getColor(this, id)
    fun background(c: Context, color: Int, radius: Int = 16, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(c.color(color))
            cornerRadius = c.dp(radius).toFloat()
            stroke?.let { setStroke(c.dp(1), c.color(it)) }
        }

    fun column(c: Context, padding: Int = 0) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(c.dp(padding), c.dp(padding), c.dp(padding), c.dp(padding))
    }

    fun text(c: Context, value: String, size: Int = 14, color: Int = R.color.fc_text,
             bold: Boolean = false, id: Int = View.NO_ID) = TextView(c).apply {
        this.id = id
        text = value
        textSize = size.toFloat()
        setTextColor(c.color(color))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(c.dp(3).toFloat(), 1f)
    }

    fun LinearLayout.add(view: View, top: Int = 0) {
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(top) })
    }

    fun row(c: Context, vararg views: View) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { index, view ->
            addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = c.dp(10)
            })
        }
    }

    fun card(parent: LinearLayout, top: Int = 16, color: Int = R.color.fc_surface,
             padding: Int = 20, build: LinearLayout.() -> Unit): LinearLayout {
        val c = parent.context
        val inside = column(c, padding).apply(build)
        val card = MaterialCardView(c).apply {
            radius = c.dp(24).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(c.color(color))
            strokeWidth = if (color == R.color.fc_surface) c.dp(1) else 0
            strokeColor = c.color(R.color.fc_outline)
            addView(inside, ViewGroup.LayoutParams(-1, -2))
        }
        parent.add(card, top)
        return inside
    }

    fun button(c: Context, label: String, id: Int = View.NO_ID,
               tone: String = "primary", action: (() -> Unit)? = null) =
        MaterialButton(c).apply {
            this.id = id
            text = label
            textSize = 14f
            isAllCaps = false
            minHeight = c.dp(48)
            minimumHeight = c.dp(48)
            insetTop = c.dp(2)
            insetBottom = c.dp(2)
            cornerRadius = c.dp(14)
            setPadding(c.dp(14), c.dp(10), c.dp(14), c.dp(10))
            val (bg, fg) = when (tone) {
                "soft" -> R.color.fc_primary_soft to R.color.fc_primary
                "danger" -> R.color.fc_danger_soft to R.color.fc_danger
                "quiet" -> R.color.fc_surface_soft to R.color.fc_muted
                else -> R.color.fc_primary to R.color.fc_on_primary
            }
            backgroundTintList = ColorStateList.valueOf(c.color(bg))
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(c.color(R.color.fc_muted), c.color(fg))))
            action?.let { setOnClickListener { it() } }
        }

    fun iconButton(c: Context, icon: Int, description: String, action: () -> Unit) =
        button(c, "", tone = "quiet", action = action).apply {
            setIconResource(icon)
            iconTint = ColorStateList.valueOf(c.color(R.color.fc_muted))
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = 0
            minWidth = c.dp(48)
            minimumWidth = c.dp(48)
            contentDescription = description
        }

    fun field(parent: LinearLayout, label: String, id: Int, value: String = "",
              type: Int = InputType.TYPE_CLASS_TEXT, password: Boolean = false): EditText {
        val c = parent.context
        val input = TextInputLayout(c, null,
            com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = label
            setBoxCornerRadii(c.dp(12).toFloat(), c.dp(12).toFloat(), c.dp(12).toFloat(), c.dp(12).toFloat())
            boxStrokeColor = c.color(R.color.fc_primary)
            if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val edit = TextInputEditText(input.context).apply {
            this.id = id
            inputType = type
            textSize = 15f
            minHeight = c.dp(56)
            setText(value)
            if (password) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                isSaveEnabled = false
            }
        }
        input.addView(edit, LinearLayout.LayoutParams(-1, -2))
        parent.add(input, 12)
        return edit
    }

    fun check(c: Context, label: String, id: Int, checked: Boolean = false) =
        MaterialCheckBox(c).apply {
            this.id = id
            text = label
            textSize = 14f
            minHeight = c.dp(48)
            isChecked = checked
        }

    fun badge(c: Context, label: String, id: Int = View.NO_ID) =
        text(c, label, 12, R.color.fc_primary, true, id).apply {
            background = background(c, R.color.fc_primary_soft, 8)
            setPadding(c.dp(10), c.dp(5), c.dp(10), c.dp(5))
        }

    fun section(parent: LinearLayout, title: String, hint: String? = null) {
        parent.add(text(parent.context, title, 18, bold = true), 24)
        hint?.let { parent.add(text(parent.context, it, 13, R.color.fc_muted), 4) }
    }

    /** Keep picker coordinates full-screen; only inset its hint panel around system bars. */
    fun pickerContent(activity: Activity, frame: FrameLayout, hint: View) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            hint.layoutParams = (hint.layoutParams as FrameLayout.LayoutParams).apply {
                setMargins(bars.left + activity.dp(16), bars.top + activity.dp(12),
                    bars.right + activity.dp(16), 0)
            }
            insets
        }
        activity.setContentView(frame)
    }

    /** Insets include IME, and the content remains scrollable at large fonts / landscape. */
    fun screen(activity: Activity, title: String, subtitle: String, destination: Int? = null): LinearLayout {
        val root = column(activity)
        root.setBackgroundColor(activity.color(R.color.fc_background))
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        val content = column(activity, 20)
        val wrapper = FrameLayout(activity).apply {
            addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
                if (r - l != or - ol) {
                    val width = minOf(r - l, activity.dp(680))
                    if (content.layoutParams.width != width) {
                        content.layoutParams = (content.layoutParams as FrameLayout.LayoutParams).apply { this.width = width }
                    }
                }
            }
        }
        root.addView(ScrollView(activity).apply {
            isFillViewport = true
            clipToPadding = false
            addView(wrapper)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        val brand = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        if (destination == null) {
            brand.addView(iconButton(activity, R.drawable.ic_back, "返回") {
                activity.onBackPressedDispatcherCompat()
            }.apply { id = R.id.btnBack }, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)))
        }
        brand.addView(text(activity, if (destination == R.id.navHome) "◈  FlowClicker" else "FLOWCLICKER  /  $title",
            13, R.color.fc_primary, true), LinearLayout.LayoutParams(-2, -2).apply {
            if (destination == null) marginStart = activity.dp(12)
        })
        content.add(brand, 4)
        content.add(text(activity, title, 30, bold = true), 18)
        content.add(text(activity, subtitle, 14, R.color.fc_muted), 6)
        if (destination != null) {
            root.addView(BottomNavigationView(activity).apply {
                setBackgroundColor(activity.color(R.color.fc_surface))
                labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
                menu.add(0, R.id.navHome, 0, "工作台").setIcon(R.drawable.ic_dashboard)
                menu.add(0, R.id.navTasks, 1, "任务").setIcon(R.drawable.ic_tasks)
                menu.add(0, R.id.navAi, 2, "AI 助手").setIcon(R.drawable.ic_spark)
                selectedItemId = destination
                setOnItemSelectedListener { item ->
                    if (item.itemId != destination) {
                        val target = when (item.itemId) {
                            R.id.navTasks -> TaskListActivity::class.java
                            R.id.navAi -> AiActivity::class.java
                            else -> MainActivity::class.java
                        }
                        activity.startActivity(Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        if (activity !is MainActivity) activity.finish()
                    }
                    true
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        activity.setContentView(root)
        // PhoneWindow needs an installed decor before requesting its controller (API 30+).
        WindowCompat.getInsetsController(activity.window, root).apply {
            isAppearanceLightStatusBars = activity.resources.getBoolean(R.bool.fc_light_bars)
            isAppearanceLightNavigationBars = activity.resources.getBoolean(R.bool.fc_light_bars)
        }
        return content
    }

    private fun Activity.onBackPressedDispatcherCompat() {
        if (this is androidx.activity.ComponentActivity) onBackPressedDispatcher.onBackPressed() else finish()
    }
}
