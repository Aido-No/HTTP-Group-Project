package HTTPALL;
import java.io.*;
import java.net.Socket;
import java.net.URL;
import java.util.*;

public class MyHTTPClient {
	/* This will not be used in the final version
    private static Scanner scanner = new Scanner(System.in);
    private static Map<String, CookieData> cookies = new HashMap<>();
    
    static class CookieData {
        String value;
        long expiresAt;
        
        CookieData(String value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
    
    public static void main(String[] args) {
        System.out.println("=== HTTP Client (HTTP/1.1) ===");
        System.out.println("Type 'help' for commands, 'exit' to quit\n");
        
        while (true) {
            try {
                System.out.print("\n> ");
                String input = scanner.nextLine().trim();
                
                if (input.equalsIgnoreCase("exit")) {
                    System.out.println("Goodbye!");
                    break;
                }
                
                if (input.equalsIgnoreCase("help")) {
                    showHelp();
                    continue;
                }
                
                if (input.equalsIgnoreCase("cookies")) {
                    cookies.entrySet().removeIf(entry -> entry.getValue().isExpired());
                    if (cookies.isEmpty()) {
                        System.out.println("No cookies stored");
                    } else {
                        System.out.println("\nStored Cookies:");
                        for (Map.Entry<String, CookieData> entry : cookies.entrySet()) {
                            System.out.println("  " + entry.getKey() + " = " + entry.getValue().value);
                        }
                    }
                    continue;
                }
                
                if (input.isEmpty()) continue;
                
                HTTPRequest request = parseCommand(input);
                if (request != null) {
                    sendRequest(request);
                }
                
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }
    }
    
    private static void showHelp() {
        System.out.println("\nCommands:");
        System.out.println("  GET    <url>                    - Send GET request");
        System.out.println("  POST   <url> <body>             - Send POST request");
        System.out.println("  PUT    <url> <body>             - Send PUT request");
        System.out.println("  DELETE <url>                    - Send DELETE request");
        System.out.println("  HEAD   <url>                    - Send HEAD request");
        System.out.println("  header <name>:<value>           - Add custom header (will apply to next requests)");
        System.out.println("  headers                        - Show current custom headers");
        System.out.println("  clearheaders                   - Clear all custom headers");
        System.out.println("  cookies                        - Show stored cookies");
        System.out.println("  clearcookies                   - Clear all cookies");
        System.out.println("  help                           - Show this help");
        System.out.println("  exit                           - Quit");
        System.out.println("\nExamples:");
        System.out.println("  GET http://example.com/");
        System.out.println("  GET http://httpbin.org/get");
        System.out.println("  POST http://httpbin.org/post {\"name\":\"test\"}");
        System.out.println("  header X-Custom-Token: abc123");
    }
    
    private static Map<String, String> customHeaders = new HashMap<>();
    
    private static HTTPRequest parseCommand(String input) {
        String[] parts = input.split(" ", 3);
        if (parts.length < 2) {
            System.err.println("Usage: METHOD URL [body]");
            return null;
        }
        
        String method = parts[0].toUpperCase();
        String urlStr = parts[1];
        String body = parts.length > 2 ? parts[2] : null;
        
        if (method.equals("HEADER")) {
            String[] headerParts = urlStr.split(":", 2);
            if (headerParts.length == 2) {
                customHeaders.put(headerParts[0].trim(), headerParts[1].trim());
                System.out.println("Added header: " + headerParts[0].trim() + ": " + headerParts[1].trim());
            } else {
                System.err.println("Usage: header Name: Value");
            }
            return null;
        }
        
        if (method.equals("HEADERS")) {
            if (customHeaders.isEmpty()) {
                System.out.println("No custom headers set");
            } else {
                System.out.println("Current custom headers:");
                for (Map.Entry<String, String> e : customHeaders.entrySet()) {
                    System.out.println("  " + e.getKey() + ": " + e.getValue());
                }
            }
            return null;
        }
        
        if (method.equals("CLEARHEADERS")) {
            customHeaders.clear();
            System.out.println("All custom headers cleared");
            return null;
        }
        
        if (method.equals("CLEARCOOKIES")) {
            cookies.clear();
            System.out.println("All cookies cleared");
            return null;
        }
        
        List<String> validMethods = Arrays.asList("GET", "POST", "PUT", "DELETE", "HEAD");
        if (!validMethods.contains(method)) {
            System.err.println("Invalid method. Use: GET, POST, PUT, DELETE, HEAD");
            return null;
        }
        
        if ((method.equals("POST") || method.equals("PUT")) && (body == null || body.isEmpty())) {
            System.err.println(method + " requests require a body");
            return null;
        }
        
        return new HTTPRequest(method, urlStr, body, new HashMap<>(customHeaders));
    }
    
    private static void sendRequest(HTTPRequest req) {
        try {
            URL url = new URL(req.url);
            String host = url.getHost();
            int port = url.getPort() != -1 ? url.getPort() : 80;
            String path = url.getPath().isEmpty() ? "/" : url.getPath();
            if (url.getQuery() != null) path += "?" + url.getQuery();
            
            System.out.println("\n=== Sending " + req.method + " request to " + host + " ===");
            
            StringBuilder requestBuilder = new StringBuilder();
            requestBuilder.append(req.method).append(" ").append(path).append(" HTTP/1.1\r\n");
            requestBuilder.append("Host: ").append(host).append("\r\n");
            requestBuilder.append("User-Agent: MyHTTPClient/1.0\r\n");
            requestBuilder.append("Accept: r\n");
            
            cookies.entrySet().removeIf(entry -> entry.getValue().isExpired());
            if (!cookies.isEmpty()) {
                StringBuilder cookieHeader = new StringBuilder();
                for (Map.Entry<String, CookieData> entry : cookies.entrySet()) {
                    cookieHeader.append(entry.getKey())
                                .append("=")
                                .append(entry.getValue().value)
                                .append("; ");
                }
                requestBuilder.append("Cookie: ")
                              .append(cookieHeader.toString())
                              .append("\r\n");
            }
            
            requestBuilder.append("Connection: close\r\n");
            
            if (req.body != null && !req.body.isEmpty()) {
                requestBuilder.append("Content-Type: application/json\r\n");
                requestBuilder.append("Content-Length: ").append(req.body.getBytes().length).append("\r\n");
            }
            
            for (Map.Entry<String, String> entry : req.customHeaders.entrySet()) {
                requestBuilder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
            
            requestBuilder.append("\r\n");
            if (req.body != null && !req.body.isEmpty()) {
                requestBuilder.append(req.body);
            }
            
            String request = requestBuilder.toString();
            System.out.println("Request:\n" + request);
            
            try (Socket socket = new Socket(host, port)) {
                OutputStream out = socket.getOutputStream();
                out.write(request.getBytes());
                out.flush();
                
                InputStream in = socket.getInputStream();
                
                String response = readFullResponse(in);
                storeCookies(response);
                System.out.println("\n=== RESPONSE ===");
                System.out.println(response);
            }
            
        } catch (Exception e) {
            System.err.println("Request failed: " + e.getMessage());
        }
    }
    
    private static String readFullResponse(InputStream in) throws Exception {
        StringBuilder response = new StringBuilder();
        byte[] buffer = new byte[4096];
        int bytesRead;
        
        while ((bytesRead = in.read(buffer)) != -1) {
            response.append(new String(buffer, 0, bytesRead));
        }
        
        return response.toString();
    }
    
    private static void storeCookies(String response) {
        String[] lines = response.split("\r\n");
        for (String line : lines) {
            if (line.toLowerCase().startsWith("set-cookie:")) {
                String cookieData = line.substring("Set-Cookie:".length()).trim();
                String[] parts = cookieData.split(";");
                
                String name = null;
                String value = null;
                long maxAge = -1;
                
                for (String part : parts) {
                    part = part.trim();
                    if (part.contains("=") && !part.toLowerCase().startsWith("path") && !part.toLowerCase().startsWith("max-age")) {
                        String[] kv = part.split("=", 2);
                        name = kv[0].trim();
                        value = kv[1].trim();
                    }
                    if (part.toLowerCase().startsWith("max-age=")) {
                        maxAge = Long.parseLong(part.substring(8));
                    }
                }
                
                if (name != null && value != null) {
                    long expiresAt = maxAge > 0 ? System.currentTimeMillis() + (maxAge * 1000) : Long.MAX_VALUE;
                    cookies.put(name, new CookieData(value, expiresAt));
                    System.out.println("Cookie stored: " + name + "=" + value + " (expires in " + maxAge + "s)");
                }
            }
        }
    }
    
    static class HTTPRequest {
        String method;
        String url;
        String body;
        Map<String, String> customHeaders;
        
        HTTPRequest(String method, String url, String body, Map<String, String> customHeaders) {
            this.method = method;
            this.url = url;
            this.body = body;
            this.customHeaders = customHeaders;
        }
    }
    */
}