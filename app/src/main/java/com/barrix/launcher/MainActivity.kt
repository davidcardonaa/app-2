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
import android.content.pm.ApplicationInfo
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.SystemClock
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
    private lateinit var homePill: TextView // NUEVO
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
        // Sin recortes: la tarjeta enfocada crece un 10 % y se sale un poco de su sitio.
        root.clipChildren = false
        root.clipToPadding = false
        stage.addView(root, FrameLayout.LayoutParams(MP, MP))

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.clipChildren = false
        content.clipToPadding = false
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
        // NUEVO: botón para activar el Home de Barrix (se oculta cuando ya está activo)
        homePill = pill("Activar Home") { activateHome() }
        (homePill.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(10)
        right.addView(homePill)
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
        refreshHomePill() // NUEVO
        web?.onResume()
    }

    override fun onPause() {
        super.onPause()
        web?.onPause()
        try { unregisterReceiver(ticker) } catch (e: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        web?.destroy()
        anim?.cancel()
        try { unregisterReceiver(pkgChange) } catch (e: Exception) { }
    }

    // Pulsar Home estando ya aquí: cerrar el panel, volver arriba y enfocar la primera tarjeta.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (browser != null) closeBrowser()
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

    // ---------- Activar Home (servicio de accesibilidad) ---------- NUEVO

    private fun homeActive(): Boolean {
        val v = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return v.contains("com.barrix.launcher/com.barrix.launcher.HomeService")
    }

    private fun refreshHomePill() {
        homePill.visibility = if (homeActive()) View.GONE else View.VISIBLE
    }

    private fun activateHome() {
        Toast.makeText(
            this,
            "Activando… si la tele pide permiso de depuración, marca «Permitir siempre» y acepta",
            Toast.LENGTH_LONG
        ).show()
        HomeActivator.activate(this) { r ->
            when (r) {
                is HomeActivator.Result.Ok ->
                    Toast.makeText(this, "Listo: ahora Home abre Barrix", Toast.LENGTH_LONG).show()
                is HomeActivator.Result.Error ->
                    Toast.makeText(
                        this,
                        "No se pudo activar: " + r.message + "\n¿Está activada la Depuración ADB?",
                        Toast.LENGTH_LONG
                    ).show()
            }
            refreshHomePill()
        }
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

    /**
     * Quita los márgenes transparentes de un banner (p. ej. Amazon Kids trae el logo pequeño en
     * medio de un lienzo vacío) para que, al rellenar la tarjeta, se vea el dibujo entero.
     * Si no sobra nada, devuelve el banner tal cual.
     */
    private fun trimmed(d: Drawable?): Drawable? {
        if (d == null) return null
        return try {
            val iw = if (d.intrinsicWidth > 0) d.intrinsicWidth else 320
            val ih = if (d.intrinsicHeight > 0) d.intrinsicHeight else 180
            val k = minOf(1f, 480f / iw)
            val w = maxOf(1, (iw * k).toInt())
            val h = maxOf(1, (ih * k).toInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, w, h)
            d.draw(Canvas(bmp))
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            for (y in 0 until h) for (x in 0 until w) {
                if (Color.alpha(px[y * w + x]) > 24) {
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
            val cw = x1 - x0 + 1
            val ch = y1 - y0 + 1
            // Nada que recortar (o imagen vacía): se deja el banner original.
            if (x1 < 0 || cw * ch >= w * h * 0.92f) {
                bmp.recycle()
                return d
            }
            val out = Bitmap.createBitmap(bmp, x0, y0, cw, ch)
            if (out !== bmp) bmp.recycle()
            android.graphics.drawable.BitmapDrawable(resources, out)
        } catch (e: Throwable) {
            d
        }
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

    // ---------- Navegador integrado (con cursor) ----------
    // Mando: flechas mueven el cursor (en el borde se desplaza la página), OK pulsa,
    // Atrás vuelve (o sale), Menú sale. Hace falta <uses-permission android:name="android.permission.INTERNET"/>.

    private var browser: FrameLayout? = null
    private var web: WebView? = null
    private var cursor: View? = null
    private var curX = 0f
    private var curY = 0f

    private fun openBrowser(url: String) {
        if (browser != null) return
        val b = FrameLayout(this)
        b.setBackgroundColor(Color.BLACK)
        b.isClickable = true

        val w = WebView(this)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"
        w.webViewClient = WebViewClient()
        w.webChromeClient = WebChromeClient()
        b.addView(w, FrameLayout.LayoutParams(MP, MP))

        val cur = View(this)
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(Color.WHITE)
        g.setStroke(dp(2), 0xFF111111.toInt())
        cur.background = g
        b.addView(cur, FrameLayout.LayoutParams(dp(20), dp(20)))

        stage.addView(b, FrameLayout.LayoutParams(MP, MP))
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        browser = b
        web = w
        cursor = cur
        val m = resources.displayMetrics
        curX = m.widthPixels / 2f
        curY = m.heightPixels / 2f
        moveCursor(0f, 0f)
        w.loadUrl(url)
        Toast.makeText(this, "OK: pulsar · Atrás: volver · Menú: salir", Toast.LENGTH_LONG).show()
    }

    private fun closeBrowser() {
        val b = browser ?: return
        stage.removeView(b)
        b.removeAllViews()
        web?.destroy()
        web = null
        cursor = null
        browser = null
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        first?.requestFocus()
    }

    private fun moveCursor(dx: Float, dy: Float) {
        val m = resources.displayMetrics
        val maxX = m.widthPixels - 1f
        val maxY = m.heightPixels - 1f
        val edge = dp(3).toFloat()
        val w = web
        if (w != null) {
            if (dy < 0 && curY <= edge) w.scrollBy(0, -dp(70))
            if (dy > 0 && curY >= maxY - edge) w.scrollBy(0, dp(70))
            if (dx < 0 && curX <= edge) w.scrollBy(-dp(70), 0)
            if (dx > 0 && curX >= maxX - edge) w.scrollBy(dp(70), 0)
        }
        curX = (curX + dx).coerceIn(0f, maxX)
        curY = (curY + dy).coerceIn(0f, maxY)
        cursor?.translationX = curX - dp(10)
        cursor?.translationY = curY - dp(10)
    }

    private fun touch(action: Int) {
        val w = web ?: return
        val t = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(t, t, action, curX, curY, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        w.dispatchTouchEvent(e)
        e.recycle()
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val w = web
        if (browser != null && w != null) {
            val down = ev.action == KeyEvent.ACTION_DOWN
            when (ev.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down) {
                        // Cuanto más tiempo se mantiene pulsada la flecha, más rápido va el cursor.
                        val step = dp(8) * (1f + minOf(ev.repeatCount, 12) / 3f)
                        when (ev.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> moveCursor(0f, -step)
                            KeyEvent.KEYCODE_DPAD_DOWN -> moveCursor(0f, step)
                            KeyEvent.KEYCODE_DPAD_LEFT -> moveCursor(-step, 0f)
                            else -> moveCursor(step, 0f)
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER -> {
                    if (down && ev.repeatCount == 0) touch(MotionEvent.ACTION_DOWN)
                    else if (!down) touch(MotionEvent.ACTION_UP)
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (!down) { if (w.canGoBack()) w.goBack() else closeBrowser() }
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (!down) closeBrowser()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(ev)
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

        val dynBtn = pill("", 0x33FFFFFF) { }
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
            val pb = pill("", 0x33FFFFFF) { }
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

        val nHidden = hidden().size
        if (nHidden > 0) {
            val rb = pill("", 0x33FFFFFF) { }
            rb.text = "Restaurar apps ocultas ($nHidden)"
            rb.setOnClickListener {
                prefs().edit().remove("hidden").apply()
                closePanel()
                render()
                Toast.makeText(this, "Apps restauradas", Toast.LENGTH_SHORT).show()
            }
            (rb.layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
            card.addView(rb)
        }

        val close = pill("Cerrar", 0x33FFFFFF) { closePanel() }
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
        val seenLabels = HashSet<String>() // evita apps repetidas con el mismo nombre
        val out = ArrayList<App>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val tv = cat == Intent.CATEGORY_LEANBACK_LAUNCHER
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val ai = ri.activityInfo
                if (ai.packageName == packageName || ai.packageName == "com.amazon.tv.launcher") continue
                if (!seen.add(ai.packageName)) continue

                // Filtro de apps que no son "de usar": servicios del sistema en la lista normal
                // (no de TV) y apps sin icono propio (salen con el muñeco de Android).
                val flags = ai.applicationInfo.flags
                val system = flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                    flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
                if (!tv && system) continue
                if (ai.icon == 0 && ai.applicationInfo.icon == 0) continue

                val label = ri.loadLabel(pm).toString().trim()
                if (ai.packageName in ALWAYS_HIDE_PKGS) continue
                val low = label.lowercase(es)
                if (ALWAYS_HIDE_LABELS.any { low == it || (it.length > 8 && low.contains(it)) }) continue
                if (!seenLabels.add(low)) continue

                val launch = Intent(Intent.ACTION_MAIN).addCategory(cat)
                    .setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val banner = if (tv) trimmed(ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)) else null
                val icon = ri.loadIcon(pm)
                out.add(App(label, ai.packageName, launch, banner, icon, tv, dominant(banner ?: icon)))
            }
        }
        out.add(googleApp())
        if ("soundcloud" !in seenLabels) out.add(soundcloudApp()) // solo si no está la app de verdad
        out.sortBy { it.label.lowercase(es) }
        return out
    }

    // Apps que nunca se muestran (por paquete o por nombre, en español e inglés).
    private val ALWAYS_HIDE_PKGS = setOf(
        "com.amazon.gamehub",                  // Amazon Game Center
        "com.amazon.ftv.profilepicker",        // Interfaz de usuario del perfil de Fire TV
        "com.amazon.ssm",                      // Monitor de estado del sistema
        "com.amazon.cloud9"                    // Navegador Silk
    )
    private val ALWAYS_HIDE_LABELS = listOf(
        "game center", "descargas", "downloads",
        "interfaz de usuario del perfil", "profile",
        "monitor de estado", "system status",
        "silk", "navegador",
        "what should i watch", "qué ver",
        "news", "noticias"
    )

    /** Acceso directo a Google: abre google.com en el navegador del sistema. */
    private fun googleApp(): App {
        val w = 640
        val h = 360
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.textSize = 150f
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        val letters = "Google"
        val cols = intArrayOf(0xFF4285F4.toInt(), 0xFFEA4335.toInt(), 0xFFFBBC05.toInt(),
            0xFF4285F4.toInt(), 0xFF34A853.toInt(), 0xFFEA4335.toInt())
        var total = 0f
        for (ch in letters) total += p.measureText(ch.toString())
        var x = (w - total) / 2f
        val y = h / 2f + 50f
        for (i in letters.indices) {
            p.color = cols[i]
            c.drawText(letters[i].toString(), x, y, p)
            x += p.measureText(letters[i].toString())
        }
        val d = android.graphics.drawable.BitmapDrawable(resources, bmp)
        return App("Google", "barrix.shortcut.google", browserIntent("https://www.google.com"), d, d, true, 0xFF4285F4.toInt())
    }

    /** Acceso directo a SoundCloud web (para cuando la app no está disponible en tu país). */
    private fun soundcloudApp(): App {
        val w = 640
        val h = 360
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFFF5500.toInt())
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        p.textSize = 96f
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        p.textAlign = android.graphics.Paint.Align.CENTER
        c.drawText("SoundCloud", w / 2f, h / 2f + 34f, p)
        val d = android.graphics.drawable.BitmapDrawable(resources, bmp)
        return App("SoundCloud", "barrix.shortcut.soundcloud", browserIntent("https://soundcloud.com"), d, d, true, 0xFFFF5500.toInt())
    }

    /** Abre la web en el mejor navegador instalado (Chrome o Firefox si los hay; si no, el del sistema). */
    private fun browserIntent(url: String): Intent {
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (pkg in listOf("com.android.chrome", "org.mozilla.firefox", "org.mozilla.firefox_tv", "com.puffin.tv")) {
            try {
                packageManager.getPackageInfo(pkg, 0)
                i.setPackage(pkg)
                break
            } catch (e: Exception) { }
        }
        return i
    }

    private fun pins(): Set<String> = prefs().getStringSet("pins", emptySet())!!.toSet()

    private fun hidden(): Set<String> = prefs().getStringSet("hidden", emptySet())!!.toSet()

    /** Botón Menú del mando sobre una tarjeta: la oculta. Se restauran desde "Aspecto". */
    private fun hideApp(a: App) {
        val h = hidden().toMutableSet()
        h.add(a.pkg)
        prefs().edit().putStringSet("hidden", h).apply()
        Toast.makeText(this, a.label + " oculta (se restaura desde Aspecto)", Toast.LENGTH_LONG).show()
        render()
    }

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
        val hid = hidden()
        val visible = allApps.filter { it.pkg !in hid }
        val groups = listOf(
            "Favoritas" to visible.filter { it.pkg in pins },
            "Apps" to visible.filter { it.tv && it.pkg !in pins },
            "Más apps" to visible.filter { !it.tv && it.pkg !in pins }
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
            fillWithIcon(inner, a)
        }
        box.addView(inner, FrameLayout.LayoutParams(MP, MP))

        box.setOnClickListener { launch(a) }
        box.setOnLongClickListener { togglePin(a); true }
        box.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_MENU && ev.action == KeyEvent.ACTION_UP) { hideApp(a); true }
            else code == KeyEvent.KEYCODE_MENU
        }
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

    /**
     * Apps sin banner: la tarjeta se llena entera con el icono.
     * - Icono adaptativo (Android 8+): su fondo cubre toda la tarjeta y el dibujo va grande encima.
     * - Icono normal: fondo con el color de la app y el icono lo más grande posible.
     */
    private fun fillWithIcon(inner: FrameLayout, a: App) {
        val ic = a.icon
        val side = tileH - dp(6) // alto útil de la tarjeta
        if (Build.VERSION.SDK_INT >= 26 && ic is AdaptiveIconDrawable) {
            val bgIv = ImageView(this)
            bgIv.scaleType = ImageView.ScaleType.CENTER_CROP
            bgIv.setImageDrawable(ic.background)
            inner.addView(bgIv, FrameLayout.LayoutParams(MP, MP))
            val fgIv = ImageView(this)
            fgIv.scaleType = ImageView.ScaleType.FIT_XY
            fgIv.setImageDrawable(ic.foreground)
            val big = (side * 1.3f).toInt() // la zona segura del icono es ~2/3 del lienzo
            inner.addView(fgIv, FrameLayout.LayoutParams(big, big, Gravity.CENTER))
        } else {
            val hsv = FloatArray(3)
            val base = if (a.color != 0) a.color else 0xFF3A3F4B.toInt()
            Color.colorToHSV(base, hsv)
            hsv[2] = minOf(hsv[2], 0.45f)
            inner.setBackgroundColor(Color.HSVToColor(hsv))
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setImageDrawable(ic)
            val s = (side * 0.9f).toInt()
            inner.addView(iv, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
        }
    }

    private fun pill(label: String, fill: Int = 0x80000000.toInt(), onClick: () -> Unit): TextView {
        val t = text(13f, Color.WHITE)
        t.text = label
        t.gravity = Gravity.CENTER
        t.setPadding(dp(18), dp(8), dp(18), dp(8))
        t.isFocusable = true
        t.isClickable = true
        t.layoutParams = LinearLayout.LayoutParams(WC, WC)
        t.background = shape(fill, 20)
        t.setOnClickListener { onClick() }
        // Enfocado: píldora blanca con texto oscuro (limpio, sin borde ni zoom que difuminen el texto).
        t.setOnFocusChangeListener { v, focused ->
            v.background = shape(if (focused) Color.WHITE else fill, 20)
            (v as TextView).setTextColor(if (focused) 0xFF111111.toInt() else Color.WHITE)
        }
        return t
    }

    private fun launch(a: App) {
        // Los accesos web (Google, SoundCloud) se abren en el navegador integrado.
        if (a.pkg.startsWith("barrix.shortcut.")) {
            val url = a.intent.dataString
            if (url != null) { openBrowser(url); return }
        }
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
