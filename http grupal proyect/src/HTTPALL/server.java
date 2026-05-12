package HTTPALL;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.ArrayList;

public class Server {

    private static Map<Integer, String> memes = new ConcurrentHashMap<>();
    private static final AtomicInteger nextId = new AtomicInteger(1);
    private static final String memesFolder = "http grupal proyect/src/HTTPALL/Content/Memes";
    private static final String htmlEndPoint = "http grupal proyect/src/HTTPALL/Content/index.html";
    public static void main(String[] args) throws Exception {
        setUpHashMap();
        
        int port = 3000;
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server listening on port " + port);
            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(() -> handleConnection(socket)).start();
            }
        }
    }

    private static void setUpHashMap(){
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
    }

    private static void handleConnection(Socket socket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             OutputStream out = socket.getOutputStream()) {

            String requestLine = in.readLine();
            if (requestLine == null) return;

            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String path = parts[1];

            StringBuilder headers = new StringBuilder();
            String line;
            int contentLength = 0;
            while (!(line = in.readLine()).isEmpty()) {
                headers.append(line).append("\r\n");
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }

            String body = "";
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                in.read(buf, 0, contentLength);
                body = new String(buf);
            }

            byte[] response = route(method, path, body);
            out.write(response);
            out.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static byte[] route(String method, String path, String body) {
    
    // ============ STATIC FILES ============
    if ("GET".equals(method) && (path.equals("") || path.equals("/"))) {
        return getStatichtml();
    }
    
    // ============ COMMENTS ENDPOINTS (Advanced CRUD) ============
    // GET /resource/{id}/comments
    if ("GET".equals(method) && path.matches("/resource/\\d+/comments")) {
        String[] parts = path.split("/");
        int memeId = Integer.parseInt(parts[2]);
        return getCommentsForMeme(memeId);
    }
    
    // POST /resource/{id}/comments
    if ("POST".equals(method) && path.matches("/resource/\\d+/comments")) {
        String[] parts = path.split("/");
        int memeId = Integer.parseInt(parts[2]);
        return addCommentToMeme(memeId, body);
    }
    
    // DELETE /resource/{id}/comments/{commentId}
    if ("DELETE".equals(method) && path.matches("/resource/\\d+/comments/\\d+")) {
        String[] parts = path.split("/");
        int memeId = Integer.parseInt(parts[2]);
        int commentId = Integer.parseInt(parts[4]);
        return deleteComment(memeId, commentId);
    }
    
    // ============ RESOURCE ENDPOINTS (Basic CRUD) ============
    // GET /resource - get all memes
    if ("GET".equals(method) && "/resource".equals(path)) {
        return getAllResources();
    }
    
    // GET /resource/{id} - get specific meme
    if ("GET".equals(method) && path.matches("/resource/\\d+")) {
        String[] parts = path.split("/");
        int id = Integer.parseInt(parts[2]);
        return getResourceById(id);
    }
    
    // POST /resource - create meme
    if ("POST".equals(method) && "/resource".equals(path)) {
        return createResource(body);
    }
    
    // PUT /resource/{id} - update meme
    if ("PUT".equals(method) && path.matches("/resource/\\d+")) {
        String[] parts = path.split("/");
        int id = Integer.parseInt(parts[2]);
        return updateResource(id, body);
    }
    
    // DELETE /resource/{id} - delete meme
    if ("DELETE".equals(method) && path.matches("/resource/\\d+")) {
        String[] parts = path.split("/");
        int id = Integer.parseInt(parts[2]);
        return deleteResource(id);
    }
    
    // ============ SERVING STATIC FILES ============
    if ("GET".equals(method) && path.startsWith("/Resources/")) {
        return gethtmlResource(path);
    }
    
    return jsonResponse(404, "Not Found", "{\"error\":\"Endpoint not found\"}");
}

    private static byte[] handleDeleteRequest(String path, String body) {
            if (path.matches("/resource/[^/]+")) {
                int id = Integer.parseInt(path.split("/")[2]);

                return deleteResourceById(id);
            }
            return jsonResponse(400, "Bad Request");

    }
    
 // Get all comments for a specific meme
    private static byte[] getCommentsForMeme(int memeId) {
        // Check if meme exists first
        if (!memes.containsKey(memeId)) {
            return jsonResponse(404, "Not Found", "{\"error\":\"Meme not found\"}");
        }
        
        List<Comment> memeComments = comments.getOrDefault(memeId, new ArrayList<>());
        
        // Build JSON array of comments
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < memeComments.size(); i++) {
            if (i > 0) json.append(",");
            json.append(memeComments.get(i).toJson());
        }
        json.append("]");
        
        return jsonResponse(200, "OK", json.toString());
    }

    // Add a comment to a meme
    private static byte[] addCommentToMeme(int memeId, String body) {
        // Check if meme exists
        if (!memes.containsKey(memeId)) {
            return jsonResponse(404, "Not Found", "{\"error\":\"Meme not found\"}");
        }
        
        // Parse JSON body to get author and text
        // Expected format: {"author":"John","text":"This is funny!"}
        String author = "";
        String text = "";
        
        try {
            // Simple parsing (you can use a JSON library like org.json for better parsing)
            if (body.contains("\"author\"")) {
                int authorStart = body.indexOf("\"author\"") + 9;
                author = body.substring(authorStart, body.indexOf(",", authorStart));
                author = author.replaceAll("\"", "").trim();
            }
            if (body.contains("\"text\"")) {
                int textStart = body.indexOf("\"text\"") + 7;
                int textEnd = body.indexOf("}", textStart);
                text = body.substring(textStart, textEnd);
                text = text.replaceAll("\"", "").trim();
            }
            
            if (author.isEmpty() || text.isEmpty()) {
                return jsonResponse(400, "Bad Request", "{\"error\":\"Missing author or text\"}");
            }
            
            // Create comment
            int commentId = nextCommentId.getAndIncrement();
            Comment comment = new Comment(commentId, author, text);
            
            // Add to comments map
            comments.computeIfAbsent(memeId, k -> new ArrayList<>()).add(comment);
            
            return jsonResponse(201, "Created", comment.toJson());
            
        } catch (Exception e) {
            return jsonResponse(400, "Bad Request", "{\"error\":\"Invalid JSON format\"}");
        }
    }

    // Delete a comment
    private static byte[] deleteComment(int memeId, int commentId) {
        // Check if meme exists
        if (!memes.containsKey(memeId)) {
            return jsonResponse(404, "Not Found", "{\"error\":\"Meme not found\"}");
        }
        
        List<Comment> memeComments = comments.get(memeId);
        if (memeComments == null) {
            return jsonResponse(404, "Not Found", "{\"error\":\"Comment not found\"}");
        }
        
        // Find and remove comment
        boolean removed = memeComments.removeIf(comment -> comment.id == commentId);
        
        if (removed) {
            return jsonResponse(200, "OK", "{\"message\":\"Comment deleted\"}");
        } else {
            return jsonResponse(404, "Not Found", "{\"error\":\"Comment not found\"}");
        }
    }

   
    private static byte[] createResource(String body) {
        int id = nextId.getAndIncrement();
        
        // Create JSON file content
        // Parse body to extract meme data
        String memeData = body;
        if (!memeData.contains("\"id\"")) {
            // Insert id into the JSON
            memeData = "{\"id\":" + id + "," + body.substring(1);
        }
        
        // Save to file (as in your original code)
        String filePath = memesFolder + "/" + id + ".json";
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get(filePath), memeData.getBytes());
            memes.put(id, filePath);
            return jsonResponse(201, "Created", memeData);
        } catch (Exception e) {
            return jsonResponse(500, "Internal Server Error");
        }
    }


    private static byte[] handleGetRequest(String path, String body) {
        switch (path) {
            case "":
            case "/" :
                return getStatichtml();
            case "/resource": 
                return getAllResources();
        default:
            if (path.matches("/resource/[^/]+")) {
                int id = Integer.parseInt(path.split("/")[2]);
                return getResourceById(id);
            }
            if (path.startsWith("/Resources/")) {
                return gethtmlResource(path);
            }
            return jsonResponse(400, "Bad Request");
        }
    }

    private static byte[] getAllResources() {
        String json = "[" + String.join(",", memes.values()) + "]";
        return jsonResponse(200, "OK", json);
    }

    private static byte[] handlePutRequest(int id, String body) {
            String entry = body.substring(1);
            if (entry.isBlank()) {
                return jsonResponse(400, "Bad Request", "{\"error\":\"Empty body\"}");
            }
            String memepath = memes.get(id);
            try {
                Files.write(Paths.get(memepath), entry.getBytes(), StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception e) {
                return jsonResponse(500, "Internal Server Error");
            }
            return jsonResponse(200, "OK", entry);
    }

    private static byte[] deleteResourceById(int id) {
        if (memes.containsKey(id)) {
            try {
                String memePath = memes.get(id);
                Files.deleteIfExists(Paths.get(memePath));
                memes.remove(id);
                return jsonResponse(200, "OK");
            } catch (Exception e) {
                return jsonResponse(500, "Internal Server Error");
            }
           
        } else {
            return jsonResponse(404, "Not found");
        }
    }

    private static byte[] getResourceById(int id) {
        if (memes.containsKey(id)){
            try {
                String memePath = memes.get(id);
                String meme = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(memePath)));
                return jsonResponse(200, "OK", meme);
            } catch (Exception e) {
                return jsonResponse(500, "Internal Server Error");
            }
        } else {
            return jsonResponse(404, "Not found");
        }
    }

    private static byte[] getStatichtml(){
        try {            
            java.nio.file.Path fullPath = java.nio.file.Paths.get(htmlEndPoint);

            byte[] fileBytes = java.nio.file.Files.readAllBytes(fullPath);
            
            String contentType = getContentType(htmlEndPoint);

            return fileResponse(200, "OK", fileBytes, contentType);
        } catch (Exception e) {
            return jsonResponse(500, "Internal server error");
        }
    }

    private static byte[] gethtmlResource(String fileName){
        try {            
            java.nio.file.Path fullPath = java.nio.file.Paths.get("http grupal proyect/src/HTTPALL/Content"+fileName);
            if (java.nio.file.Files.exists(fullPath)){
                byte[] fileBytes = java.nio.file.Files.readAllBytes(fullPath);
                String contentType = getContentType(fileName);
                
                return fileResponse(200, "OK", fileBytes, contentType);
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

    private static byte[] fileResponse(int code, String reason, byte[] fileBytes, String contentType) {
        return buildResponse(code, reason, contentType, fileBytes);
    }
    
    
    // Existing meme storage
    private static Map<Integer, String> memes = new ConcurrentHashMap<>();
    private static final AtomicInteger nextId = new AtomicInteger(1);

    // NEW: Comment storage (memeId -> List of comments)
    private static Map<Integer, List<Comment>> comments = new ConcurrentHashMap<>();
    private static final AtomicInteger nextCommentId = new AtomicInteger(1);

    // Comment class
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
