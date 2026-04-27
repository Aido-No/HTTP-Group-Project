package HTTPALL;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class Server {

    private static final Map<Integer, String> memes = new ConcurrentHashMap<>();
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
            out.write(response.getBytes());
            out.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String route(String method, String path, String body) {
        if ("GET".equals(method) && "/memes".equals(path)) {    // GET that handles GET /memes
            String json = "[" + String.join(",", memes.values()) + "]";
            return jsonResponse(200, "OK", json);
        }

        // for (String keys : memes.keySet()) {

        // }
        // System.out.println("key: " + );

        if ("GET".equals(method) && path.startsWith("/memes/")) {
        try {
            // Extract the part after "/memes/"
            String memeID = path.substring(7); 
            
            // Handle cases where user might type /memes/1.json
            if (memeID.endsWith(".json")) {
                memeID = memeID.substring(0, memeID.length() - 5);
            }

            int id = Integer.parseInt(memeID);
            String meme = memes.get(id);

            if (meme != null) {
                return jsonResponse(200, "OK", meme);
            } else {
                return jsonResponse(404, "Not Found", "{\"error\":\"Meme #" + id + " not found in memory\"}");
            }
        } catch (NumberFormatException e) {
            return jsonResponse(400, "Bad Request", "{\"error\":\"Invalid ID format\"}");
        }
    }

        if ("POST".equals(method) && "/memes".equals(path)) {
            int id = nextId.getAndIncrement();
            String entry = "{\"id\":" + id + "," + body.substring(1);
            memes.put(id, entry);
            return jsonResponse(201, "Created", entry);
        }

        return jsonResponse(404, "Not Found", "{\"error\":\"Not found\"}");
    }

    private static String jsonResponse(int code, String reason, String json) {
        return "HTTP/1.1 " + code + " " + reason + "\r\n"
             + "Content-Type: application/json\r\n"
             + "Content-Length: " + json.getBytes().length + "\r\n"
             + "Connection: close\r\n"
             + "\r\n"
             + json;
    }
}
