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

public class Server {

	private static String API_KEY = null;
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

    private static void handleConnection(Socket socket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             OutputStream out = socket.getOutputStream()) {

            String requestLine = in.readLine();
            if (requestLine == null) return;

            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String path = parts[1];
            
            //Edited for API Key Authentication
            Map<String, String> headerMap = new HashMap<>();
            String line;
            int contentLength = 0;
            
            while (!(line = in.readLine()).isEmpty()) {
                int colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    String key = line.substring(0, colonIndex).toLowerCase().trim();
                    String value = line.substring(colonIndex + 1).trim();
                    headerMap.put(key, value);
                    
                    // Extract content-length while we're here
                    if (key.equals("content-length")) {
                        contentLength = Integer.parseInt(value);
                    }
                }
            }
            //Finished Editing for API Authentication 

            String body = "";
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                in.read(buf, 0, contentLength);
                body = new String(buf);
            }

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
        //GET REQUESTS
        if ("GET".equals(method)) { 
            System.out.println(path);
            return handleGetRequest(path, body);
        }

        //POST REQUESTS
        if ("POST".equals(method) && "/memes".equals(path)) {
            int id = nextId.getAndIncrement();
            String entry = "{\"id\":" + id + "," + body.substring(1);
            memes.put(id, entry);
            return jsonResponse(201, "Created", entry);
        }

        //PUT REQUESTS
        if ("PUT".equals(method) && path.matches("/memes/[^/]+")) {
            int id = Integer.parseInt(path.split("/")[2]);
            if (memes.containsKey(id)) {
                return handlePutRequest(id, body);
            } else {
                return jsonResponse(404, "Not Found");
            }
        }

        if ("DELETE".equals(method)) {
            System.out.println(path);
            return handleDeleteRequest(path, body);
        }

        return jsonResponse(404, "Not Found", "{\"error\":\"Not found\"}");
    }

    private static byte[] handleDeleteRequest(String path, String body) {
            if (path.matches("/resource/[^/]+")) {
                int id = Integer.parseInt(path.split("/")[2]);

                return deleteResourceById(id);
            }
            return jsonResponse(400, "Bad Request");

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
}
