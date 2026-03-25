import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class Server {

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
            out.write(response.getBytes());
            out.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String route(String method, String path, String body) {
        if ("GET".equals(method) && "/cats".equals(path)) {
            String json = "[" + String.join(",", cats.values()) + "]";
            return jsonResponse(200, "OK", json);
        }

        if ("POST".equals(method) && "/cats".equals(path)) {
            int id = nextId.getAndIncrement();
            String entry = "{\"id\":" + id + "," + body.substring(1);
            cats.put(id, entry);
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
