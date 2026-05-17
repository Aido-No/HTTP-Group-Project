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

public class Server {

    private static final Logger logger = Logger.getLogger("HTTPServer");
    private static PrintWriter logWriter;
    private static String API_KEY = null;
    private static Map<Integer, String> memes = new ConcurrentHashMap<>();
    private static final AtomicInteger nextId = new AtomicInteger(1);
    
    private static Map<Integer, List<Comment>> comments = new ConcurrentHashMap<>();
    private static final AtomicInteger nextCommentId = new AtomicInteger(1);
    
    private static final String memesFolder = "http grupal proyect/src/HTTPALL/Content/Memes";
    private static final String htmlEndPoint = "http grupal proyect/src/HTTPALL/Content/index.html";
    private static final String baseContentPath = "http grupal proyect/src/HTTPALL/Content";
    
    private static Map<Integer, String> etags = new ConcurrentHashMap<>();
    private static Map<String, Session> sessions = new ConcurrentHashMap<>();
    
    // For persistent connections tracking
    private static Map<Socket, Long> lastActivityTime = new ConcurrentHashMap<>();
    private static final int KEEP_ALIVE_TIMEOUT = 5000; // 5 seconds
    
    // ============ Comment Class ============
    static class Comment {
        int id;
        String author;
        String text;
        String timestamp;
        
