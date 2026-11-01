package com.oli.glasslauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.util.Log
import android.text.TextUtils
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class AppInfo(val label: String, val icon: Drawable, val pkg: String, val intent: Intent)
class Wall(val sharp: Bitmap, val blur: Bitmap)

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var bottomBox: LinearLayout
    private lateinit var drawer: GlassPanel
    private lateinit var drawerInner: LinearLayout
    private lateinit var grid: GridView
    private lateinit var searchBox: EditText
    private lateinit var backdrop: View
    private lateinit var wall: Wall
    private lateinit var detector: GestureDetector

    private val panels = ArrayList<GlassPanel>()
    private var allApps: List<AppInfo> = emptyList()
    private var shown: List<AppInfo> = emptyList()
    private var drawerOpen = false
    private val dm by lazy { resources.displayMetrics }

    private fun dp(v: Int) = (v * dm.density).toInt()
    private fun dpf(v: Float) = v * dm.density
    private fun wh(a: Int) = Color.argb(a, 255, 255, 255)

    // ---------------------------------------------------------------- UI pieces

    /** A frosted-glass panel: blurred wallpaper + white tint + shiny border. */
    inner class GlassPanel(radiusDp: Float, darkAlpha: Int, lightAlpha: Int) :
        FrameLayout(this@MainActivity) {
        private val radius = radiusDp * dm.density
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.6f) })
        }
        private val darkPaint = Paint().apply { color = Color.argb(darkAlpha, 0, 0, 0) }
        private val lightPaint = Paint().apply { color = Color.argb(lightAlpha, 255, 255, 255) }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * dm.density
        }
        private val path = Path()
        private val rect = RectF()
        private val dst = Rect()
        private val a = IntArray(2)
        private val b = IntArray(2)

        init {
            panels.add(this)
        }

        override fun dispatchDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            rect.set(0f, 0f, w, h)
            path.reset()
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(path)
            getLocationOnScreen(a)
            root.getLocationOnScreen(b)
            val dx = (b[0] - a[0]).toFloat()
            val dy = (b[1] - a[1]).toFloat()
            canvas.translate(dx, dy)
            dst.set(0, 0, root.width, root.height)
            canvas.drawBitmap(wall.blur, null, dst, bmpPaint)
            canvas.translate(-dx, -dy)
            canvas.drawRect(rect, lightPaint)
            canvas.drawRect(rect, darkPaint)
            canvas.restore()
            border.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(Color.argb(190, 255, 255, 255), Color.argb(25, 255, 255, 255), Color.argb(100, 255, 255, 255)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawPath(path, border)
            super.dispatchDraw(canvas)
        }
    }

    /** One app icon on a small glass tile, with an optional label. */
    inner class AppCell : LinearLayout(this@MainActivity) {
        private val icon = ImageView(context)
        private val label = TextView(context)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            icon.background = GradientDrawable().apply {
                cornerRadius = dpf(17f)
                setColor(wh(38))
                setStroke(dp(1), wh(90))
            }
            icon.setPadding(dp(7), dp(7), dp(7), dp(7))
            addView(icon, LayoutParams(dp(60), dp(60)))
            label.apply {
                textSize = 11f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setShadowLayer(4f, 0f, 1f, Color.argb(160, 0, 0, 0))
            }
            addView(label, LayoutParams(WRAP, WRAP).apply { topMargin = dp(5) })
        }

        fun bind(a: AppInfo, showLabel: Boolean) {
            icon.setImageDrawable(a.icon)
            label.text = a.label
            label.visibility = if (showLabel) View.VISIBLE else View.GONE
        }
    }

    private val adp = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val cell = (cv as? AppCell) ?: AppCell()
            cell.bind(shown[p], true)
            return cell
        }
    }

    // ---------------------------------------------------------------- lifecycle

    private var ok = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashFile = File(filesDir, "crash.txt")
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                crashFile.writeText(Log.getStackTraceString(e))
            } catch (_: Throwable) {
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
        if (crashFile.exists()) {
            val saved = crashFile.readText()
            crashFile.delete()
            showCrash(saved)
            return
        }
        try {
            setup()
            ok = true
        } catch (e: Throwable) {
            showCrash(Log.getStackTraceString(e))
        }
    }

    private fun showCrash(err: String) {
        val tv = TextView(this).apply {
            this.text = "Glass Launcher error. Take a screenshot and send it to Claude:\n\n" + err.take(6000)
            setTextColor(Color.WHITE)
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(16), dp(48), dp(16), dp(16))
        }
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.BLACK)
        sv.addView(tv)
        setContentView(sv)
    }

    private fun setup() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        wall = loadWall()
        root = FrameLayout(this)
        backdrop = object : View(this) {
            private val p = Paint(Paint.FILTER_BITMAP_FLAG)
            private val r = Rect()
            override fun onDraw(c: Canvas) {
                r.set(0, 0, width, height)
                c.drawBitmap(wall.sharp, null, r, p)
            }
        }
        root.addView(backdrop, MATCH, MATCH)
        content = FrameLayout(this)
        root.addView(content, MATCH, MATCH)
        buildHome()
        buildDrawer()

        root.setOnApplyWindowInsetsListener { _, ins ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val s = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                top = s.top
                bottom = s.bottom
            } else {
                top = ins.systemWindowInsetTop
                bottom = ins.systemWindowInsetBottom
            }
            content.setPadding(0, top, 0, bottom)
            drawerInner.setPadding(dp(16), top, dp(16), bottom)
            ins
        }
        setContentView(root)

        detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vy) < abs(vx)) return false
                if (!drawerOpen && vy < -1200f) {
                    openDrawer(false)
                    return true
                }
                if (drawerOpen && vy > 1800f && !grid.canScrollVertically(-1)) {
                    closeDrawer()
                    return true
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                if (drawerOpen) return
                val l = IntArray(2)
                bottomBox.getLocationOnScreen(l)
                if (e.rawY < l[1]) showMenu()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (!ok) return
        Thread {
            val list = loadApps()
            runOnUiThread {
                allApps = list
                filter(searchBox.text.toString())
            }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Intent.ACTION_MAIN == intent.action) closeDrawer()
    }

    override fun onBackPressed() {
        if (drawerOpen) closeDrawer()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ok) detector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ---------------------------------------------------------------- home screen

    private fun buildHome() {
        // Clock widget
        val clock = GlassPanel(28f, 0, 40)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(18), dp(24), dp(18))
        }
        val time = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            textSize = 64f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val date = TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            textSize = 16f
            setTextColor(wh(210))
        }
        col.addView(time)
        col.addView(date)
        clock.addView(col)
        content.addView(
            clock,
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply { setMargins(dp(20), dp(36), dp(20), 0) }
        )

        // Search pill + dock
        bottomBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val search = GlassPanel(24f, 0, 40)
        search.addView(TextView(this).apply {
            text = "🔍   Search"
            textSize = 16f
            setTextColor(wh(220))
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(14))
        })
        search.setOnClickListener { openDrawer(true) }
        bottomBox.addView(
            search,
            LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(24), 0, dp(24), dp(14)) }
        )

        val dock = GlassPanel(34f, 0, 46)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(14), dp(10), dp(14))
        }
        dockApps().forEach { app ->
            val cell = AppCell()
            cell.bind(app, false)
            cell.setOnClickListener { launch(app) }
            row.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        dock.addView(row)
        bottomBox.addView(dock, LinearLayout.LayoutParams(MATCH, WRAP))

        content.addView(
            bottomBox,
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(14)) }
        )
    }

    private fun buildDrawer() {
        drawer = GlassPanel(0f, 95, 12)
        drawerInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        searchBox = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(wh(150))
            setTextColor(Color.WHITE)
            textSize = 16f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            background = GradientDrawable().apply {
                cornerRadius = dpf(24f)
                setColor(wh(46))
                setStroke(dp(1), wh(90))
            }
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        searchBox.setOnEditorActionListener { _, _, _ ->
            shown.firstOrNull()?.let { launch(it) }
            true
        }
        grid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(18)
            horizontalSpacing = dp(4)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false
            setPadding(0, dp(12), 0, dp(24))
            overScrollMode = View.OVER_SCROLL_NEVER
            adapter = adp
        }
        grid.setOnItemClickListener { _, _, pos, _ -> launch(shown[pos]) }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + shown[pos].pkg))
            )
            true
        }
        drawerInner.addView(
            searchBox,
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12); bottomMargin = dp(8) }
        )
        drawerInner.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))
        drawer.addView(drawerInner, MATCH, MATCH)
        drawer.visibility = View.GONE
        root.addView(drawer, MATCH, MATCH)
    }

    // ---------------------------------------------------------------- drawer logic

    private fun openDrawer(focusSearch: Boolean) {
        if (drawerOpen) return
        drawerOpen = true
        searchBox.setText("")
        drawer.visibility = View.VISIBLE
        drawer.translationY = root.height.toFloat()
        drawer.animate().translationY(0f).setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .setUpdateListener { drawer.invalidate() }
            .withEndAction {
                if (focusSearch) {
                    searchBox.requestFocus()
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(searchBox, 0)
                }
            }.start()
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchBox.windowToken, 0)
        searchBox.clearFocus()
        drawer.animate().translationY(root.height.toFloat()).setDuration(240)
            .setInterpolator(AccelerateInterpolator())
            .setUpdateListener { drawer.invalidate() }
            .withEndAction { if (!drawerOpen) drawer.visibility = View.GONE }
            .start()
    }

    private fun filter(q: String) {
        val s = q.trim().lowercase()
        shown = if (s.isEmpty()) allApps else allApps.filter { it.label.lowercase().contains(s) }
        adp.notifyDataSetChanged()
    }

    private fun launch(app: AppInfo) {
        try {
            startActivity(app.intent)
            closeDrawer()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open " + app.label, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- apps

    private fun loadApps(): List<AppInfo> {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { ri ->
                val ai = ri.activityInfo
                AppInfo(
                    ri.loadLabel(pm).toString(),
                    ri.loadIcon(pm),
                    ai.packageName,
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(ComponentName(ai.packageName, ai.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun dockApps(): List<AppInfo> {
        val pm = packageManager
        val bases = listOf(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
        return bases.mapNotNull { base ->
            pm.queryIntentActivities(base, 0).firstOrNull()?.let { ri ->
                val ai = ri.activityInfo
                AppInfo(
                    ri.loadLabel(pm).toString(),
                    ri.loadIcon(pm),
                    ai.packageName,
                    Intent(base).setComponent(ComponentName(ai.packageName, ai.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    // ---------------------------------------------------------------- wallpaper

    private fun showMenu() {
     
