package HTTPALL;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class server {

    private static final Map<Integer, String> cats = new ConcurrentHashMap<>();
    private static final AtomicInteger nextId = new AtomicInteger(1);

    public static void main(String[] args) throws Exception {
        int port = 3000;
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server listening on port " + port);
            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(() -> handleConnection(socket)).start();
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

         
            String response = route(method, path, body);
            
            if (response == null) {
             
                sendFile(out, path);
            } else {
                out.write(response.getBytes());
                out.flush();
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static int getIdFromPath(String path) {
        if (path.startsWith("/cats/") && path.length() > 6) {
            try {
                return Integer.parseInt(path.substring(6));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private static String route(String method, String path, String body) {      


    	 
        if ("GET".equals(method) && "/cats".equals(path)) {
            String json = "[" + String.join(",", cats.values()) + "]";
            return jsonResponse(200, "OK", json);
        }
        

        if ("GET".equals(method) && path.startsWith("/cats/") && !"/cats".equals(path)) {
            int id = getIdFromPath(path);
            String cat = cats.get(id);
            if (cat == null) {
                return jsonResponse(404, "Not Found", "{\"error\":\"Cat not found\"}");
            }
            return jsonResponse(200, "OK", cat);
        }
        
     
        if ("PUT".equals(method) && path.startsWith("/cats/") && !"/cats".equals(path)) {
            int id = getIdFromPath(path);

            if (!cats.containsKey(id)) {
                return jsonResponse(404, "Not Found", "{\"error\":\"Cat not found\"}");
            }
            if (body == null || body.isEmpty()) {
                return jsonResponse(400, "Bad Request", "{\"error\":\"Body required\"}");
            }
            String entry = "{\"id\":" + id + "," + body.substring(1);
            cats.put(id, entry);
            return jsonResponse(200, "OK", entry);
        }
        

        if ("DELETE".equals(method) && path.startsWith("/cats/") && !"/cats".equals(path)) {
            int id = getIdFromPath(path);

            if (!cats.containsKey(id)) {
                return jsonResponse(404, "Not Found", "{\"error\":\"Cat not found\"}");
            }
            cats.remove(id);

            return "HTTP/1.1 204 No Content\r\n" +
                   "Content-Length: 0\r\n" +
                   "Connection: close\r\n" +
                   "\r\n";
        }

        if ("POST".equals(method) && "/cats".equals(path)) {
            int id = nextId.getAndIncrement();
            String entry = "{\"id\":" + id + "," + body.substring(1);
            cats.put(id, entry);
            return jsonResponse(201, "Created", entry);
        }

        return jsonResponse(404, "Not Found", "{\"error\":\"Not found\"}");
    }

    private static void sendFile(OutputStream out, String path){
        
        try {
            String fileName = "Content/";
            if ("/".equals(path)) {
                fileName += "index.html";
            } else if ("/epicmeme".equals(path)) {
                fileName += "Memes/3.png";
            } else {
                fileName += path;
            }
            
            System.out.println(fileName);
            
            java.nio.file.Path fullPath = java.nio.file.Paths.get(fileName);

            byte[] fileBytes = java.nio.file.Files.readAllBytes(fullPath);
            
            System.out.println("sending file: " + fullPath);

            String contentType = getContentType(fileName);

            String headers =
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + fileBytes.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";

            out.write(headers.getBytes());
            out.write(fileBytes);
            out.flush();
        } catch (Exception e) {
            e.printStackTrace();
            try {
                String errorMessage = jsonResponse(404, "Not Found", "{\"error\":\"Not found\"}");
                out.write(errorMessage.getBytes());
                out.flush();
                
            } catch (Exception ex) {
                ex.printStackTrace();
            }
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

    private static String jsonResponse(int code, String reason, String json) {
        
        String mssg = "HTTP/1.1 " + code + " " + reason + "\r\n"
             + "Content-Type: application/json\r\n"
             + "Content-Length: " + json.getBytes().length + "\r\n"
             + "Connection: close\r\n"
             + "\r\n"
             + json;
        return mssg;
    }
}
