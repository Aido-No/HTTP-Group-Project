package src.HTTPALL;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.Socket;
import java.net.URL;
import java.util.*;

public class HTTPClientGUI extends JFrame {
    private JTextField urlField;
    private JComboBox<String> methodCombo;
    private JTextArea bodyArea;
    private JTextArea responseArea;
    private JButton sendButton;
    private JTextArea headersArea;
    private JCheckBox customHeadersCheck;
    
    public HTTPClientGUI() {
        setTitle("HTTP Client - Real Working Client");
        setSize(900, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        createUI();
        setVisible(true);
    }
    
    private void createUI() {
        setLayout(new BorderLayout(10, 10));
        
        // Top panel - URL and Method
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        topPanel.add(new JLabel("URL:"));
        urlField = new JTextField("http://httpbin.org/get", 40);
        topPanel.add(urlField);
        topPanel.add(new JLabel("Method:"));
        methodCombo = new JComboBox<>(new String[]{"GET", "POST", "PUT", "DELETE", "HEAD"});
        topPanel.add(methodCombo);
        
        // Middle panel - Request Body and Headers
        JPanel middlePanel = new JPanel(new GridLayout(1, 2, 10, 10));
        
        // Body panel
        JPanel bodyPanel = new JPanel(new BorderLayout());
        bodyPanel.setBorder(BorderFactory.createTitledBorder("Request Body (for POST/PUT)"));
        bodyArea = new JTextArea(8, 30);
        bodyArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        bodyPanel.add(new JScrollPane(bodyArea), BorderLayout.CENTER);
        
        // Headers panel
        JPanel headersPanel = new JPanel(new BorderLayout());
        headersPanel.setBorder(BorderFactory.createTitledBorder("Custom Headers (format: Header: Value)"));
        headersArea = new JTextArea(8, 30);
        headersArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        headersArea.setText("User-Agent: MyHTTPClient/1.0\nAccept: */*");
        headersPanel.add(new JScrollPane(headersArea), BorderLayout.CENTER);
        
        middlePanel.add(bodyPanel);
        middlePanel.add(headersPanel);
        
        // Bottom panel - Send button and Response
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 10));
        
        sendButton = new JButton("🚀 SEND REQUEST");
        sendButton.setFont(new Font("Arial", Font.BOLD, 14));
        sendButton.setBackground(new Color(50, 150, 200));
        sendButton.setForeground(Color.WHITE);
        sendButton.addActionListener(e -> sendRequest());
        
        JPanel buttonPanel = new JPanel();
        buttonPanel.add(sendButton);
        bottomPanel.add(buttonPanel, BorderLayout.NORTH);
        
        // Response panel
        JPanel responsePanel = new JPanel(new BorderLayout());
        responsePanel.setBorder(BorderFactory.createTitledBorder("Response"));
        responseArea = new JTextArea(15, 80);
        responseArea.setEditable(false);
        responseArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        responsePanel.add(new JScrollPane(responseArea), BorderLayout.CENTER);
        bottomPanel.add(responsePanel, BorderLayout.CENTER);
        
        // Add all to main frame
        add(topPanel, BorderLayout.NORTH);
        add(middlePanel, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);
    }
    
    private void sendRequest() {
        // Clear previous response
        responseArea.setText("");
        
        String method = (String) methodCombo.getSelectedItem();
        String urlStr = urlField.getText().trim();
        String body = bodyArea.getText();
        String headersText = headersArea.getText();
        
        // Validate URL
        if (urlStr.isEmpty()) {
            responseArea.setText("ERROR: Please enter a URL");
            return;
        }
        
        // Validate body for POST/PUT
        if ((method.equals("POST") || method.equals("PUT")) && body.isEmpty()) {
            int result = JOptionPane.showConfirmDialog(this, 
                "POST/PUT requests usually have a body. Continue anyway?", 
                "Warning", JOptionPane.YES_NO_OPTION);
            if (result != JOptionPane.YES_OPTION) {
                return;
            }
        }
        
        // Run in separate thread to not freeze GUI
        new Thread(() -> {
            try {
                sendHTTPRequest(method, urlStr, body, headersText);
            } catch (Exception ex) {
                responseArea.setText("ERROR: " + ex.getMessage());
                ex.printStackTrace();
            }
        }).start();
    }
    
    private void sendHTTPRequest(String method, String urlStr, String body, String headersText) throws Exception {
        // Parse URL
        URL url = new URL(urlStr);
        String host = url.getHost();
        int port = url.getPort() != -1 ? url.getPort() : 80;
        String path = url.getPath().isEmpty() ? "/" : url.getPath();
        if (url.getQuery() != null) path += "?" + url.getQuery();
        
        // Parse custom headers
        Map<String, String> customHeaders = new HashMap<>();
        for (String line : headersText.split("\n")) {
            line = line.trim();
            if (!line.isEmpty() && line.contains(":")) {
                String[] parts = line.split(":", 2);
                customHeaders.put(parts[0].trim(), parts[1].trim());
            }
        }
        
        // Build request
        StringBuilder requestBuilder = new StringBuilder();
        requestBuilder.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
        requestBuilder.append("Host: ").append(host).append("\r\n");
        
        // Add custom headers
        for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
            requestBuilder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        
        // Add Content-Length for body
        if (body != null && !body.isEmpty()) {
            requestBuilder.append("Content-Length: ").append(body.getBytes().length).append("\r\n");
        }
        
        requestBuilder.append("Connection: close\r\n");
        requestBuilder.append("\r\n");
        
        if (body != null && !body.isEmpty()) {
            requestBuilder.append(body);
        }
        
        String request = requestBuilder.toString();
        
        // Display request in response area (as a header)
        SwingUtilities.invokeLater(() -> {
            responseArea.append("═══════════════════════════════════════════════════\n");
            responseArea.append("📤 SENDING REQUEST\n");
            responseArea.append("═══════════════════════════════════════════════════\n");
            responseArea.append(request);
            responseArea.append("\n\n");
        });
        
        // Connect and send
        try (Socket socket = new Socket(host, port)) {
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes());
            out.flush();
            
            InputStream in = socket.getInputStream();
            
            // Read full response
            ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                responseBuffer.write(buffer, 0, bytesRead);
            }
            
            String response = responseBuffer.toString();
            
            // Display response
            SwingUtilities.invokeLater(() -> {
                responseArea.append("═══════════════════════════════════════════════════\n");
                responseArea.append("📥 RESPONSE\n");
                responseArea.append("═══════════════════════════════════════════════════\n");
                responseArea.append(response);
                responseArea.append("\n\n");
            });
            
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> {
                responseArea.append("❌ CONNECTION ERROR: " + e.getMessage() + "\n");
            });
            throw e;
        }
    }
    
    public static void main(String[] args) {
        // Use SwingUtilities for thread safety
        SwingUtilities.invokeLater(() -> new HTTPClientGUI());
    }
}