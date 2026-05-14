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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.ArrayList;
import java.util.logging.*;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.security.MessageDigest;

public class server {

	private static final Logger logger = Logger.getLogger("HTTPServer");
	private static PrintWriter logWriter;
	private static String API_KEY = null;
    private static Map<Integer, String> memes = new ConcurrentHashMap<>();
    private static final AtomicInteger nextId = new AtomicInteger(1);
    
    // FIXED: Use relative paths instead of absolute paths
    private static final String memesFolder = "Content/Memes";
    private static final String htmlEndPoint = "Content/index.html";
    private static final String baseContentPath = "Content";
    
    // ETag storage - Now persistent with file hashes
    private static Map<Integer, String> etags = new ConcurrentHashMap<>();
    private static Map<String, String> fileEtagCache = new ConcurrentHashMap<>(); // Cache file paths to ETags
    
    // Session storage for cookies
    private static Map<String, Session> sessions = new ConcurrentHashMap<>();
    
    static class Session {
        String sessionId;
        String userId;
        long createdAt;
        long lastAccessedAt;
        
        Session(String sessionId, String userId) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.createdAt = System.currentTimeMillis();
            this.lastAccessedAt = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return (System.currentTimeMillis() - lastAccessedAt) > (30 * 60 * 1000); // 30 minutes timeout
        }
        
