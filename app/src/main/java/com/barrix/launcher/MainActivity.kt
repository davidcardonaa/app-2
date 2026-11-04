package com.barrix.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class App(
    val label: String,
    val pkg: String,
    val intent: Intent,
    val banner: Drawable?,
    val icon: Drawable,
    val tv: Boolean,
    val color: Int // color dominante de la app (0 si no tiene uno claro)
)

/**
 * Pantalla de inicio ligera para Fire TV. Sin librerías externas: solo vistas del sistema,
 * para que arranque rápido incluso en un Fire TV Stick Lite.
 *
 * Mando: OK abre la app. OK largo la fija o la quita de Favoritas.
 * El botón "Aspecto" cambia el color, activa o desactiva el color según la app y la foto de fondo.
 */
class MainActivity : Activity() {
    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private val es = Locale("es", "ES")

    private val THEMES = listOf(
        "Rojo" to 0xFFE5252A.toInt(),
        "Naranja" to 0xFFFF7A18.toInt(),
        "Amarillo" to 0xFFF5B800.toInt(),
        "Verde" to 0xFF1FBF5B.toInt(),
        "Turquesa" to 0xFF14B8C4.toInt(),
        "Azul" to 0xFF2F6BFF.toInt(),
        "Morado" to 0xFF8B3DFF.toInt(),
        "Rosa" to 0xFFFF3D8B.toInt()
    )

    private lateinit var bg: GradientDrawable
    private lateinit var stage: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var photo: ImageView
    private lateinit var scrim: View
    private lateinit var aspectPill: TextView
    private lateinit var greet: TextView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var heading: TextView
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout

    private var panel: View? = null
    private var allApps: List<App> = emptyList()
    private var first: View? = null
    private var focusPkg: String? = null
    private var focusApp: App? = null
    private var loading = false
    private var tileW = 0
    private var tileH = 0

    // Fondo: color que se está mostrando, color al que va y animación en curso.
    private var shown = 0
    private var wanted = 0
    private var anim: ValueAnimator? = null
    private var photoActive = false
    private var photoStamp = -2L

