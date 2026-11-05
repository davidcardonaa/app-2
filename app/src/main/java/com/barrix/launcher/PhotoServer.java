package com.barrix.launcher;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * Servidor web mínimo para subir la foto de fondo desde el móvil (misma Wi-Fi).
 * GET /?k=TOKEN        -> página con selector de foto (reduce la foto en el móvil y la envía)
 * POST /upload?k=TOKEN -> recibe el JPEG, lo valida y lo deja en "target"
 * Solo vive mientras la pantalla del QR está abierta. El token evita que otros dispositivos
 * de la red suban fotos sin haber escaneado el QR.
 */
public final class PhotoServer {

    public interface Validator { boolean isValidImage(File f); }

    private static final long MAX_BYTES = 12L * 1024 * 1024;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File target;
    private final String token;
    private final Validator validator;
    private final Runnable onUploaded;
    private volatile ServerSocket server;
    private volatile boolean running;

    public PhotoServer(File target, String token, Validator validator, Runnable onUploaded) {
        this.target = target;
        this.token = token;
        this.validator = validator;
        this.onUploaded = onUploaded;
    }

    /** Arranca en el primer puerto libre entre 8080 y 8099 y lo devuelve. */
    public int start() throws IOException {
        IOException last = null;
        for (int port = 8080; port < 8100; port++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(port));
                server = s;
                running = true;
                final ServerSocket fs = s;
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { acceptLoop(fs); }
                }, "barrix-photo-server");
                t.setDaemon(true);
                t.start();
                return port;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("Sin puerto libre");
    }

    public void stop() {
        running = false;
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try { s.close(); } catch (IOException e) { /* nada */ }
        }
    }

    private void acceptLoop(ServerSocket s) {
        while (running) {
            try {
                final Socket c = s.accept();
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { handle(c); }
                }, "barrix-photo-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket c) {
        try {
            c.setSoTimeout(20000);
            InputStream in = new BufferedInputStream(c.getInputStream());
            OutputStream out = c.getOutputStream();

            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] rl = lines[0].split(" ");
            if (rl.length < 2) { reply(out, 400, "text/plain", "Solicitud no válida"); return; }
            String method = rl[0];
            String path = rl[1];
            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }
            long len = -1;
            for (int i = 1; i < lines.length; i++) {
                int idx = lines[i].indexOf(':');
                if (idx > 0 && lines[i].substring(0, idx).trim().equalsIgnoreCase("content-length")) {
                    try { len = Long.parseLong(lines[i].substring(idx + 1).trim()); } catch (NumberFormatException e) { len = -1; }
                }
            }
            boolean authorized = ("k=" + token).equals(queryParam(query, "k"));

            if ("GET".equals(method) && "/".equals(path)) {
                if (!authorized) { reply(out, 403, "text/html", simplePage("Enlace no válido. Vuelve a escanear el QR de la tele.")); return; }
                reply(out, 200, "text/html", page());
            } else if ("POST".equals(method) && "/upload".equals(path)) {
                if (!authorized) { reply(out, 403, "text/plain", "No autorizado"); return; }
                if (len <= 0) { reply(out, 411, "text/plain", "Falta el tamaño"); return; }
                if (len > MAX_BYTES) { reply(out, 413, "text/plain", "La foto es demasiado grande"); return; }
                File tmp = new File(target.getPath() + ".tmp");
                FileOutputStream fo = new FileOutputStream(tmp);
                try {
                    byte[] buf = new byte[16384];
                    long left = len;
                    while (left > 0) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) throw new IOException("Conexión cortada");
                        fo.write(buf, 0, n);
                        left -= n;
                    }
                } finally {
                    fo.close();
                }
                if (!validator.isValidImage(tmp)) {
                    tmp.delete();
                    reply(out, 400, "text/plain", "Ese archivo no es una imagen válida");
                    return;
                }
                if (!tmp.renameTo(target)) {
                    copy(tmp, target);
                    tmp.delete();
                }
                reply(out, 200, "text/plain", "ok");
                if (onUploaded != null) onUploaded.run();
            } else {
                reply(out, 404, "text/plain", "No encontrado");
            }
        } catch (Exception e) {
            // conexión rota o tiempo agotado: se ignora
        } finally {
            try { c.close(); } catch (IOException e) { /* nada */ }
        }
    }

    /** Devuelve "k=valor" si existe el parámetro, o "" si no. */
    private static String queryParam(String query, String name) {
        for (String p : query.split("&")) {
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).equals(name)) return name + "=" + p.substring(eq + 1);
        }
        return "";
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0, ch;
        while ((ch = in.read()) >= 0) {
            b.write(ch);
            if (b.size() > 16384) return null;
            if ((state == 0 || state == 2) && ch == '\r') state++;
            else if ((state == 1 || state == 3) && ch == '\n') state++;
            else state = (ch == '\r') ? 1 : 0;
            if (state == 4) return new String(b.toByteArray(), 0, b.size() - 4, UTF8);
        }
        return null;
    }

    private static void reply(OutputStream out, int code, String type, String body) throws IOException {
        byte[] data = body.getBytes(UTF8);
        String status;
        switch (code) {
            case 200: status = "OK"; break;
            case 400: status = "Bad Request"; break;
            case 403: status = "Forbidden"; break;
            case 404: status = "Not Found"; break;
            case 411: status = "Length Required"; break;
            case 413: status = "Payload Too Large"; break;
            default: status = "Error"; break;
        }
        String h = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "; charset=utf-8\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(h.getBytes(UTF8));
        out.write(data);
        out.flush();
    }

    private static void copy(File a, File b) throws IOException {
        InputStream i = new FileInputStream(a);
        OutputStream o = new FileOutputStream(b);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
        } finally {
            i.close();
            o.close();
        }
    }

    private static String simplePage(String msg) {
        return "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<body style='font-family:sans-serif;background:#111;color:#fff;padding:32px'>" + msg;
    }

    private String page() {
        return "<!doctype html><html lang=es><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><title>Barrix · Foto de fondo</title>"
            + "<style>body{margin:0;font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#0e1014;color:#fff;"
            + "display:flex;min-height:100vh;align-items:center;justify-content:center}"
            + ".c{width:min(92vw,420px);padding:24px;background:#171a21;border-radius:18px}"
            + "h1{font-size:22px;margin:0 0 6px}p{color:#9aa3b2;font-size:14px;margin:0 0 18px}"
            + "label.b,button{display:block;width:100%;box-sizing:border-box;text-align:center;padding:14px;border-radius:12px;"
            + "font-size:16px;font-weight:600;border:0;cursor:pointer;margin-top:12px}"
            + "label.b{background:#262b36;color:#fff}button{background:#e5252a;color:#fff}button:disabled{opacity:.4}"
            + "img{width:100%;border-radius:12px;margin-top:14px;display:none}#s{margin-top:14px;font-size:15px;min-height:20px}"
            + "</style></head><body><div class=c><h1>Foto de fondo</h1>"
            + "<p>Elige una foto de tu móvil y aparecerá en la tele. Mejor si es horizontal.</p>"
            + "<label class=b>Elegir foto<input id=f type=file accept='image/*' hidden></label>"
            + "<img id=p><button id=u disabled>Enviar a la tele</button><div id=s></div></div>"
            + "<script>"
            + "var K='" + token + "',f=document.getElementById('f'),p=document.getElementById('p'),"
            + "u=document.getElementById('u'),s=document.getElementById('s'),blob=null;"
            + "function msg(t){s.textContent=t}"
            + "f.onchange=function(){var file=f.files[0];if(!file)return;msg('Preparando…');u.disabled=true;"
            + "var url=URL.createObjectURL(file),im=new Image();"
            + "im.onload=function(){var M=1920,w=im.naturalWidth,h=im.naturalHeight,k=Math.min(1,M/Math.max(w,h));"
            + "var c=document.createElement('canvas');c.width=Math.round(w*k);c.height=Math.round(h*k);"
            + "c.getContext('2d').drawImage(im,0,0,c.width,c.height);"
            + "c.toBlob(function(b){blob=b;p.src=URL.createObjectURL(b);p.style.display='block';u.disabled=false;msg('')},'image/jpeg',0.88)};"
            + "im.onerror=function(){msg('No he podido leer esa foto. Prueba con otra.')};im.src=url};"
            + "u.onclick=function(){if(!blob)return;u.disabled=true;msg('Enviando…');"
            + "fetch('/upload?k='+K,{method:'POST',body:blob}).then(function(r){return r.text().then(function(t){"
            + "if(r.ok)msg('¡Listo! Ya se ve en la tele.');else{msg('Error: '+t);u.disabled=false}})})"
            + ".catch(function(){msg('No se pudo conectar con la tele. ¿Misma Wi-Fi?');u.disabled=false})};"
            + "</script></body></html>";
    }
}