        Comment(int id, String author, String text) {
            this.id = id;
            this.author = author;
            this.text = text;
            this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        
        String toJson() {
            return String.format("{\"id\":%d,\"author\":\"%s\",\"text\":\"%s\",\"timestamp\":\"%s\"}", 
                id, escapeJson(author), escapeJson(text), timestamp);
        }
        
        private String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
        }
    }
    
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
            return (System.currentTimeMillis() - lastAccessedAt) > (30 * 60 * 1000);
        }
        
        void refresh() {
            this.lastAccessedAt = System.currentTimeMillis();
        }
    }
    
    public static void main(String[] args) throws Exception {
        System.out.println("Working Directory: " + System.getProperty("user.dir"));
        
        setUpHashMap();
        initLoging();
        loadApiKey();
        loadEtagsFromDisk();
        loadCommentsFromDisk();  // Load saved comments
        startSessionCleanup();
        
        int port = 3000;
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server listening on port " + port);
            System.out.println("HTTP/1.1 compliant with Keep-Alive and Chunked Transfer Encoding");
            System.out.println("=== ADVANCED CRUD: Comments on memes enabled ===");
            log("INFO", "Server started on port " + port);
            
            // Start keep-alive cleanup thread
            startKeepAliveCleanup();
            
            while (true) {
                Socket socket = serverSocket.accept();
                socket.setKeepAlive(true);
                socket.setSoTimeout(KEEP_ALIVE_TIMEOUT);
                new Thread(() -> handleConnectionWithKeepAlive(socket)).start();
            }
        }
    }
    
    // ============ Load/Save Comments to Disk ============
    private static void loadCommentsFromDisk() {
        try {
            Path commentsFile = Paths.get("comments.dat");
            if (Files.exists(commentsFile)) {
                List<String> lines = Files.readAllLines(commentsFile);
                for (String line : lines) {
                    String[] parts = line.split("\\|", 5);
                    if (parts.length == 5) {
                        int memeId = Integer.parseInt(parts[0]);
                        int commentId = Integer.parseInt(parts[1]);
                        String author = parts[2];
                        String text = parts[3];
                        String timestamp = parts[4];
                        
                        Comment comment = new Comment(commentId, author, text);
                        comment.timestamp = timestamp;
                        
                        comments.computeIfAbsent(memeId, k -> new ArrayList<>()).add(comment);
                        
                        if (commentId >= nextCommentId.get()) {
                            nextCommentId.set(commentId + 1);
                        }
                    }
                }
                System.out.println("Loaded comments from disk. Total comment threads: " + comments.size());
                log("INFO", "Loaded " + comments.size() + " comment threads from disk");
            }
        } catch (Exception e) {
            log("WARNING", "Could not load comments from disk: " + e.getMessage());
        }
    }
    
    private static void saveCommentsToDisk() {
        try {
            Path commentsFile = Paths.get("comments.dat");
            List<String> lines = new ArrayList<>();
            for (Map.Entry<Integer, List<Comment>> entry : comments.entrySet()) {
                int memeId = entry.getKey();
                for (Comment comment : entry.getValue()) {
                    lines.add(memeId + "|" + comment.id + "|" + comment.author + "|" + comment.text + "|" + comment.timestamp);
                }
            }
            Files.write(commentsFile, lines);
            log("INFO", "Saved " + comments.values().stream().mapToInt(List::size).sum() + " comments to disk");
        } catch (Exception e) {
            log("WARNING", "Could not save comments to disk: " + e.getMessage());
        }
    }
    
    private static void startKeepAliveCleanup() {
        Thread cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(30000); // Run every 30 seconds
                    long now = System.currentTimeMillis();
                    lastActivityTime.entrySet().removeIf(entry -> {
                        try {
                            if (now - entry.getValue() > KEEP_ALIVE_TIMEOUT) {
                                if (!entry.getKey().isClosed()) {
                                    entry.getKey().close();
                                    log("INFO", "Closed idle connection: " + entry.getKey().getRemoteSocketAddress());
                                }
                                return true;
                            }
                        } catch (Exception e) {
                            return true;
                        }
                        return false;
                    });
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }
    
    private static void handleConnectionWithKeepAlive(Socket socket) {
        log("INFO", "New connection from " + socket.getRemoteSocketAddress());
        lastActivityTime.put(socket, System.currentTimeMillis());
        
        try {
            boolean keepAlive = true;
            
            while (keepAlive) {
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    OutputStream out = socket.getOutputStream();
                    
                    // Set socket timeout for reading
                    socket.setSoTimeout(KEEP_ALIVE_TIMEOUT);
                    
                    String requestLine = in.readLine();
                    if (requestLine == null || requestLine.isEmpty()) {
                        break;
                    }
                    
                    lastActivityTime.put(socket, System.currentTimeMillis());
                    
                    String[] parts = requestLine.split(" ");
                    if (parts.length < 2) {
                        break;
                    }
                    
                    String method = parts[0];
                    String path = parts[1];
                    
                    log("INFO", String.format("Request | %s %s from %s", method, path, socket.getRemoteSocketAddress()));
                    
                    Map<String, String> headers = new ConcurrentHashMap<>();
                    String line;
                    int contentLength = 0;
                    boolean chunked = false;
                    boolean connectionKeepAlive = false;
                    
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        String[] headerParts = line.split(":", 2);
                        if (headerParts.length == 2) {
                            String key = headerParts[0].trim().toLowerCase();
                            String value = headerParts[1].trim();
                            headers.put(key, value);
                            
                            if (key.equals("content-length")) {
                                contentLength = Integer.parseInt(value);
                            }
                            if (key.equals("transfer-encoding") && value.equals("chunked")) {
                                chunked = true;
                            }
                            if (key.equals("connection")) {
                                connectionKeepAlive = value.equalsIgnoreCase("keep-alive");
                            }
                        }
                    }
                    
                    String body = "";
                    if (contentLength > 0) {
                        char[] buf = new char[contentLength];
                        int read = in.read(buf, 0, contentLength);
                        if (read > 0) {
                            body = new String(buf, 0, read);
                        }
                    } else if (chunked) {
                        body = readChunkedBody(in);
                    }
                    
                    String sessionId = getOrCreateSession(headers);
                    
                    byte[] response = route(method, path, body, headers, sessionId, connectionKeepAlive);
                    out.write(response);
                    out.flush();
                    
                    keepAlive = connectionKeepAlive;
                    
                    // Log response
                    log("INFO", String.format("Response sent for %s %s (Session: %s, Keep-Alive: %s)", 
                        method, path, sessionId, keepAlive));
                    
                } catch (java.net.SocketTimeoutException e) {
                    log("INFO", "Socket timeout - closing connection");
                    break;
                } catch (Exception e) {
                    log("WARNING", "Error in persistent connection: " + e.getMessage());
                    break;
                }
            }
        } catch (Exception e) {
            log("ERROR", "Error handling connection: " + e.getMessage());
        } finally {
            try {
                if (!socket.isClosed()) {
                    socket.close();
                    log("INFO", "Connection closed from " + socket.getRemoteSocketAddress());
                }
                lastActivityTime.remove(socket);
            } catch (Exception e) {
                // Ignore
            }
        }
    }
    
    private static String readChunkedBody(BufferedReader in) throws Exception {
        StringBuilder body = new StringBuilder();
        while (true) {
            String chunkSizeLine = in.readLine();
            if (chunkSizeLine == null) break;
            
            int chunkSize = Integer.parseInt(chunkSizeLine.trim(), 16);
            if (chunkSize == 0) {
                // Read trailing headers
                while (true) {
                    String trailer = in.readLine();
                    if (trailer == null || trailer.isEmpty()) break;
                }
                break;
            }
            
            char[] chunk = new char[chunkSize];
            int read = in.read(chunk, 0, chunkSize);
            body.append(chunk, 0, read);
            in.readLine(); // Read the CRLF after chunk
        }
        return body.toString();
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
            return hexString.toString().substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(content.hashCode());
        }
    }
    
    private static String getEtagForResource(int id) {
        if (etags.containsKey(id)) {
            return etags.get(id);
        }
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
                    Thread.sleep(5 * 60 * 1000);
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
        if ("ERROR".equals(level) || "WARNING".equals(level)) {
            System.err.println(logEntry);
        }
    }
    
    private static void setUpHashMap() {
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
        nextId.set(id);
        System.out.println("Total memes loaded: " + memes.size());
    }
    
    private static byte[] route(String method, String path, String body, Map<String, String> headers, String sessionId, boolean keepAlive) {
        if (!isAuthenticated(headers)) {
            log("WARNING", "Authentication failed for request: " + method + " " + path);
            return jsonResponse(401, "Unauthorized", "{\"error\":\"Valid API key required\"}");
        }
        
        // ============ ADVANCED CRUD: Comment Endpoints ============
        
        // GET /resource/{id}/comments - Get all comments for a meme
        if ("GET".equals(method) && path.matches("/resource/\\d+/comments")) {
            String[] parts = path.split("/");
            int memeId = Integer.parseInt(parts[2]);
            return getCommentsForMeme(memeId, sessionId, keepAlive);
        }
        
        // POST /resource/{id}/comments - Add a comment to a meme
        if ("POST".equals(method) && path.matches("/resource/\\d+/comments")) {
            String[] parts = path.split("/");
            int memeId = Integer.parseInt(parts[2]);
            return addCommentToMeme(memeId, body, sessionId, keepAlive);
        }
        
        // DELETE /resource/{id}/comments/{commentId} - Delete a comment
        if ("DELETE".equals(method) && path.matches("/resource/\\d+/comments/\\d+")) {
            String[] parts = path.split("/");
            int memeId = Integer.parseInt(parts[2]);
            int commentId = Integer.parseInt(parts[4]);
            return deleteCommentFromMeme(memeId, commentId, sessionId, keepAlive);
        }
        
        // PUT /resource/{id}/comments/{commentId} - Update a comment
        if ("PUT".equals(method) && path.matches("/resource/\\d+/comments/\\d+")) {
            String[] parts = path.split("/");
            int memeId = Integer.parseInt(parts[2]);
            int commentId = Integer.parseInt(parts[4]);
            return updateCommentOnMeme(memeId, commentId, body, sessionId, keepAlive);
        }
        
        // GET /resource/{id}/comments/count - Get comment count for a meme
        if ("GET".equals(method) && path.matches("/resource/\\d+/comments/count")) {
            String[] parts = path.split("/");
            int memeId = Integer.parseInt(parts[2]);
            return getCommentCountForMeme(memeId, sessionId, keepAlive);
        }
        
        // ============ Existing endpoints ============
        
        if ("GET".equals(method)) {
            return handleGetRequest(path, body, headers, sessionId, keepAlive);
        }
        
        if ("POST".equals(method) && "/memes".equals(path)) {
            return handlePostRequest(body, sessionId, keepAlive);
        }
        
        if ("PUT".equals(method) && path.matches("/memes/[^/]+")) {
            try {
                int id = Integer.parseInt(path.split("/")[2]);
                if (memes.containsKey(id)) {
                    String ifMatch = headers.get("if-match");
                    String currentEtag = getEtagForResource(id);
                    
                    if (ifMatch != null && currentEtag != null && !ifMatch.equals(currentEtag)) {
                        return jsonResponseWithCookie(412, "Precondition Failed", "{\"error\":\"Resource has been modified\"}", sessionId, keepAlive);
                    }
                    return handlePutRequest(id, body, sessionId, keepAlive);
                } else {
                    return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"Meme not found\"}", sessionId, keepAlive);
                }
            } catch (NumberFormatException e) {
                return jsonResponseWithCookie(400, "Bad Request", "{\"error\":\"Invalid ID format\"}", sessionId, keepAlive);
            }
        }
        
        if ("DELETE".equals(method)) {
            return handleDeleteRequest(path, body, sessionId, keepAlive);
        }
        
        return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"Not found\"}", sessionId, keepAlive);
    }
    
    // ============ ADVANCED CRUD: Comment Handler Methods ============
    
    private static byte[] getCommentsForMeme(int memeId, String sessionId, boolean keepAlive) {
        // Check if meme exists
        if (!memes.containsKey(memeId)) {
            return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"Meme not found\"}", sessionId, keepAlive);
        }
        
        List<Comment> memeComments = comments.getOrDefault(memeId, new ArrayList<>());
        
        // Build JSON array of comments
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < memeComments.size(); i++) {
            if (i > 0) json.append(",");
            json.append(memeComments.get(i).toJson());
        }
        json.append("]");
        
        log("INFO", "Returned " + memeComments.size() + " comments for meme ID " + memeId);
        return jsonResponseWithCookie(200, "OK", json.toString(), sessionId, keepAlive);
    }
    
    private static byte[] addCommentToMeme(int memeId, String body, String sessionId, boolean keepAlive) {
        // Check if meme exists
        if (!memes.containsKey(memeId)) {
            return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"Meme not found\"}", sessionId, keepAlive);
        }
        
        // Parse JSON body
        String author = "";
        String text = "";
        
        try {
            // Simple JSON parsing
            if (body.contains("\"author\"")) {
                int authorStart = body.indexOf("\"author\"") + 9;
                int authorEnd = body.indexOf(",", authorStart);
                if (authorEnd == -1) authorEnd = body.indexOf("}", authorStart);
                author = body.substring(authorStart, authorEnd);
                author = author.replaceAll("\"", "").trim();
                // Remove colon if present
                if (author.startsWith(":")) author = author.substring(1).trim();
            }
            
            if (body.contains("\"text\"")) {
                int textStart = body.indexOf("\"text\"") + 7;
                int textEnd = body.indexOf("}", textStart);
                if (textEnd == -1) textEnd = body.length();
                text = body.substring(textStart, textEnd);
                text = text.replaceAll("\"", "").trim();
                // Remove colon if present
                if (text.startsWith(":")) text = text.substring(1).trim();
            }
            
            if (author.isEmpty() || text.isEmpty()) {
                return jsonResponseWithCookie(400, "Bad Request", 
                    "{\"error\":\"Missing author or text. Expected: {\\\"author\\\":\\\"name\\\",\\\"text\\\":\\\"comment\\\"}\"}", 
                    sessionId, keepAlive);
            }
            
            // Create comment
            int commentId = nextCommentId.getAndIncrement();
            Comment comment = new Comment(commentId, author, text);
            
            // Add to comments map
            comments.computeIfAbsent(memeId, k -> new ArrayList<>()).add(comment);
            saveCommentsToDisk();
            
            log("INFO", "Added comment ID " + commentId + " to meme ID " + memeId);
            return jsonResponseWithCookie(201, "Created", comment.toJson(), sessionId, keepAlive);
            
        } catch (Exception e) {
            log("ERROR", "Error parsing comment: " + e.getMessage());
            return jsonResponseWithCookie(400, "Bad Request", 
                "{\"error\":\"Invalid JSON format. Expected: {\\\"author\\\":\\\"name\\\",\\\"text\\\":\\\"comment\\\"}\"}", 
                sessionId, keepAlive);
        }
    }
    
    private static byte[] deleteCommentFromMeme(int memeId, int commentId, String sessionId, boolean keepAlive) {
        // Check if meme exists
        if (!memes.containsKey(memeId)) {
            return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"Meme not found\"}", sessionId, keepAlive);
        }
        
        List<Comment> memeComments = comments.get(memeId);
        if (memeComments == null) {
            return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"Comment not found\"}", sessionId, keepAlive);
        }
        
        // Find and remove comment
        boolean removed = memeComments.removeIf(comment -> comment.id == commentId);
        
        if (removed) {
            saveCommentsToDisk();
            log("INFO", "Deleted comment ID " + commentId + " from meme ID " + memeId);
            return jsonResponseWithCookie(200, "OK", "{\"message\":\"Comment deleted successfully\"}", sessionId, keepAlive);
        } else {
            return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"Comment not found\"}", sessionId, keepAlive);
        }
    }
    
    private static byte[] updateCommentOnMeme(int memeId, int commentId, String body, String sessionId, boolean keepAlive) {
        // Check if meme exists
        if (!memes.containsKey(memeId)) {
            return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"Meme not found\"}", sessionId, keepAlive);
        }
        
        List<Comment> memeComments = comments.get(memeId);
        if (memeComments == null) {
            return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"Comment not found\"}", sessionId, keepAlive);
        }
        
        // Parse new text
        String newText = "";
        try {
            if (body.contains("\"text\"")) {
                int textStart = body.indexOf("\"text\"") + 7;
                int textEnd = body.indexOf("}", textStart);
                if (textEnd == -1) textEnd = body.length();
                newText = body.substring(textStart, textEnd);
                newText = newText.replaceAll("\"", "").trim();
                if (newText.startsWith(":")) newText = newText.substring(1).trim();
            }
            
            if (newText.isEmpty()) {
                return jsonResponseWithCookie(400, "Bad Request", 
                    "{\"error\":\"Missing text field. Expected: {\\\"text\\\":\\\"new comment text\\\"}\"}", 
                    sessionId, keepAlive);
            }
            
            // Find and update comment
            for (Comment comment : memeComments) {
                if (comment.id == commentId) {
                    comment.text = newText;
                    comment.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    saveCommentsToDisk();
                    log("INFO", "Updated comment ID " + commentId + " on meme ID " + memeId);
                    return jsonResponseWithCookie(200, "OK", comment.toJson(), sessionId, keepAlive);
                }
            }
            
            return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"Comment not found\"}", sessionId, keepAlive);
            
        } catch (Exception e) {
            log("ERROR", "Error updating comment: " + e.getMessage());
            return jsonResponseWithCookie(400, "Bad Request", 
                "{\"error\":\"Invalid JSON format. Expected: {\\\"text\\\":\\\"new comment text\\\"}\"}", 
                sessionId, keepAlive);
        }
    }
    
    private static byte[] getCommentCountForMeme(int memeId, String sessionId, boolean keepAlive) {
        // Check if meme exists
        if (!memes.containsKey(memeId)) {
            return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"Meme not found\"}", sessionId, keepAlive);
        }
        
        int count = comments.getOrDefault(memeId, new ArrayList<>()).size();
        return jsonResponseWithCookie(200, "OK", "{\"memeId\":" + memeId + ",\"commentCount\":" + count + "}", sessionId, keepAlive);
    }
    
    // ============ Existing Handler Methods ============
    
    private static byte[] handleGetRequest(String path, String body, Map<String,String> headers, String sessionId, boolean keepAlive) {
        if (path.equals("/") || path.isEmpty()) {
            return getStaticHtml(sessionId, keepAlive);
        }
        
        if (path.equals("/resource")) {
            return getAllResources(sessionId, keepAlive);
        }
        
        if (path.matches("/resource/[^/]+")) {
            try {
                int id = Integer.parseInt(path.split("/")[2]);
                return getResourceById(id, headers, sessionId, keepAlive);
            } catch (NumberFormatException e) {
                return jsonResponseWithCookie(400, "Bad Request", "{\"error\":\"Invalid ID format\"}", sessionId, keepAlive);
            }
        }
        
        if (path.equals("/session-info")) {
            return getSessionInfo(sessionId, keepAlive);
        }
        
        if (path.equals("/test-etag")) {
            return testEtagResponse(sessionId, keepAlive);
        }
        
        if (path.startsWith("/Resources/") || path.startsWith("/Content/")) {
            return getHtmlResource(path, sessionId, keepAlive);
        }
        
        return jsonResponseWithCookie(400, "Bad Request", "{\"error\":\"Invalid request path\"}", sessionId, keepAlive);
    }
    
    // The restored handlePostRequest function
    private static byte[] handlePostRequest(String body, String sessionId, boolean keepAlive) {
        if (body == null || body.length() <= 1) {
            return jsonResponseWithCookie(400, "Bad Request", "{\"error\":\"Invalid body\"}", sessionId, keepAlive);
        }
        
        int id = nextId.getAndIncrement();
        String entry = "{\"id\":" + id + "," + body.substring(1);
        
        String filePath = memesFolder + "/meme_" + id + ".json";
        try {
            Path fullPath = getResourcePath(filePath);
            Files.createDirectories(fullPath.getParent());
            Files.write(fullPath, entry.getBytes());
            
            memes.put(id, fullPath.toString());
            String etag = generateEtagFromContent(entry);
            etags.put(id, etag);
            saveEtagsToDisk();
            
            log("INFO", "Created new meme with ID: " + id + " ETag: " + etag);
            return jsonResponseWithETagAndCookie(201, "Created", entry, etag, sessionId, keepAlive);
        } catch (Exception e) {
            log("ERROR", "Error creating meme: " + e.getMessage());
            return jsonResponseWithCookie(500, "Internal Server Error", "{\"error\":\"Failed to create meme\"}", sessionId, keepAlive);
        }
    }
    
    private static byte[] testEtagResponse(String sessionId, boolean keepAlive) {
        String testContent = "{\"message\":\"This is a test resource for ETag validation\",\"timestamp\":" + System.currentTimeMillis() + "}";
        String etag = generateEtagFromContent(testContent);
        return jsonResponseWithETagAndCookie(200, "OK", testContent, etag, sessionId, keepAlive);
    }
    
    private static byte[] getSessionInfo(String sessionId, boolean keepAlive) {
        Session session = sessions.get(sessionId);
        if (session != null) {
            String info = String.format("{\"sessionId\":\"%s\",\"userId\":\"%s\",\"createdAt\":%d,\"lastAccessedAt\":%d,\"isExpired\":%b}",
                session.sessionId, session.userId, session.createdAt, session.lastAccessedAt, session.isExpired());
            return jsonResponseWithCookie(200, "OK", info, sessionId, keepAlive);
        }
        return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"Session not found\"}", sessionId, keepAlive);
    }
    
    private static byte[] getResourceById(int id, Map<String, String> headers, String sessionId, boolean keepAlive) {
        if (memes.containsKey(id)) {
            try {
                String memePath = memes.get(id);
                Path path = Paths.get(memePath);
                if (!Files.exists(path)) {
                    return jsonResponseWithCookie(404, "Resource file missing", "{\"error\":\"Resource not found\"}", sessionId, keepAlive);
                }
                
                String meme = new String(Files.readAllBytes(path));
                String currentETag = generateEtagFromContent(meme);
                etags.put(id, currentETag);
                
                String clientETag = headers.get("if-none-match");
                if (clientETag != null) {
                    clientETag = clientETag.replaceAll("^\"|\"$", "");
                    if (clientETag.equals(currentETag)) {
                        return jsonResponseWithCookie(304, "Not Modified", true, sessionId, keepAlive);
                    }
                }
                
                return jsonResponseWithETagAndCookie(200, "OK", meme, currentETag, sessionId, keepAlive);
            } catch (Exception e) {
                log("ERROR", "Error reading meme ID " + id + ": " + e.getMessage());
                return jsonResponseWithCookie(500, "Internal Server Error", "{\"error\":\"Failed to read resource\"}", sessionId, keepAlive);
            }
        }
        return jsonResponseWithCookie(404, "Not found", "{\"error\":\"Resource not found\"}", sessionId, keepAlive);
    }
    
    private static byte[] handlePutRequest(int id, String body, String sessionId, boolean keepAlive) {
        if (body == null || body.length() <= 1) {
            return jsonResponseWithCookie(400, "Bad Request", "{\"error\":\"Empty body\"}", sessionId, keepAlive);
        }
        
        String entry = body;
        if (entry.trim().isEmpty()) {
            return jsonResponseWithCookie(400, "Bad Request", "{\"error\":\"Empty body\"}", sessionId, keepAlive);
        }
        
        String memepath = memes.get(id);
        if (memepath == null) {
            return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"Resource not found\"}", sessionId, keepAlive);
        }
        
        try {
            Files.write(Paths.get(memepath), entry.getBytes(), StandardOpenOption.TRUNCATE_EXISTING);
            String newETag = generateEtagFromContent(entry);
            etags.put(id, newETag);
            saveEtagsToDisk();
            return jsonResponseWithETagAndCookie(200, "OK", entry, newETag, sessionId, keepAlive);
        } catch (Exception e) {
            log("ERROR", "Error updating meme ID " + id + ": " + e.getMessage());
            return jsonResponseWithCookie(500, "Internal Server Error", "{\"error\":\"Failed to update resource\"}", sessionId, keepAlive);
        }
    }
    
    private static byte[] handleDeleteRequest(String path, String body, String sessionId, boolean keepAlive) {
        if (path.matches("/resource/[^/]+")) {
            try {
                int id = Integer.parseInt(path.split("/")[2]);
                // Also delete all comments for this meme
                comments.remove(id);
                saveCommentsToDisk();
                return deleteResourceById(id, sessionId, keepAlive);
            } catch (NumberFormatException e) {
                return jsonResponseWithCookie(400, "Bad Request", "{\"error\":\"Invalid ID format\"}", sessionId, keepAlive);
            }
        }
        return jsonResponseWithCookie(400, "Bad Request", "{\"error\":\"Invalid delete path\"}", sessionId, keepAlive);
    }
    
    private static byte[] getAllResources(String sessionId, boolean keepAlive) {
        if (memes.isEmpty()) {
            return jsonResponseWithCookie(200, "OK", "[]", sessionId, keepAlive);
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
        return jsonResponseWithCookie(200, "OK", json, sessionId, keepAlive);
    }
    
    private static byte[] deleteResourceById(int id, String sessionId, boolean keepAlive) {
        if (memes.containsKey(id)) {
            try {
                String memePath = memes.get(id);
                Path path = Paths.get(memePath);
                if (Files.exists(path)) {
                    Files.delete(path);
                }
                memes.remove(id);
                etags.remove(id);
                saveEtagsToDisk();
                return jsonResponseWithCookie(200, "OK", "{\"message\":\"Resource deleted successfully\"}", sessionId, keepAlive);
            } catch (Exception e) {
                return jsonResponseWithCookie(500, "Internal Server Error", "{\"error\":\"Failed to delete resource\"}", sessionId, keepAlive);
            }
        } else {
            return jsonResponseWithCookie(404, "Not found", "{\"error\":\"Resource not found\"}", sessionId, keepAlive);
        }
    }
    
    private static Path getResourcePath(String relativePath) {
        String[] basePaths = {"", "./", "src/HTTPALL/", "./src/HTTPALL/"};
        for (String basePath : basePaths) {
            Path fullPath = Paths.get(basePath + relativePath);
            if (Files.exists(fullPath.getParent()) || relativePath.equals(memesFolder + "/meme_1.json")) {
                return fullPath;
            }
        }
        return Paths.get(relativePath);
    }
    
    private static byte[] getStaticHtml(String sessionId, boolean keepAlive) {
        try {
            String[] possiblePaths = {
                htmlEndPoint, "./" + htmlEndPoint, "src/HTTPALL/" + htmlEndPoint,
                "./src/HTTPALL/" + htmlEndPoint, "Content/index.html", "./Content/index.html"
            };
            
            Path fullPath = null;
            for (String path : possiblePaths) {
                Path testPath = Paths.get(path);
                if (Files.exists(testPath)) {
                    fullPath = testPath;
                    break;
                }
            }
            
            if (fullPath == null) {
                return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"index.html not found\"}", sessionId, keepAlive);
            }
            
            byte[] fileBytes = Files.readAllBytes(fullPath);
            String contentType = getContentType(htmlEndPoint);
            return fileResponseWithCookie(200, "OK", fileBytes, contentType, sessionId, keepAlive);
        } catch (Exception e) {
            return jsonResponseWithCookie(500, "Internal server error", "{\"error\":\"Failed to load page\"}", sessionId, keepAlive);
        }
    }
    
    private static byte[] getHtmlResource(String fileName, String sessionId, boolean keepAlive) {
        try {
            String cleanPath = fileName.startsWith("/") ? fileName.substring(1) : fileName;
            String[] possiblePaths = {
                cleanPath, "./" + cleanPath, "src/HTTPALL/" + cleanPath,
                "./src/HTTPALL/" + cleanPath, baseContentPath + "/" + cleanPath.replaceAll("^Resources/|^Content/", "")
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
                return fileResponseWithCookie(200, "OK", fileBytes, contentType, sessionId, keepAlive);
            }
            
            return jsonResponseWithCookie(404, "Not Found", "{\"error\":\"Resource not found\"}", sessionId, keepAlive);
        } catch (Exception e) {
            return jsonResponseWithCookie(500, "Internal server error", "{\"error\":\"Failed to load resource\"}", sessionId, keepAlive);
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
    
    /*private static byte[] buildResponse(int code, String reason, String contentType, byte[] body, boolean keepAlive) {
        String connectionHeader = keepAlive ? "keep-alive" : "close";
        String keepAliveHeader = keepAlive ? "Keep-Alive: timeout=" + (KEEP_ALIVE_TIMEOUT / 1000) + "\r\n" : "";
        
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
            + "Content-Type: " + contentType + "\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Connection: " + connectionHeader + "\r\n"
            + keepAliveHeader
            + "\r\n";
        
        byte[] headerBytes = headers.getBytes();
        byte[] combined = new byte[headerBytes.length + body.length];
        System.arraycopy(headerBytes, 0, combined, 0, headerBytes.length);
        System.arraycopy(body, 0, combined, headerBytes.length, body.length);
        return combined;
    }*/
    
    // Response helper methods (renamed without "AndKeepAlive")
    private static byte[] jsonResponseWithCookie(int code, String reason, String json, String sessionId, boolean keepAlive) {
        String connectionHeader = keepAlive ? "keep-alive" : "close";
        String keepAliveHeader = keepAlive ? "Keep-Alive: timeout=" + (KEEP_ALIVE_TIMEOUT / 1000) + "\r\n" : "";
        
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + json.getBytes().length + "\r\n"
                        + "Set-Cookie: session_id=" + sessionId + "; Path=/; Max-Age=1800; HttpOnly\r\n"
                        + "Connection: " + connectionHeader + "\r\n"
                        + keepAliveHeader
                        + "\r\n";
        
        byte[] headerBytes = headers.getBytes();
        byte[] jsonBytes = json.getBytes();
        byte[] combined = new byte[headerBytes.length + jsonBytes.length];
        System.arraycopy(headerBytes, 0, combined, 0, headerBytes.length);
        System.arraycopy(jsonBytes, 0, combined, headerBytes.length, jsonBytes.length);
        return combined;
    }
    
    // Overloaded version for responses with no body (like 304)
    private static byte[] jsonResponseWithCookie(int code, String reason, boolean noBody, String sessionId, boolean keepAlive) {
        String connectionHeader = keepAlive ? "keep-alive" : "close";
        String keepAliveHeader = keepAlive ? "Keep-Alive: timeout=" + (KEEP_ALIVE_TIMEOUT / 1000) + "\r\n" : "";
        
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                        + "Set-Cookie: session_id=" + sessionId + "; Path=/; Max-Age=1800; HttpOnly\r\n"
                        + "Connection: " + connectionHeader + "\r\n"
                        + keepAliveHeader
                        + "\r\n";
        return headers.getBytes();
    }
    
    private static byte[] jsonResponseWithETagAndCookie(int code, String reason, String json, String etag, String sessionId, boolean keepAlive) {
        String quotedEtag = "\"" + etag + "\"";
        String connectionHeader = keepAlive ? "keep-alive" : "close";
        String keepAliveHeader = keepAlive ? "Keep-Alive: timeout=" + (KEEP_ALIVE_TIMEOUT / 1000) + "\r\n" : "";
        
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + json.getBytes().length + "\r\n"
                        + "ETag: " + quotedEtag + "\r\n"
                        + "Cache-Control: private, max-age=3600\r\n"
                        + "Set-Cookie: session_id=" + sessionId + "; Path=/; Max-Age=1800; HttpOnly\r\n"
                        + "Connection: " + connectionHeader + "\r\n"
                        + keepAliveHeader
                        + "\r\n";
        
        byte[] headerBytes = headers.getBytes();
        byte[] jsonBytes = json.getBytes();
        byte[] combined = new byte[headerBytes.length + jsonBytes.length];
        System.arraycopy(headerBytes, 0, combined, 0, headerBytes.length);
        System.arraycopy(jsonBytes, 0, combined, headerBytes.length, jsonBytes.length);
        return combined;
    }
    
    private static byte[] fileResponseWithCookie(int code, String reason, byte[] fileBytes, String contentType, String sessionId, boolean keepAlive) {
        String connectionHeader = keepAlive ? "keep-alive" : "close";
        String keepAliveHeader = keepAlive ? "Keep-Alive: timeout=" + (KEEP_ALIVE_TIMEOUT / 1000) + "\r\n" : "";
        
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                        + "Content-Type: " + contentType + "\r\n"
                        + "Content-Length: " + fileBytes.length + "\r\n"
                        + "Set-Cookie: session_id=" + sessionId + "; Path=/; Max-Age=1800; HttpOnly\r\n"
                        + "Connection: " + connectionHeader + "\r\n"
                        + keepAliveHeader
                        + "\r\n";
        
        byte[] headerBytes = headers.getBytes();
        byte[] combined = new byte[headerBytes.length + fileBytes.length];
        System.arraycopy(headerBytes, 0, combined, 0, headerBytes.length);
        System.arraycopy(fileBytes, 0, combined, headerBytes.length, fileBytes.length);
        return combined;
    }
    
    // Original jsonResponse without cookies (for authentication errors)
    private static byte[] jsonResponse(int code, String reason, String json) {
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + json.getBytes().length + "\r\n"
                        + "Connection: close\r\n"
                        + "\r\n";
        
        byte[] headerBytes = headers.getBytes();
        byte[] jsonBytes = json.getBytes();
        byte[] combined = new byte[headerBytes.length + jsonBytes.length];
        System.arraycopy(headerBytes, 0, combined, 0, headerBytes.length);
        System.arraycopy(jsonBytes, 0, combined, headerBytes.length, jsonBytes.length);
        return combined;
    }
    
    /*private static byte[] jsonResponse(int code, String reason) {
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                        + "Connection: close\r\n"
                        + "\r\n";
        return headers.getBytes();
    }*/
}