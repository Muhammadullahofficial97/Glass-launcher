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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        detector.onTouchEvent(ev)
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
        AlertDialog.Builder(this)
            .setItems(arrayOf("Change wallpaper (pick a photo)", "Default wallpaper")) { _, i ->
                if (i == 0) {
                    startActivityForResult(
                        Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), "Photo"), 1
                    )
                } else {
                    File(filesDir, "wall.jpg").delete()
                    applyWall(loadWall())
                }
            }.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == 1 && resultCode == RESULT_OK && uri != null) {
            saveWall(uri)
            applyWall(loadWall())
        }
    }

    private fun saveWall(uri: Uri) {
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var s = 1
            while (max(o.outWidth, o.outHeight) / s > 2400) s *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = s }
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
                ?: return
            File(filesDir, "wall.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not load photo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyWall(w: Wall) {
        wall = w
        backdrop.invalidate()
        panels.forEach { it.invalidate() }
    }

    private fun loadWall(): Wall {
        val w = dm.widthPixels
        val h = dm.heightPixels
        val f = File(filesDir, "wall.jpg")
        val src = (if (f.exists()) BitmapFactory.decodeFile(f.path) else null) ?: defaultBitmap(w, h)
        return makeWall(src, w, h)
    }

    private fun makeWall(src: Bitmap, w: Int, h: Int): Wall {
        val scale = max(w / src.width.toFloat(), h / src.height.toFloat())
        val cw = min(src.width, (w / scale).toInt().coerceAtLeast(1))
        val ch = min(src.height, (h / scale).toInt().coerceAtLeast(1))
        val x = (src.width - cw) / 2
        val y = (src.height - ch) / 2
        val cropped = Bitmap.createBitmap(src, x, y, cw, ch)
        val sharp = Bitmap.createScaledBitmap(cropped, w, h, true)
        val small = Bitmap.createScaledBitmap(cropped, max(w / 8, 8), max(h / 8, 8), true)
        return Wall(sharp, boxBlur(small, 6))
    }

    private fun defaultBitmap(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint()
        p.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(Color.rgb(27, 20, 100), Color.rgb(123, 47, 247), Color.rgb(241, 7, 163)),
            null, Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        fun blob(cx: Float, cy: Float, r: Float, rr: Int, gg: Int, bb: Int) {
            p.shader = RadialGradient(
                cx, cy, r, Color.argb(200, rr, gg, bb), Color.argb(0, rr, gg, bb), Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, r, p)
        }
        blob(w * 0.2f, h * 0.25f, w * 0.6f, 0, 200, 255)
        blob(w * 0.85f, h * 0.55f, w * 0.7f, 255, 140, 60)
        blob(w * 0.3f, h * 0.85f, w * 0.6f, 80, 255, 170)
        return b
    }

    // ---------------------------------------------------------------- blur

    private fun boxBlur(bmp: Bitmap, r: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            blurPass(px, tmp, w, h, r, true)
            blurPass(tmp, px, w, h, r, false)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
        val lines = if (horiz) h else w
        val len = if (horiz) w else h
        val n = 2 * r + 1
        for (l in 0 until lines) {
            fun idx(i: Int) = if (horiz) l * w + i else i * w + l
            var rr = 0
            var gg = 0
            var bb = 0
            for (i in -r..r) {
                val p = src[idx(i.coerceIn(0, len - 1))]
                rr += (p shr 16) and 255
                gg += (p shr 8) and 255
                bb += p and 255
            }
            for (i in 0 until len) {
                dst[idx(i)] = (0xFF shl 24) or ((rr / n) shl 16) or ((gg / n) shl 8) or (bb / n)
                val add = src[idx((i + r + 1).coerceIn(0, len - 1))]
                val rem = src[idx((i - r).coerceIn(0, len - 1))]
                rr += ((add shr 16) and 255) - ((rem shr 16) and 255)
                gg += ((add shr 8) and 255) - ((rem shr 8) and 255)
                bb += (add and 255) - (rem and 255)
            }
        }
    }
}