        void refresh() {
            this.lastAccessedAt = System.currentTimeMillis();
        }
    }
    
    public static void main(String[] args) throws Exception {
        // Debug: Show working directory
        System.out.println("Working Directory: " + System.getProperty("user.dir"));
        
        setUpHashMap();
        initLoging();
        loadApiKey();
        
        // Load existing ETags from disk
        loadEtagsFromDisk();
        
        // Start session cleanup thread
        startSessionCleanup();
        
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
    
    private static void loadEtagsFromDisk() {
        try {
            Path etagFile = Paths.get("etags.dat");
            if (Files.exists(etagFile)) {
                List<String> lines = Files.readAllLines(etagFile);
                for (String line : lines) {
                    String[] parts = line.split(":", 2);
                    if (parts.length == 2) {
                        int id = Integer.parseInt(parts[0]);
                        String etag = parts[1];
                        etags.put(id, etag);
                        System.out.println("Loaded ETag for ID " + id + ": " + etag);
                    }
                }
                log("INFO", "Loaded " + etags.size() + " ETags from disk");
            }
        } catch (Exception e) {
            log("WARNING", "Could not load ETags from disk: " + e.getMessage());
        }
    }
    
    private static void saveEtagsToDisk() {
        try {
            Path etagFile = Paths.get("etags.dat");
            List<String> lines = new ArrayList<>();
            for (Map.Entry<Integer, String> entry : etags.entrySet()) {
                lines.add(entry.getKey() + ":" + entry.getValue());
            }
            Files.write(etagFile, lines);
            log("INFO", "Saved " + etags.size() + " ETags to disk");
        } catch (Exception e) {
            log("WARNING", "Could not save ETags to disk: " + e.getMessage());
        }
    }
    
    private static String generateEtagFromContent(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().substring(0, 16); // Use first 16 chars for shorter ETag
        } catch (Exception e) {
            // Fallback to simple hash
            return Integer.toHexString(content.hashCode());
        }
    }
    
    private static String getEtagForResource(int id) {
        // Try to get from cache first
        if (etags.containsKey(id)) {
            return etags.get(id);
        }
        
        // Generate from file content
        try {
            String memePath = memes.get(id);
            if (memePath != null) {
                Path path = Paths.get(memePath);
                if (Files.exists(path)) {
                    String content = new String(Files.readAllBytes(path));
                    String etag = generateEtagFromContent(content);
                    etags.put(id, etag);
                    return etag;
                }
            }
        } catch (Exception e) {
            log("ERROR", "Error generating ETag for ID " + id + ": " + e.getMessage());
        }
        return null;
    }
    
    private static void startSessionCleanup() {
        Thread cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5 * 60 * 1000); // Run every 5 minutes
                    sessions.entrySet().removeIf(entry -> entry.getValue().isExpired());
                    log("INFO", "Session cleanup completed. Active sessions: " + sessions.size());
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }
    
    private static String getOrCreateSession(Map<String, String> headers) {
        // Parse cookies from request
        String cookieHeader = headers.get("cookie");
        String existingSessionId = null;
        
        if (cookieHeader != null) {
            String[] cookies = cookieHeader.split(";");
            for (String cookie : cookies) {
                String[] parts = cookie.trim().split("=", 2);
                if (parts.length == 2 && "session_id".equals(parts[0])) {
                    existingSessionId = parts[1];
                    break;
                }
            }
        }
        
        // If session exists and is valid, refresh and return it
        if (existingSessionId != null && sessions.containsKey(existingSessionId)) {
            Session session = sessions.get(existingSessionId);
            if (!session.isExpired()) {
                session.refresh();
                log("INFO", "Existing session refreshed: " + existingSessionId);
                return existingSessionId;
            } else {
                sessions.remove(existingSessionId);
            }
        }
        
        // Create new session
        String newSessionId = UUID.randomUUID().toString();
        Session newSession = new Session(newSessionId, "anonymous_" + System.currentTimeMillis());
        sessions.put(newSessionId, newSession);
        log("INFO", "New session created: " + newSessionId);
        return newSessionId;
    }
    
    private static void loadApiKey() {
        try {
            Path keyPath = Paths.get("api.key");
            API_KEY = Files.readString(keyPath).trim();
            System.out.println("API key loaded successfully");
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
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String logEntry = String.format("[%s] [%s] %s", timestamp, level, message);
        if (logWriter != null) {
            logWriter.println(logEntry);
        }
        // Also print to console for important messages
        if ("ERROR".equals(level) || "WARNING".equals(level)) {
            System.err.println(logEntry);
        }
    }

    private static void setUpHashMap(){
        // Try multiple possible locations for the memes folder
        File folder = null;
        String[] possiblePaths = {
            memesFolder,
            "./" + memesFolder,
            "../" + memesFolder,
            "src/HTTPALL/" + memesFolder,
            "./src/HTTPALL/" + memesFolder
        };
        
        for (String path : possiblePaths) {
            File testFolder = new File(path);
            if (testFolder.exists() && testFolder.isDirectory()) {
                folder = testFolder;
                System.out.println("Found memes folder at: " + testFolder.getAbsolutePath());
                break;
            }
        }
        
        if (folder == null || !folder.exists() || !folder.isDirectory()) {
            System.err.println("Invalid folder path: " + memesFolder);
            System.err.println("Tried paths: " + String.join(", ", possiblePaths));
            return;
        }

        File[] files = folder.listFiles();
        if (files == null) return;

        int id = 0;
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".json")) {
                memes.put(id, file.getAbsolutePath());
                System.out.println("Loaded meme: " + file.getName() + " with ID: " + id);
                id++;
            }
        }
        System.out.println("Total memes loaded: " + memes.size());
    }

    private static void handleConnection(Socket socket) {
        log("INFO", "New connection from " + socket.getRemoteSocketAddress());
        
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             OutputStream out = socket.getOutputStream()) {

            String requestLine = in.readLine();
            if (requestLine == null) {
                log("WARNING", "Empty request from " + socket.getRemoteSocketAddress());
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                log("WARNING", "Malformed request line: " + requestLine);
                return;
            }
            
            String method = parts[0];
            String path = parts[1];
            
            log("INFO", String.format("Request | %s %s from %s", method, path, socket.getRemoteSocketAddress()));

            // Parse headers into a map
            Map<String, String> headers = new ConcurrentHashMap<>();
            String line;
            int contentLength = 0;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                String[] headerParts = line.split(":", 2);
                if (headerParts.length == 2) {
                    headers.put(headerParts[0].trim().toLowerCase(), headerParts[1].trim());
                }
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }
            
            // Log ETag headers for debugging
            if (headers.containsKey("if-none-match")) {
                log("INFO", "Client sent If-None-Match: " + headers.get("if-none-match"));
            }
            if (headers.containsKey("if-match")) {
                log("INFO", "Client sent If-Match: " + headers.get("if-match"));
            }

            String body = "";
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int read = in.read(buf, 0, contentLength);
                if (read > 0) {
                    body = new String(buf, 0, read);
                }
            }

            // Get or create session for this request
            String sessionId = getOrCreateSession(headers);
            
            // Pass headers and session to route method
            byte[] response = route(method, path, body, headers, sessionId);
            out.write(response);
            out.flush();
            
            log("INFO", String.format("Response sent for %s %s (Session: %s)", method, path, sessionId));
        } catch (Exception e) {
            log("ERROR", "Error handling connection: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static byte[] route(String method, String path, String body, Map<String, String> headers, String sessionId) {

        if (!isAuthenticated(headers)) {
            log("WARNING", "Authentication failed for request: " + method + " " + path);
            return jsonResponseWithCookie(401, "Unauthorized", "{\"error\":\"Valid API key required\"}", sessionId);
        }

        if ("GET".equals(method)) {
            return handleGetRequest(path, body, headers, sessionId);
        }

        //POST REQUESTS
        if ("POST".equals(method) && "/memes".equals(path)) {
            try {
                int id = nextId.getAndIncrement();
                if (body == null || body.length() <= 1) {
                    return jsonResponseWithCookie(400, "Bad Request", "{\"error\":\"Invalid body\"}", sessionId);
                }
                String entry = "{\"id\":" + id + "," + body.substring(1);
                
                // Save to file
                String filePath = memesFolder + "/meme_" + id + ".json";
                Path fullPath = getResourcePath(filePath);
                Files.createDirectories(fullPath.getParent());
                Files.write(fullPath, entry.getBytes());
                
                memes.put(id, fullPath.toString());
                
                // Generate ETag using SHA-256
                String etag = generateEtagFromContent(entry);
                etags.put(id, etag);
                saveEtagsToDisk(); // Persist ETag to disk
                
                log("INFO", "Created new meme with ID: " + id + " ETag: " + etag);
                return jsonResponseWithETagAndCookie(201, "Created", entry, etag, sessionId);
            } catch (Exception e) {
                log("ERROR", "Error creating meme: " + e.getMessage());
                return jsonResponseWithCookie(500, "Internal Server Error", "{\"error\":\"Failed to create meme\"}", sessionId);
            }
        }

        //PUT REQUESTS
        if ("PUT".equals(method) && path.matches("/memes/[^/]+")) {
            try {
                int id = Integer.parseInt(path.split("/")[2]);
                if (memes.containsKey(id)) {
                    // Check If-Match header for optimistic locking
                    String ifMatch = headers.get("if-match");
                    String currentEtag = getEtagForResource(id);
                    
                    if (ifMatch != null && currentEtag != null && !ifMatch.equals(currentEtag)) {
                        log("WARNING", "ETag mismatch for PUT on ID " + id + ": client=" + ifMatch + ", server=" + currentEtag);
                        return jsonResponseWithCookie(412, "Precondition Failed", "{\"error\":\"Resource has been modified\"}", sessionId);
                    }
                    
                    return handlePutRequest(id, body, sessionId);
                } else {
                    return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"Meme not found\"}", sessionId);
                }
            } catch (NumberFormatException e) {
                return jsonResponseWithCookie(400, "Bad Request", "{\"error\":\"Invalid ID format\"}", sessionId);
            }
        }

        if ("DELETE".equals(method)) {
            return handleDeleteRequest(path, body, sessionId);
        }

        return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"Not found\"}", sessionId);
    }

    private static byte[] handleGetRequest(String path, String body, Map<String,String> headers, String sessionId) {
        if (path.equals("/") || path.isEmpty()) {
            return getStatichtml(sessionId);
        }

        if (path.equals("/resource")) {
            return getAllResources(sessionId);
        }

        if (path.matches("/resource/[^/]+")) {
            try {
                int id = Integer.parseInt(path.split("/")[2]);
                return getResourceById(id, headers, sessionId);
            } catch (NumberFormatException e) {
                return jsonResponseWithCookie(400, "Bad Request", "{\"error\":\"Invalid ID format\"}", sessionId);
            }
        }
        
        // Add endpoint to get session info (for testing)
        if (path.equals("/session-info")) {
            return getSessionInfo(sessionId);
        }
        
        // Add endpoint to test ETags
        if (path.equals("/test-etag")) {
            return testEtagResponse(sessionId);
        }

        if (path.startsWith("/Resources/") || path.startsWith("/Content/")) {
            return gethtmlResource(path, sessionId);
        }

        return jsonResponseWithCookie(400, "Bad Request", "{\"error\":\"Invalid request path\"}", sessionId);
    }
    
    private static byte[] testEtagResponse(String sessionId) {
        String testContent = "{\"message\":\"This is a test resource for ETag validation\",\"timestamp\":" + System.currentTimeMillis() + "}";
        String etag = generateEtagFromContent(testContent);
        return jsonResponseWithETagAndCookie(200, "OK", testContent, etag, sessionId);
    }
    
    private static byte[] getSessionInfo(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session != null) {
            String info = String.format("{\"sessionId\":\"%s\",\"userId\":\"%s\",\"createdAt\":%d,\"lastAccessedAt\":%d,\"isExpired\":%b}",
                session.sessionId, session.userId, session.createdAt, session.lastAccessedAt, session.isExpired());
            return jsonResponseWithCookie(200, "OK", info, sessionId);
        }
        return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"Session not found\"}", sessionId);
    }

    private static byte[] getResourceById(int id, Map<String, String> headers, String sessionId) {
        if (memes.containsKey(id)) {
            try {
                String memePath = memes.get(id);
                Path path = Paths.get(memePath);
                if (!Files.exists(path)) {
                    log("ERROR", "Meme file not found: " + memePath);
                    return jsonResponseWithCookie(404, "Resource file missing", "{\"error\":\"Resource not found\"}", sessionId);
                }
                
                String meme = new String(Files.readAllBytes(path));
                
                // Generate ETag from actual content
                String currentETag = generateEtagFromContent(meme);
                etags.put(id, currentETag);
                
                // Log ETag for debugging
                log("INFO", "Resource ID " + id + " has ETag: " + currentETag);
                
                // Check if client has matching ETag
                String clientETag = headers.get("if-none-match");
                if (clientETag != null) {
                    // Remove quotes if present
                    clientETag = clientETag.replaceAll("^\"|\"$", "");
                    log("INFO", "Comparing client ETag: " + clientETag + " with server ETag: " + currentETag);
                    
                    if (clientETag.equals(currentETag)) {
                        log("INFO", "Resource ID " + id + " not modified (ETag match) - returning 304");
                        return jsonResponseWithCookie(304, "Not Modified", true, sessionId);
                    } else {
                        log("INFO", "Resource ID " + id + " modified - returning new content");
                    }
                } else {
                    log("INFO", "No If-None-Match header provided - returning full content");
                }
                
                return jsonResponseWithETagAndCookie(200, "OK", meme, currentETag, sessionId);
            } catch (Exception e) {
                log("ERROR", "Error reading meme ID " + id + ": " + e.getMessage());
                return jsonResponseWithCookie(500, "Internal Server Error", "{\"error\":\"Failed to read resource\"}", sessionId);
            }
        }
        log("WARNING", "Resource ID not found: " + id);
        return jsonResponseWithCookie(404, "Not found", "{\"error\":\"Resource not found\"}", sessionId);
    }

    private static byte[] handlePutRequest(int id, String body, String sessionId) {
        if (body == null || body.length() <= 1) {
            return jsonResponseWithCookie(400, "Bad Request", "{\"error\":\"Empty body\"}", sessionId);
        }
        
        String entry = body.substring(1);
        
        if (entry.trim().isEmpty()) {
            return jsonResponseWithCookie(400, "Bad Request", "{\"error\":\"Empty body\"}", sessionId);
        }

        String memepath = memes.get(id);
        if (memepath == null) {
            return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"Resource not found\"}", sessionId);
        }

        try {
            Files.write(
                    Paths.get(memepath),
                    entry.getBytes(),
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            
            // Generate new ETag from updated content
            String newETag = generateEtagFromContent(entry);
            etags.put(id, newETag);
            saveEtagsToDisk(); // Persist updated ETag
            
            log("INFO", "Updated meme ID: " + id + " New ETag: " + newETag);
            return jsonResponseWithETagAndCookie(200, "OK", entry, newETag, sessionId);
        } catch (Exception e) {
            log("ERROR", "Error updating meme ID " + id + ": " + e.getMessage());
            return jsonResponseWithCookie(500, "Internal Server Error", "{\"error\":\"Failed to update resource\"}", sessionId);
        }
    }

    private static byte[] handleDeleteRequest(String path, String body, String sessionId) {
        if (path.matches("/resource/[^/]+")) {
            try {
                int id = Integer.parseInt(path.split("/")[2]);
                return deleteResourceById(id, sessionId);
            } catch (NumberFormatException e) {
                return jsonResponseWithCookie(400, "Bad Request", "{\"error\":\"Invalid ID format\"}", sessionId);
            }
        }
        return jsonResponseWithCookie(400, "Bad Request", "{\"error\":\"Invalid delete path\"}", sessionId);
    }

    private static byte[] getAllResources(String sessionId) {
        if (memes.isEmpty()) {
            return jsonResponseWithCookie(200, "OK", "[]", sessionId);
        }
        
        List<String> validMemes = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : memes.entrySet()) {
            try {
                String content = new String(Files.readAllBytes(Paths.get(entry.getValue())));
                validMemes.add(content);
            } catch (Exception e) {
                log("WARNING", "Could not read meme file for ID " + entry.getKey() + ": " + e.getMessage());
            }
        }
        
        String json = "[" + String.join(",", validMemes) + "]";
        return jsonResponseWithCookie(200, "OK", json, sessionId);
    }

    private static byte[] deleteResourceById(int id, String sessionId) {
        if (memes.containsKey(id)) {
            try {
                String memePath = memes.get(id);
                Path path = Paths.get(memePath);
                if (Files.exists(path)) {
                    Files.delete(path);
                }
                memes.remove(id);
                etags.remove(id);
                saveEtagsToDisk(); // Update ETags file
                log("INFO", "Deleted meme ID: " + id);
                return jsonResponseWithCookie(200, "OK", "{\"message\":\"Resource deleted successfully\"}", sessionId);
            } catch (Exception e) {
                log("ERROR", "Error deleting meme ID " + id + ": " + e.getMessage());
                return jsonResponseWithCookie(500, "Internal Server Error", "{\"error\":\"Failed to delete resource\"}", sessionId);
            }
        } else {
            log("WARNING", "Attempted to delete non-existent resource ID: " + id);
            return jsonResponseWithCookie(404, "Not found", "{\"error\":\"Resource not found\"}", sessionId);
        }
    }

    private static Path getResourcePath(String relativePath) {
        String[] basePaths = {
            "",
            "./",
            "src/HTTPALL/",
            "./src/HTTPALL/"
        };
        
        for (String basePath : basePaths) {
            Path fullPath = Paths.get(basePath + relativePath);
            if (Files.exists(fullPath.getParent()) || relativePath.equals(memesFolder + "/meme_1.json")) {
                return fullPath;
            }
        }
        
        return Paths.get(relativePath);
    }

    private static byte[] getStatichtml(String sessionId){
        try {
            String[] possiblePaths = {
                htmlEndPoint,
                "./" + htmlEndPoint,
                "src/HTTPALL/" + htmlEndPoint,
                "./src/HTTPALL/" + htmlEndPoint,
                "Content/index.html",
                "./Content/index.html"
            };
            
            Path fullPath = null;
            for (String path : possiblePaths) {
                Path testPath = Paths.get(path);
                if (Files.exists(testPath)) {
                    fullPath = testPath;
                    System.out.println("Found index.html at: " + fullPath.toAbsolutePath());
                    break;
                }
            }
            
            if (fullPath == null) {
                log("ERROR", "index.html not found in any expected location");
                return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"index.html not found\"}", sessionId);
            }
            
            byte[] fileBytes = Files.readAllBytes(fullPath);
            String contentType = getContentType(htmlEndPoint);
            return fileResponseWithCookie(200, "OK", fileBytes, contentType, sessionId);
        } catch (Exception e) {
            log("ERROR", "Error serving index.html: " + e.getMessage());
            return jsonResponseWithCookie(500, "Internal server error", "{\"error\":\"Failed to load page\"}", sessionId);
        }
    }

    private static byte[] gethtmlResource(String fileName, String sessionId){
        try {
            String cleanPath = fileName.startsWith("/") ? fileName.substring(1) : fileName;
            
            String[] possiblePaths = {
                cleanPath,
                "./" + cleanPath,
                "src/HTTPALL/" + cleanPath,
                "./src/HTTPALL/" + cleanPath,
                baseContentPath + "/" + cleanPath.replaceAll("^Resources/|^Content/", "")
            };
            
            Path fullPath = null;
            for (String path : possiblePaths) {
                Path testPath = Paths.get(path);
                if (Files.exists(testPath) && !Files.isDirectory(testPath)) {
                    fullPath = testPath;
                    break;
                }
            }
            
            if (fullPath != null && Files.exists(fullPath)) {
                byte[] fileBytes = Files.readAllBytes(fullPath);
                String contentType = getContentType(fileName);
                log("INFO", "Serving static file: " + fileName);
                return fileResponseWithCookie(200, "OK", fileBytes, contentType, sessionId);
            }
            
            log("WARNING", "Static resource not found: " + fileName);
            return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"Resource not found\"}", sessionId);
        } catch (Exception e) {
            log("ERROR", "Error serving static resource " + fileName + ": " + e.getMessage());
            return jsonResponseWithCookie(500, "Internal server error", "{\"error\":\"Failed to load resource\"}", sessionId);
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
        if (fileName.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private static byte[] buildResponse(int code, String reason, String contentType, byte[]... bodies) {
        int totalLength = 0;
        for (byte[] b : bodies) totalLength += b.length;

        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
            + "Content-Type: " + contentType + "\r\n"
            + "Content-Length: " + totalLength + "\r\n"
            + "Connection: close\r\n"
            + "\r\n";

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
        return jsonResponse(code, reason, "{}");
    }
    
    private static byte[] jsonResponse(int code, String reason, boolean noBody) {
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
            + "Connection: close\r\n"
            + "\r\n";
        return headers.getBytes();
    }

    private static byte[] fileResponse(int code, String reason, byte[] fileBytes, String contentType) {
        return buildResponse(code, reason, contentType, fileBytes);
    }

    private static byte[] jsonResponseWithCookie(int code, String reason, String json, String sessionId) {
        String cookieValue = sessionId;
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + json.getBytes().length + "\r\n"
                        + "Set-Cookie: session_id=" + cookieValue + "; Path=/; Max-Age=1800; HttpOnly\r\n"
                        + "Connection: close\r\n\r\n";
        
        byte[] headerBytes = headers.getBytes();
        byte[] jsonBytes = json.getBytes();
        byte[] combined = new byte[headerBytes.length + jsonBytes.length];
        
        System.arraycopy(headerBytes, 0, combined, 0, headerBytes.length);
        System.arraycopy(jsonBytes, 0, combined, headerBytes.length, jsonBytes.length);
        
        return combined;
    }
    
    private static byte[] jsonResponseWithCookie(int code, String reason, boolean noBody, String sessionId) {
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                        + "Set-Cookie: session_id=" + sessionId + "; Path=/; Max-Age=1800; HttpOnly\r\n"
                        + "Connection: close\r\n"
                        + "\r\n";
        return headers.getBytes();
    }
    
    private static byte[] jsonResponseWithETagAndCookie(int code, String reason, String json, String etag, String sessionId) {
        // Ensure ETag is quoted as per HTTP spec
        String quotedEtag = "\"" + etag + "\"";
        
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + json.getBytes().length + "\r\n"
                        + "ETag: " + quotedEtag + "\r\n"
                        + "Cache-Control: private, max-age=3600\r\n"
                        + "Set-Cookie: session_id=" + sessionId + "; Path=/; Max-Age=1800; HttpOnly\r\n"
                        + "Connection: close\r\n\r\n";
        
        byte[] headerBytes = headers.getBytes();
        byte[] jsonBytes = json.getBytes();
        byte[] combined = new byte[headerBytes.length + jsonBytes.length];
        
        System.arraycopy(headerBytes, 0, combined, 0, headerBytes.length);
        System.arraycopy(jsonBytes, 0, combined, headerBytes.length, jsonBytes.length);
        
        return combined;
    }
    
    private static byte[] fileResponseWithCookie(int code, String reason, byte[] fileBytes, String contentType, String sessionId) {
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                        + "Content-Type: " + contentType + "\r\n"
                        + "Content-Length: " + fileBytes.length + "\r\n"
                        + "Set-Cookie: session_id=" + sessionId + "; Path=/; Max-Age=1800; HttpOnly\r\n"
                        + "Connection: close\r\n\r\n";
        
        byte[] headerBytes = headers.getBytes();
        byte[] combined = new byte[headerBytes.length + fileBytes.length];
        
        System.arraycopy(headerBytes, 0, combined, 0, headerBytes.length);
        System.arraycopy(fileBytes, 0, combined, headerBytes.length, fileBytes.length);
        
        return combined;
    }
    
    // Comments feature (can be expanded later)
    private static Map<Integer, List<Comment>> comments = new ConcurrentHashMap<>();
    private static final AtomicInteger nextCommentId = new AtomicInteger(1);

    static class Comment {
        int id;
        String author;
        String text;
        String timestamp;
        
        Comment(int id, String author, String text) {
            this.id = id;
            this.author = author;
            this.text = text;
            this.timestamp = new java.util.Date().toString();
        }
        
        String toJson() {
            return String.format("{\"id\":%d,\"author\":\"%s\",\"text\":\"%s\",\"timestamp\":\"%s\"}", 
                id, author, text, timestamp);
        }
    }
}