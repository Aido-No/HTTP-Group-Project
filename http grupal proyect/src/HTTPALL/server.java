package HTTPALL;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class server {

    private static final Logger logger = Logger.getLogger("HTTPServer");
    private static PrintWriter logWriter;
    private static String API_KEY = null;
    private static Map<Integer, String> etags = new ConcurrentHashMap<>();

    private static Map<Integer, String> memes = new ConcurrentHashMap<>();
    private static final AtomicInteger nextId = new AtomicInteger(1);

    private static final String memesFolder = "src/HTTPALL/Content/Memes";
    private static final String htmlEndPoint = "src/HTTPALL/Content/index.html";

    public static void main(String[] args) throws Exception {
        setUpHashMap();
        initLoging();

        int port = 3000;

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server listening on port " + port);
            log("INFO", "Server started on port " + port);

            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(() -> handleConnection(socket)).start();
            }
        }
    }

    private static void setUpHashMap() {
        File folder = new File(memesFolder);

        if (!folder.exists() || !folder.isDirectory()) {
            System.out.println("Invalid folder path: " + memesFolder);
            return;
        }

        File[] files = folder.listFiles();
        if (files == null) return;

        int id = 0;

        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".json")) {
                memes.put(id, file.getPath());
                id++;
            }
        }

        loadApiKey();
    }

    private static void loadApiKey() {
        try {
            Path keyPath = Paths.get("api.key");
            API_KEY = Files.readString(keyPath).trim();
        } catch (Exception e) {
            System.out.println("No API key found, authentication disabled");
            API_KEY = null;
        }
    }

    private static boolean isAuthenticated(Map<String, String> headers) {
        if (API_KEY == null) return true;

        String clientKey = headers.get("x-api-key");
        return clientKey != null && clientKey.equals(API_KEY);
    }

    private static void initLoging() {
        try {
            FileWriter fw = new FileWriter("server.log", true);
            logWriter = new PrintWriter(fw, true);
            logger.setUseParentHandlers(false);
        } catch (Exception e) {
            System.err.println("Failed to initialize log file");
        }
    }

    private static void log(String level, String message) {
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        String logEntry = String.format("[%s] [%s] %s", timestamp, level, message);

        if (logWriter != null) {
            logWriter.println(logEntry);
        }
    }

    private static void handleConnection(Socket socket) {
        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                OutputStream out = socket.getOutputStream()
        ) {
            String requestLine = in.readLine();
            if (requestLine == null) return;

            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String path = parts[1];

            Map<String, String> headerMap = new HashMap<>();
            String line;
            int contentLength = 0;

            while (!(line = in.readLine()).isEmpty()) {
                int colonIndex = line.indexOf(':');

                if (colonIndex > 0) {
                    String key = line.substring(0, colonIndex).toLowerCase().trim();
                    String value = line.substring(colonIndex + 1).trim();

                    headerMap.put(key, value);

                    if (key.equals("content-length")) {
                        contentLength = Integer.parseInt(value);
                    }
                }
            }

            String body = "";

            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                in.read(buf, 0, contentLength);
                body = new String(buf);
            }

            log("INFO", String.format("Request | %s %s", method, path));

            byte[] response = route(method, path, body, headerMap);
            out.write(response);
            out.flush();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static byte[] route(String method, String path, String body, Map<String, String> headers) {

        if (!isAuthenticated(headers)) {
            return jsonResponse(401, "Unauthorized", "{\"error\":\"Valid API key required\"}");
        }

        if ("GET".equals(method)) {
            return handleGetRequest(path, body, headers);
        }

        if ("POST".equals(method) && "/memes".equals(path)) {
            int id = nextId.getAndIncrement();
            String entry = "{\"id\":" + id + "," + body.substring(1);
            memes.put(id, entry);
            String etag = Integer.toHexString(entry.hashCode());
            etags.put(id, etag);
            return jsonResponseWithCookie(201, "Created", entry);
        }

        if ("PUT".equals(method) && path.matches("/memes/[^/]+")) {
            int id = Integer.parseInt(path.split("/")[2]);

            if (memes.containsKey(id)) {
                return handlePutRequest(id, body);
            } else {
                return jsonResponse(404, "Not Found");
            }
        }

        if ("DELETE".equals(method)) {
            return handleDeleteRequest(path, body);
        }

        return jsonResponse(404, "Not Found", "{\"error\":\"Not found\"}");
    }



    private static byte[] handleGetRequest(String path, String body, Map<String,String> headers) {
        if (path.equals("/") || path.isEmpty()) {
            return getStatichtml();
        }

        if (path.equals("/resource")) {
            return getAllResources();
        }

        if (path.matches("/resource/[^/]+")) {
            int id = Integer.parseInt(path.split("/")[2]);
            return getResourceById(id, headers);
        }

        if (path.startsWith("/Resources/")) {
            return gethtmlResource(path);
        }

        return jsonResponse(400, "Bad Request");
    }

    private static byte[] handlePutRequest(int id, String body) {
        String entry = body.substring(1);

        if (entry.isBlank()) {
            return jsonResponse(400, "Bad Request", "{\"error\":\"Empty body\"}");
        }

        String memepath = memes.get(id);

        try {
            Files.write(
                    Paths.get(memepath),
                    entry.getBytes(),
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception e) {
            return jsonResponse(500, "Internal Server Error");
        }
        String newETag = Integer.toHexString(entry.hashCode());
        etags.put(id, newETag);
        
        return jsonResponseWithETag(200, "OK", entry, newETag);
        //return jsonResponse(200, "OK", entry);
    }

    private static byte[] handleDeleteRequest(String path, String body) {
        if (path.matches("/resource/[^/]+")) {
            int id = Integer.parseInt(path.split("/")[2]);
            return deleteResourceById(id);
        }

        return jsonResponse(400, "Bad Request");
    }


    private static byte[] getAllResources() {
        String json = "[" + String.join(",", memes.values()) + "]";
        return jsonResponse(200, "OK", json);
    }

    private static byte[] deleteResourceById(int id) {
        if (memes.containsKey(id)) {
            try {
                String memePath = memes.get(id);
                Files.deleteIfExists(Paths.get(memePath));
                memes.remove(id);
                etags.remove(id); 
                return jsonResponse(200, "OK");
            } catch (Exception e) {
                return jsonResponse(500, "Internal Server Error");
            }
        }
        return jsonResponse(404, "Not found");
    }

    private static byte[] getResourceById(int id, Map<String, String> headers) {
        if (memes.containsKey(id)) {
            try {
                String memePath = memes.get(id);
                String meme = new String(Files.readAllBytes(Paths.get(memePath)));
                String currentETag = etags.get(id);
                if (currentETag == null) {
                    currentETag = Integer.toHexString(meme.hashCode());
                    etags.put(id, currentETag);
                }
                String clientETag = headers.get("if-none-match");
                if (clientETag != null && clientETag.startsWith("\"") && clientETag.endsWith("\"")) {
                    clientETag = clientETag.substring(1, clientETag.length() - 1);
                }
                System.out.println("Comparing client ETag: '" + clientETag + "' vs server: '" + currentETag + "'");
                if (clientETag != null && clientETag.equals(currentETag)) {
                    return jsonResponse(304, "Not Modified"); 
                }
                return jsonResponseWithETag(200, "OK", meme, currentETag);
                //return jsonResponse(200, "OK", meme);
            } catch (Exception e) {
                return jsonResponse(500, "Internal Server Error");
            }
        }
        return jsonResponse(404, "Not found");
    }

    private static byte[] getStatichtml() {
        try {
            Path fullPath = Paths.get(htmlEndPoint);
            byte[] fileBytes = Files.readAllBytes(fullPath);
            return buildResponse(200, "OK", "text/html", fileBytes);
        } catch (Exception e) {
            return jsonResponse(500, "Internal server error");
        }
    }

    private static byte[] gethtmlResource(String fileName) {
        try {
            Path fullPath = Paths.get("http grupal proyect/src/HTTPALL/Content" + fileName);

            if (Files.exists(fullPath)) {
                byte[] fileBytes = Files.readAllBytes(fullPath);
                return buildResponse(200, "OK", getContentType(fileName), fileBytes);
            }

            return jsonResponse(404, "resource not found");
        } catch (Exception e) {
            return jsonResponse(500, "Internal server error");
        }
    }

    private static String getContentType(String fileName) {
        if (fileName.endsWith(".html")) return "text/html";
        if (fileName.endsWith(".css")) return "text/css";
        if (fileName.endsWith(".js")) return "application/javascript";
        if (fileName.endsWith(".png")) return "image/png";
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) return "image/jpeg";
        if (fileName.endsWith(".ico")) return "image/x-icon";
        if (fileName.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }

    private static byte[] buildResponse(int code, String reason, String contentType, byte[]... bodies) {
        int totalLength = 0;

        for (byte[] b : bodies) {
            totalLength += b.length;
        }

        String headers =
                "HTTP/1.1 " + code + " " + reason + "\r\n" +
                        "Content-Type: " + contentType + "\r\n" +
                        "Content-Length: " + totalLength + "\r\n" +
                        "Connection: close\r\n\r\n";

        byte[] headerBytes = headers.getBytes();
        byte[] combined = new byte[headerBytes.length + totalLength];

        int pos = 0;

        System.arraycopy(headerBytes, 0, combined, pos, headerBytes.length);
        pos += headerBytes.length;

        for (byte[] b : bodies) {
            System.arraycopy(b, 0, combined, pos, b.length);
            pos += b.length;
        }

        return combined;
    }

    private static byte[] jsonResponse(int code, String reason, String json) {
        return buildResponse(code, reason, "application/json", json.getBytes());
    }

    private static byte[] jsonResponse(int code, String reason) {
        //return jsonResponse(code, reason, "{}");
        return jsonResponse(code, reason, "");
    }

    private static byte[] jsonResponseWithCookie(int code, String reason, String json) {
        String cookieValue = "session_" + System.currentTimeMillis();
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: " + json.getBytes().length + "\r\n" +
                        "Set-Cookie: session_id=" + cookieValue + "; Path=/; Max-Age=180\r\n" +
                        "Connection: close\r\n\r\n";
        
        byte[] headerBytes = headers.getBytes();
        byte[] jsonBytes = json.getBytes();
        byte[] combined = new byte[headerBytes.length + jsonBytes.length];
        
        System.arraycopy(headerBytes, 0, combined, 0, headerBytes.length);
        System.arraycopy(jsonBytes, 0, combined, headerBytes.length, jsonBytes.length);
        
        return combined;
    }
    
    private static byte[] jsonResponseWithETag(int code, String reason, String json, String etag) {
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: " + json.getBytes().length + "\r\n"
            + "ETag: \"" + etag + "\"\r\n"  // ETag header
            + "Cache-Control: private, max-age=3600\r\n"  // Optional: cache for 1 hour
            + "Connection: close\r\n\r\n";
        
        byte[] headerBytes = headers.getBytes();
        byte[] jsonBytes = json.getBytes();
        byte[] combined = new byte[headerBytes.length + jsonBytes.length];
        
        System.arraycopy(headerBytes, 0, combined, 0, headerBytes.length);
        System.arraycopy(jsonBytes, 0, combined, headerBytes.length, jsonBytes.length);
        
        return combined;
    }
    
}