    private val ticker = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = tick()
    }
    private val pkgChange = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = reload()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getExternalFilesDir(null) // crea la carpeta donde Barrix deja la foto de fondo

        // Tamaño de tarjeta según la pantalla: unas 4,3 tarjetas visibles (se asoma la siguiente).
        val m = resources.displayMetrics
        val wDp = m.widthPixels / m.density
        val tileWDp = (wDp - 96f - 42f) / 4.3f
        tileW = (tileWDp * m.density).toInt()
        tileH = tileW * 9 / 16

        bg = GradientDrawable()
        bg.setGradientType(GradientDrawable.RADIAL_GRADIENT)
        bg.setGradientRadius(m.widthPixels * 0.85f)
        bg.setGradientCenter(0.3f, 0.25f)
        val c0 = themeColor()
        paint(c0)
        wanted = c0

        stage = FrameLayout(this)
        stage.background = bg

        photo = ImageView(this)
        photo.scaleType = ImageView.ScaleType.CENTER_CROP
        photo.visibility = View.GONE
        stage.addView(photo, FrameLayout.LayoutParams(MP, MP))

        // Capa oscura sobre la foto para que el texto blanco se lea bien.
        scrim = View(this)
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xB3000000.toInt(), 0x66000000, 0xCC000000.toInt())
        )
        scrim.visibility = View.GONE
        stage.addView(scrim, FrameLayout.LayoutParams(MP, MP))

        val root = FrameLayout(this)
        root.setPadding(dp(48), dp(28), dp(48), 0)
        stage.addView(root, FrameLayout.LayoutParams(MP, MP))

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        root.addView(content, FrameLayout.LayoutParams(MP, MP))

        // Cabecera: saludo, hora y fecha a la izquierda; botones a la derecha.
        val header = FrameLayout(this)
        content.addView(header, LinearLayout.LayoutParams(MP, WC))

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        greet = text(12f, 0xB3FFFFFF.toInt())
        greet.letterSpacing = 0.15f
        left.addView(greet)
        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        clock = text(40f, Color.WHITE, true)
        line.addView(clock)
        val sep = View(this)
        sep.setBackgroundColor(0x55FFFFFF)
        val sepLp = LinearLayout.LayoutParams(dp(1), dp(26))
        sepLp.setMargins(dp(14), 0, dp(14), 0)
        line.addView(sep, sepLp)
        date = text(13f, 0xCCFFFFFF.toInt())
        date.letterSpacing = 0.08f
        line.addView(date)
        left.addView(line)
        header.addView(left, FrameLayout.LayoutParams(WC, WC, Gravity.START or Gravity.TOP))

        val right = LinearLayout(this)
        right.orientation = LinearLayout.HORIZONTAL
        aspectPill = pill("Aspecto") { openPanel() }
        right.addView(aspectPill)
        val wifi = pill("Wi-Fi") {
            openSettings(Settings.ACTION_WIFI_SETTINGS, "com.amazon.tv.settings.v2.tv.network.NetworkActivity")
        }
        (wifi.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(10)
        right.addView(wifi)
        val setBtn = pill("Ajustes") {
            openSettings(Settings.ACTION_SETTINGS, "com.amazon.tv.settings.v2.tv.device.DeviceActivity")
        }
        (setBtn.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(10)
        right.addView(setBtn)
        header.addView(right, FrameLayout.LayoutParams(WC, WC, Gravity.END or Gravity.TOP))

        // Nombre de la app enfocada.
        heading = text(28f, Color.WHITE, true)
        heading.text = "Barrix"
        heading.setSingleLine(true)
        heading.ellipsize = TextUtils.TruncateAt.END
        val hLp = LinearLayout.LayoutParams(MP, WC)
        hLp.topMargin = dp(22)
        content.addView(heading, hLp)

        scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.clipChildren = false
        scroll.clipToPadding = false
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.clipChildren = false
        list.setPadding(0, 0, 0, dp(24))
        scroll.addView(list, FrameLayout.LayoutParams(MP, WC))
        content.addView(scroll, LinearLayout.LayoutParams(MP, 0, 1f))

        setContentView(stage)

        val f = IntentFilter()
        f.addAction(Intent.ACTION_PACKAGE_ADDED)
        f.addAction(Intent.ACTION_PACKAGE_REMOVED)
        f.addAction(Intent.ACTION_PACKAGE_CHANGED)
        f.addDataScheme("package")
        registerReceiver(pkgChange, f)

        tick()
        reload()
        applyPhoto()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(ticker, IntentFilter(Intent.ACTION_TIME_TICK))
        tick()
        applyPhoto() // por si se ha subido una foto nueva desde Barrix
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(ticker) } catch (e: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        anim?.cancel()
        try { unregisterReceiver(pkgChange) } catch (e: Exception) { }
    }

    // Pulsar Home estando ya aquí: cerrar el panel, volver arriba y enfocar la primera tarjeta.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (panel != null) closePanel()
        scroll.smoothScrollTo(0, 0)
        first?.requestFocus()
    }

    // Un launcher no debe cerrarse con Atrás; solo cierra el panel si está abierto.
    override fun onBackPressed() {
        if (panel != null) closePanel()
    }

    private fun tick() {
        val now = Date()
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        greet.text = when {
            h < 6 -> "BUENAS NOCHES"
            h < 13 -> "BUENOS DÍAS"
            h < 21 -> "BUENAS TARDES"
            else -> "BUENAS NOCHES"
        }
        clock.text = SimpleDateFormat("H:mm", es).format(now)
        date.text = SimpleDateFormat("EEEE d 'de' MMMM", es).format(now).uppercase(es)
    }

    // ---------- Colores y fondo ----------

    private fun prefs() = getSharedPreferences("barrix", MODE_PRIVATE)
    private fun theme() = prefs().getInt("theme", 0).coerceIn(0, THEMES.size - 1)
    private fun themeColor() = THEMES[theme()].second
    private fun dyn() = prefs().getBoolean("dyn", true)

    /** Pinta el degradado a partir de un color: centro apagado y bordes casi negros. */
    private fun paint(c: Int) {
        shown = c
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        val center = Color.HSVToColor(floatArrayOf(hsv[0], minOf(hsv[1], 0.9f), 0.55f))
        val edge = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1] * 0.6f, 0.07f))
        bg.setColors(intArrayOf(center, edge))
    }

    private fun setAccent(color: Int) {
        if (photoActive || color == wanted) return
        wanted = color
        anim?.cancel()
        val from = shown
        if (from == 0) {
            paint(color)
            return
        }
        val a = ValueAnimator.ofArgb(from, color)
        a.duration = 250
        a.addUpdateListener { paint(it.animatedValue as Int) }
        a.start()
        anim = a
    }

    /** Elige el color de fondo: el de la app enfocada si está activado, o el del tema. */
    private fun refreshAccent() {
        val f = focusApp
        setAccent(if (dyn() && f != null && f.color != 0) f.color else themeColor())
    }

    /** Color más "de marca" de una imagen: el tono con más peso entre los píxeles vivos. */
    private fun dominant(d: Drawable?): Int {
        if (d == null) return 0
        return try {
            val w = 32
            val h = 18
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, w, h)
            d.draw(Canvas(bmp))
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            val bins = 12
            val ws = FloatArray(bins)
            val rs = FloatArray(bins)
            val gs = FloatArray(bins)
            val bs = FloatArray(bins)
            val hsv = FloatArray(3)
            for (p in px) {
                if (Color.alpha(p) < 128) continue
                Color.colorToHSV(p, hsv)
                if (hsv[1] < 0.35f || hsv[2] < 0.25f) continue
                val wt = hsv[1] * hsv[2]
                val b = (hsv[0] / 360f * bins).toInt().coerceIn(0, bins - 1)
                ws[b] += wt
                rs[b] += Color.red(p) * wt
                gs[b] += Color.green(p) * wt
                bs[b] += Color.blue(p) * wt
            }
            var best = -1
            var bw = 0f
            for (i in 0 until bins) if (ws[i] > bw) { bw = ws[i]; best = i }
            if (best < 0 || bw < 0.6f) 0
            else Color.rgb((rs[best] / ws[best]).toInt(), (gs[best] / ws[best]).toInt(), (bs[best] / ws[best]).toInt())
        } catch (e: Exception) {
            0
        }
    }

    // ---------- Foto de fondo ----------

    private fun photoFile() = File(getExternalFilesDir(null), "fondo.jpg")

    private fun applyPhoto() {
        val f = photoFile()
        val want = f.exists() && prefs().getBoolean("photo", true)
        val stamp = if (want) f.lastModified() else -1L
        if (stamp == photoStamp) return
        photoStamp = stamp
        if (!want) {
            photoActive = false
            photo.visibility = View.GONE
            scrim.visibility = View.GONE
            refreshAccent()
            return
        }
        val dm = resources.displayMetrics
        Thread {
            val bmp = try { decode(f.path, dm.widthPixels, dm.heightPixels) } catch (e: Exception) { null }
            runOnUiThread {
                if (!isDestroyed) {
                    if (bmp != null) {
                        photo.setImageBitmap(bmp)
                        photo.visibility = View.VISIBLE
                        scrim.visibility = View.VISIBLE
                        photoActive = true
                    } else {
                        photoActive = false
                        photoStamp = -2L
                    }
                    refreshAccent()
                }
            }
        }.start()
    }

    private fun decode(path: String, w: Int, h: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        o2.inPreferredConfig = Bitmap.Config.RGB_565
        return BitmapFactory.decodeFile(path, o2)
    }

    // ---------- Panel "Aspecto" ----------

    private fun openPanel() {
        if (panel != null) return
        val p = FrameLayout(this)
        p.setBackgroundColor(0xCC000000.toInt())
        p.isClickable = true

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(24), dp(20), dp(24), dp(20))
        card.background = shape(0xFF14161A.toInt(), 16, 1, 0x33FFFFFF)

        val title = text(20f, Color.WHITE, true)
        title.text = "Aspecto"
        card.addView(title)

        val lbl = text(12f, 0xFF8B92A1.toInt())
        lbl.text = "COLOR"
        lbl.letterSpacing = 0.12f
        lbl.setPadding(0, dp(14), 0, dp(8))
        card.addView(lbl)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.clipChildren = false
        val updates = ArrayList<() -> Unit>()
        var firstSw: View? = null
        for (i in THEMES.indices) {
            val color = THEMES[i].second
            val t = TextView(this)
            t.gravity = Gravity.CENTER
            t.textSize = 22f
            t.setTextColor(Color.WHITE)
            t.isFocusable = true
            t.isClickable = true
            val lp = LinearLayout.LayoutParams(dp(38), dp(38))
            lp.rightMargin = dp(10)
            row.addView(t, lp)
            val upd = {
                val sel = theme() == i
                t.text = if (sel) "•" else ""
                t.background = oval(color, sel || t.isFocused)
            }
            updates.add(upd)
            upd()
            t.setOnFocusChangeListener { v, f ->
                upd()
                val sc = if (f) 1.2f else 1f
                v.animate().scaleX(sc).scaleY(sc).setDuration(120).start()
            }
            t.setOnClickListener {
                prefs().edit().putInt("theme", i).apply()
                for (u in updates) u()
                refreshAccent()
            }
            if (i == theme()) firstSw = t
        }
        card.addView(row)

        val dynBtn = pill("") { }
        val dynLabel = { dynBtn.text = "Color según la app: " + (if (dyn()) "Sí" else "No") }
        dynLabel()
        dynBtn.setOnClickListener {
            prefs().edit().putBoolean("dyn", !dyn()).apply()
            dynLabel()
            refreshAccent()
        }
        (dynBtn.layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)
        card.addView(dynBtn)

        if (photoFile().exists()) {
            val pb = pill("") { }
            val pl = { pb.text = "Foto de fondo: " + (if (prefs().getBoolean("photo", true)) "Sí" else "No") }
            pl()
            pb.setOnClickListener {
                prefs().edit().putBoolean("photo", !prefs().getBoolean("photo", true)).apply()
                pl()
                applyPhoto()
            }
            (pb.layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
            card.addView(pb)
        } else {
            val h = text(12f, 0xFF8B92A1.toInt())
            h.text = "Para usar tu foto de fondo, súbela desde el panel web de Barrix."
            h.setPadding(0, dp(14), 0, 0)
            card.addView(h)
        }

        val close = pill("Cerrar") { closePanel() }
        (close.layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)
        card.addView(close)

        p.addView(card, FrameLayout.LayoutParams(dp(460), WC, Gravity.CENTER))
        stage.addView(p, FrameLayout.LayoutParams(MP, MP))
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        panel = p
        val target = firstSw ?: dynBtn
        target.post { target.requestFocus() }
    }

    private fun closePanel() {
        val p = panel ?: return
        stage.removeView(p)
        panel = null
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        aspectPill.requestFocus()
    }

    // ---------- Apps ----------

    private fun reload() {
        if (loading) return
        loading = true
        Thread {
            val apps = try { loadApps() } catch (e: Exception) { emptyList() }
            runOnUiThread {
                loading = false
                if (!isDestroyed) {
                    allApps = apps
                    render()
                }
            }
        }.start()
    }

    private fun loadApps(): List<App> {
        val pm = packageManager
        val seen = HashSet<String>()
        val out = ArrayList<App>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val tv = cat == Intent.CATEGORY_LEANBACK_LAUNCHER
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val ai = ri.activityInfo
                if (ai.packageName == packageName || ai.packageName == "com.amazon.tv.launcher") continue
                if (!seen.add(ai.packageName)) continue
                val launch = Intent(Intent.ACTION_MAIN).addCategory(cat)
                    .setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val banner = if (tv) (ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)) else null
                val icon = ri.loadIcon(pm)
                out.add(App(ri.loadLabel(pm).toString(), ai.packageName, launch, banner, icon, tv, dominant(banner ?: icon)))
            }
        }
        out.sortBy { it.label.lowercase(es) }
        return out
    }

    private fun pins(): Set<String> = prefs().getStringSet("pins", emptySet())!!.toSet()

    private fun togglePin(a: App) {
        val p = pins().toMutableSet()
        val added = p.add(a.pkg)
        if (!added) p.remove(a.pkg)
        prefs().edit().putStringSet("pins", p).apply()
        focusPkg = a.pkg
        Toast.makeText(this, if (added) "Fijada en Favoritas" else "Quitada de Favoritas", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun render() {
        list.removeAllViews()
        val pins = pins()
        val groups = listOf(
            "Favoritas" to allApps.filter { it.pkg in pins },
            "Apps" to allApps.filter { it.tv && it.pkg !in pins },
            "Más apps" to allApps.filter { !it.tv && it.pkg !in pins }
        )
        var target: View? = null
        first = null
        for ((title, apps) in groups) {
            if (apps.isEmpty()) continue
            val tv = text(12f, 0xFF8B92A1.toInt())
            tv.text = title.uppercase(es)
            tv.letterSpacing = 0.12f
            tv.setPadding(0, dp(10), 0, 0)
            list.addView(tv)

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.clipChildren = false
            for (a in apps) {
                val t = tile(a)
                row.addView(t)
                if (first == null) first = t
                if (a.pkg == focusPkg && target == null) target = t
            }
            val hsv = HorizontalScrollView(this)
            hsv.isHorizontalScrollBarEnabled = false
            hsv.overScrollMode = View.OVER_SCROLL_NEVER
            hsv.clipChildren = false
            hsv.clipToPadding = false
            hsv.setPadding(0, dp(14), 0, dp(14))
            hsv.addView(row, WC, WC)
            list.addView(hsv, LinearLayout.LayoutParams(MP, WC))
        }
        val f = target ?: first
        if (f != null) f.post { f.requestFocus() }
    }

    private fun tile(a: App): View {
        val box = FrameLayout(this)
        box.isFocusable = true
        box.isClickable = true
        val lp = LinearLayout.LayoutParams(tileW, tileH)
        lp.rightMargin = dp(14)
        box.layoutParams = lp
        box.setPadding(dp(3), dp(3), dp(3), dp(3))
        box.background = shape(Color.TRANSPARENT, 13, 3, Color.TRANSPARENT)

        val inner = FrameLayout(this)
        inner.background = shape(0xFF22252C.toInt(), 10)
        inner.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat())
            }
        }
        inner.clipToOutline = true

        if (a.banner != null) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageDrawable(a.banner)
            inner.addView(iv, FrameLayout.LayoutParams(MP, MP))
        } else {
            // Sin banner: solo el icono, centrado. El nombre sale arriba al enfocar la tarjeta.
            val s = (tileH * 0.6f).toInt()
            val iv = ImageView(this)
            iv.setImageDrawable(a.icon)
            inner.addView(iv, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
        }
        box.addView(inner, FrameLayout.LayoutParams(MP, MP))

        box.setOnClickListener { launch(a) }
        box.setOnLongClickListener { togglePin(a); true }
        box.setOnFocusChangeListener { v, focused ->
            v.background = shape(Color.TRANSPARENT, 13, 3, if (focused) Color.WHITE else Color.TRANSPARENT)
            v.translationZ = if (focused) dp(8).toFloat() else 0f
            val sc = if (focused) 1.1f else 1f
            v.animate().scaleX(sc).scaleY(sc).setDuration(120).start()
            if (focused) {
                heading.text = a.label
                focusApp = a
                refreshAccent()
            }
        }
        return box
    }

    private fun pill(label: String, onClick: () -> Unit): TextView {
        val t = text(13f, Color.WHITE)
        t.text = label
        t.gravity = Gravity.CENTER
        t.setPadding(dp(18), dp(8), dp(18), dp(8))
        t.isFocusable = true
        t.isClickable = true
        t.layoutParams = LinearLayout.LayoutParams(WC, WC)
        t.background = shape(0x33FFFFFF, 20, 2, Color.TRANSPARENT)
        t.setOnClickListener { onClick() }
        t.setOnFocusChangeListener { v, focused ->
            v.background = shape(0x33FFFFFF, 20, 2, if (focused) Color.WHITE else Color.TRANSPARENT)
            val sc = if (focused) 1.08f else 1f
            v.animate().scaleX(sc).scaleY(sc).setDuration(120).start()
        }
        return t
    }

    private fun launch(a: App) {
        try {
            startActivity(a.intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir " + a.label, Toast.LENGTH_SHORT).show()
        }
    }

    // En Fire TV el aviso genérico de ajustes acaba en el Home de Amazon, así que primero se
    // prueban actividades concretas de la app de ajustes de Fire TV (com.amazon.tv.settings.v2).
    private fun openSettings(action: String, vararg direct: String) {
        for (cls in direct) {
            try {
                val cn = ComponentName("com.amazon.tv.settings.v2", cls)
                packageManager.getActivityInfo(cn, 0)
                startActivity(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) { }
        }
        for (act in listOf(action, Settings.ACTION_SETTINGS)) {
            try {
                startActivity(Intent(act).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) { }
        }
        Toast.makeText(this, "No se pudieron abrir los ajustes", Toast.LENGTH_SHORT).show()
    }

    private fun text(sp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.textSize = sp
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun shape(fill: Int, radiusDp: Int, strokeDp: Int = 0, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.setCornerRadius(dp(radiusDp).toFloat())
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke)
        return g
    }

    private fun oval(fill: Int, ring: Boolean): GradientDrawable {
        val g = GradientDrawable()
        g.setShape(GradientDrawable.OVAL)
        g.setColor(fill)
        if (ring) g.setStroke(dp(3), Color.WHITE)
        return g
    }
}
