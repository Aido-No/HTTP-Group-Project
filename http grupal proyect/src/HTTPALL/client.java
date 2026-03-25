import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class MyHTTPClient {
    public static void main(String[] args) {
        String server = "example.com";
        int port = 80;
        
        String request = "GET / HTTP/1.1\r\n"
                       + "Host: " + server + "\r\n"
                       + "Connection: close\r\n"
                       + "\r\n";
        
        try (Socket socket = new Socket(server, port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            
            out.println(request);
            
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println(line);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
