package com.bradyxu.headunitmanager;

import android.os.Environment;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.SecureRandom;

final class TransferServer {
    private volatile ServerSocket server;
    private Thread worker;
    private String token;

    synchronized String start() throws Exception {
        if (server != null) return token;
        byte[] secret = new byte[16];
        new SecureRandom().nextBytes(secret);
        StringBuilder code = new StringBuilder(32);
        for (byte value : secret) code.append(String.format("%02x", value & 0xff));
        token = code.toString();
        server = new ServerSocket(8088);
        worker = new Thread(() -> {
            while (server != null && !server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(30000);
                    handle(socket);
                } catch (Exception ignored) { }
            }
        }, "headunit-file-transfer");
        worker.start();
        return token;
    }

    synchronized void stop() {
        if (server != null) {
            try { server.close(); } catch (Exception ignored) { }
            server = null;
        }
    }

    private void handle(Socket socket) throws Exception {
        BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
        OutputStream output = socket.getOutputStream();
        String request = line(input);
        if (request == null) return;
        String[] first = request.split(" ");
        if (first.length < 2) { response(output, 400, "Bad Request"); return; }
        int length = -1;
        String encodedName = null;
        String header;
        int headerBytes = 0;
        while ((header = line(input)) != null && !header.isEmpty()) {
            headerBytes += header.length();
            if (headerBytes > 16384) { response(output, 400, "Headers too large"); return; }
            String lower = header.toLowerCase();
            if (lower.startsWith("content-length:")) length = Integer.parseInt(header.substring(15).trim());
            if (lower.startsWith("x-filename:")) encodedName = header.substring(11).trim();
        }
        String path = first[1];
        if (!path.endsWith("token=" + token)) { response(output, 403, "Forbidden"); return; }
        if ("GET".equals(first[0])) {
            response(output, 200, "<!doctype html><meta charset='utf-8'><title>车机文件快传</title>" +
                "<h2>上传文件到车机 Download/HeadunitManager/incoming</h2><input type='file' id='file'>" +
                "<button onclick='send()'>上传</button><p id='status'></p><script>async function send(){" +
                "let f=document.getElementById('file').files[0];if(!f)return;" +
                "let r=await fetch('/upload?token=" + token + "',{method:'POST',headers:{'X-Filename':encodeURIComponent(f.name)},body:f});" +
                "document.getElementById('status').textContent=await r.text();}</script>", true);
            return;
        }
        if (!"POST".equals(first[0]) || !path.startsWith("/upload?")) { response(output, 405, "Method Not Allowed"); return; }
        if (length < 0 || length > 512 * 1024 * 1024 || encodedName == null) {
            response(output, 400, "Invalid file"); return;
        }
        String name = URLDecoder.decode(encodedName, "UTF-8");
        if (FileOps.safeChild(new File("/"), name) == null) { response(output, 400, "Invalid name"); return; }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HeadunitManager/incoming");
        if (!dir.exists() && !dir.mkdirs()) { response(output, 500, "Cannot create directory"); return; }
        File target = new File(dir, name);
        if (target.exists()) { response(output, 409, "File already exists"); return; }
        int remain = length;
        try (FileOutputStream file = new FileOutputStream(target)) {
            byte[] buf = new byte[65536];
            while (remain > 0) {
                int n = input.read(buf, 0, Math.min(remain, buf.length));
                if (n < 0) throw new Exception("Incomplete upload");
                file.write(buf, 0, n); remain -= n;
            }
        } catch (Exception e) { target.delete(); throw e; }
        response(output, 200, "上传完成：" + name);
    }

    private static String line(BufferedInputStream in) throws Exception {
        StringBuilder s = new StringBuilder(); int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') break;
            if (ch != '\r') s.append((char) ch);
            if (s.length() > 16384) throw new Exception("Line too long");
        }
        return ch == -1 && s.length() == 0 ? null : s.toString();
    }

    private static void response(OutputStream out, int code, String body) throws Exception {
        response(out, code, body, false);
    }

    private static void response(OutputStream out, int code, String body, boolean html) throws Exception {
        byte[] bytes = body.getBytes("UTF-8");
        String status = code == 200 ? "OK" : "Error";
        out.write(("HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " +
            (html ? "text/html" : "text/plain") + "; charset=utf-8\r\n" +
            "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
        out.write(bytes); out.flush();
    }
}
