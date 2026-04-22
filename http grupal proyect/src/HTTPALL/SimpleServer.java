package src.HTTPALL;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class SimpleServer {

    public static void main(String[] args) {
        int port = 3000;
        
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server is listening on port " + port);
            
            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("New client connected");
                
                try (BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                     OutputStream output = socket.getOutputStream();
                     PrintWriter writer = new PrintWriter(output, true)) {
                    
                    StringBuilder requestBuilder = new StringBuilder();
                    String line;
                    while (!(line = input.readLine()).isBlank()) {
                        requestBuilder.append(line + "\r\n");
                    }
                    
                    String request = requestBuilder.toString();
                    
                    int bodyIndex = request.indexOf("\r\n\r\n");
                    String body = request.substring(bodyIndex + 4); // Skip the "\r\n\r\n"
                    
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n" +
                                      "Echoing back your request body:\r\n" + body;
                    
                    writer.print(response);
                    
                    System.out.println("Client disconnected");
                }
            }
        } catch (Exception e) {
            System.out.println("Server exception: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
