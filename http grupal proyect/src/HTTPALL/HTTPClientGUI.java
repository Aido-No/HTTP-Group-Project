package HTTPALL;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.Socket;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;

public class HTTPClientGUI extends JFrame {
    private JTextField urlField;
    private JComboBox<String> methodCombo;
    private JTextArea bodyArea;
    private JTextArea responseArea;
    private JButton sendButton;
    private JTextArea headersArea;
    private static Map<String, CookieData> cookies = new HashMap<>();
    private static Map<String, CacheEntry> cache = new HashMap<>();
    
    static class CacheEntry {
        String etag;
        String body;
        
        CacheEntry(String etag, String body) {
        	this.etag = etag;
        	this.body = body;
        }
    }
    
    static class CookieData {
        String value;
        long expiresAt;
        String path;
        
        CookieData(String value, long expiresAt, String path) {
            this.value = value;
            this.expiresAt = expiresAt;
            this.path = (path == null || path.isEmpty()) ? "/" : path;
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
        
        boolean matchesPath(String requestPath) {
            return requestPath.startsWith(this.path);
        }
    }
    
    public HTTPClientGUI() {
        setTitle("HTTP Client - Full Cookie Support");
        setSize(900, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        createUI();
        setVisible(true);
    }
    
    private void createUI() {
        setLayout(new BorderLayout(10, 10));
        
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        topPanel.add(new JLabel("URL:"));
        urlField = new JTextField("http://httpbin.org/cookies/set?name=value", 40);
        topPanel.add(urlField);
        topPanel.add(new JLabel("Method:"));
        methodCombo = new JComboBox<>(new String[]{"GET", "POST", "PUT", "DELETE", "HEAD"});
        topPanel.add(methodCombo);
        
        JPanel cookiePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        cookiePanel.setBorder(BorderFactory.createTitledBorder("Cookie Management"));
        JButton showCookiesBtn = new JButton("Show Cookies");
        JButton clearCookiesBtn = new JButton("Clear Cookies");
        
        showCookiesBtn.addActionListener(e -> showCookies());
        clearCookiesBtn.addActionListener(e -> {
            cookies.clear();
            JOptionPane.showMessageDialog(this, "All cookies cleared!");
            responseArea.append("All cookies cleared\n\n");
        });
        
        cookiePanel.add(showCookiesBtn);
        cookiePanel.add(clearCookiesBtn);
        
        JPanel middlePanel = new JPanel(new GridLayout(1, 2, 10, 10));
        
        JPanel bodyPanel = new JPanel(new BorderLayout());
        bodyPanel.setBorder(BorderFactory.createTitledBorder("Request Body (for POST/PUT)"));
        bodyArea = new JTextArea(8, 30);
        bodyArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        bodyPanel.add(new JScrollPane(bodyArea), BorderLayout.CENTER);
        
        JPanel headersPanel = new JPanel(new BorderLayout());
        headersPanel.setBorder(BorderFactory.createTitledBorder("Custom Headers (format: Header: Value)"));
        headersArea = new JTextArea(8, 30);
        headersArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        headersArea.setText("User-Agent: MyHTTPClient/1.0\nAccept: */*");
        headersPanel.add(new JScrollPane(headersArea), BorderLayout.CENTER);
        
        middlePanel.add(bodyPanel);
        middlePanel.add(headersPanel);
        
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 10));
        
        sendButton = new JButton("SEND REQUEST");
        sendButton.setFont(new Font("Arial", Font.BOLD, 14));
        sendButton.setBackground(new Color(50, 150, 200));
        sendButton.setForeground(Color.WHITE);
        sendButton.addActionListener(e -> sendRequest());
        
        JPanel buttonPanel = new JPanel();
        buttonPanel.add(sendButton);
        bottomPanel.add(buttonPanel, BorderLayout.NORTH);
        
        JPanel responsePanel = new JPanel(new BorderLayout());
        responsePanel.setBorder(BorderFactory.createTitledBorder("Response"));
        responseArea = new JTextArea(15, 80);
        responseArea.setEditable(false);
        responseArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        responsePanel.add(new JScrollPane(responseArea), BorderLayout.CENTER);
        bottomPanel.add(responsePanel, BorderLayout.CENTER);
        
        JPanel northContainer = new JPanel(new BorderLayout());
        northContainer.add(topPanel, BorderLayout.NORTH);
        northContainer.add(cookiePanel, BorderLayout.CENTER);
        
        add(northContainer, BorderLayout.NORTH);
        add(middlePanel, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);
    }
    
    private void showCookies() {
        cookies.entrySet().removeIf(entry -> entry.getValue().isExpired());
        
        if (cookies.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No cookies stored");
        } else {
            StringBuilder sb = new StringBuilder("Stored Cookies:\n\n");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            
            for (Map.Entry<String, CookieData> entry : cookies.entrySet()) {
                CookieData cd = entry.getValue();
                sb.append("Name: ").append(entry.getKey()).append("\n");
                sb.append("Value: ").append(cd.value).append("\n");
                sb.append("Path: ").append(cd.path).append("\n");
                if (cd.expiresAt < Long.MAX_VALUE) {
                    sb.append("Expires: ").append(sdf.format(new Date(cd.expiresAt))).append("\n");
                } else {
                    sb.append("Expires: Session (until browser closes)\n");
                }
                sb.append("------------------------\n");
            }
            
            JTextArea textArea = new JTextArea(sb.toString());
            textArea.setEditable(false);
            textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
            JScrollPane scrollPane = new JScrollPane(textArea);
            scrollPane.setPreferredSize(new Dimension(500, 400));
            JOptionPane.showMessageDialog(this, scrollPane, "Cookies", JOptionPane.INFORMATION_MESSAGE);
        }
    }
    
    private void sendRequest() {
        responseArea.setText("");
        
        String method = (String) methodCombo.getSelectedItem();
        String urlStr = urlField.getText().trim();
        String body = bodyArea.getText();
        String headersText = headersArea.getText();
        
        if (urlStr.isEmpty()) {
            responseArea.setText("ERROR: Please enter a URL");
            return;
        }
        
        if ((method.equals("POST") || method.equals("PUT")) && body.isEmpty()) {
            int result = JOptionPane.showConfirmDialog(this, 
                "POST/PUT requests usually have a body. Continue anyway?", 
                "Warning", JOptionPane.YES_NO_OPTION);
            if (result != JOptionPane.YES_OPTION) {
                return;
            }
        }
        
        new Thread(() -> {
            try {
                sendHTTPRequest(method, urlStr, body, headersText);
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> 
                    responseArea.setText("ERROR: " + ex.getMessage()));
                ex.printStackTrace();
            }
        }).start();
    }
    
    private void sendHTTPRequest(String method, String urlStr, String body, String headersText) throws Exception {
        URL url = new URL(urlStr);
        String host = url.getHost();
        int port = url.getPort() != -1 ? url.getPort() : 80;
        String path = url.getPath().isEmpty() ? "/" : url.getPath();
        if (url.getQuery() != null) path += "?" + url.getQuery();
        
        Map<String, String> customHeaders = new HashMap<>();
        for (String line : headersText.split("\n")) {
            line = line.trim();
            if (!line.isEmpty() && line.contains(":")) {
                String[] parts = line.split(":", 2);
                customHeaders.put(parts[0].trim(), parts[1].trim());
            }
        }
        
        StringBuilder requestBuilder = new StringBuilder();
        requestBuilder.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
        requestBuilder.append("Host: ").append(host).append("\r\n");
        
        for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
            requestBuilder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        
        cookies.entrySet().removeIf(entry -> entry.getValue().isExpired());
        if (!cookies.isEmpty()) {
            StringBuilder cookieHeader = new StringBuilder();
            boolean first = true;
            
            for (Map.Entry<String, CookieData> entry : cookies.entrySet()) {
                CookieData cd = entry.getValue();
                if (cd.matchesPath(path)) {
                    if (!first) {
                        cookieHeader.append("; ");
                    }
                    cookieHeader.append(entry.getKey()).append("=").append(cd.value);
                    first = false;
                }
            }
            
            if (!first) {
                requestBuilder.append("Cookie: ").append(cookieHeader.toString()).append("\r\n");
            }
        }
        
        CacheEntry cached = cache.get(urlStr);
        
        if (cached != null) {
        	requestBuilder.append("If-None-Match: \"").append(cached.etag).append("\"\r\n");
        }
        
        if (body != null && !body.isEmpty()) {
            requestBuilder.append("Content-Length: ").append(body.getBytes().length).append("\r\n");
        }
        
        requestBuilder.append("Connection: close\r\n");
        requestBuilder.append("\r\n");
        
        if (body != null && !body.isEmpty()) {
            requestBuilder.append(body);
        }
        
        String request = requestBuilder.toString();
        
        SwingUtilities.invokeLater(() -> {
            responseArea.append("REQUEST\n");
            responseArea.append(request);
            responseArea.append("\n\n");
        });
        
        try (Socket socket = new Socket(host, port)) {
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes());
            out.flush();
            
            InputStream in = socket.getInputStream();
            
            ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                responseBuffer.write(buffer, 0, bytesRead);
            }
            
            String response = responseBuffer.toString();
            
            storeCookies(response, path);
            
            if(response.contains("304 Not Modified"))
            {
            	CacheEntry cachedresponse = cache.get(urlStr);
                if (cachedresponse != null) {
                    String cachedBody = cachedresponse.body;
                    SwingUtilities.invokeLater(() -> {
                        responseArea.append("RESPONSE\n");
                        responseArea.append("HTTP/1.1 304 Not Modified\n\n");
                        responseArea.append("Resource not changed - USING CACHED VERSION\n");
                        responseArea.append(cachedBody);
                        responseArea.append("\n\n");
                        responseArea.append("(Retrieved from cache - no network transfer for body)\n\n");
                        responseArea.append("Active cookies: " + cookies.size() + "\n\n");
                    }); 
                }
                return; 
            }
            
            if (response.contains("200 OK") && "GET".equals(method))
            {
            	String etag = null;
            	String[] lines = response.split("\r\n");
            	for (String line : lines) {
            	    if (line.toLowerCase().startsWith("etag:")) {
            	        etag = line.substring(5).trim();
            	        if (etag.startsWith("\"") && etag.endsWith("\"")) {
            	            etag = etag.substring(1, etag.length() - 1);
            	        }
            	        break;
            	    }
            	}
            	String responseBody = null;
            	int bodyStart = response.indexOf("\r\n\r\n");
            	if (bodyStart != -1) {
            	    responseBody = response.substring(bodyStart + 4);
            	}
                if (etag != null && responseBody != null) {
                    cache.put(urlStr, new CacheEntry(etag, responseBody));
                }
            } 
            
            SwingUtilities.invokeLater(() -> {
                responseArea.append("RESPONSE\n");
                responseArea.append(response);
                responseArea.append("\n\n");
                responseArea.append("Active cookies: " + cookies.size() + "\n\n");
            });
            
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> {
                responseArea.append("CONNECTION ERROR: " + e.getMessage() + "\n");
            });
            throw e;
        }
    }
    
    
    
    private static void storeCookies(String response, String currentPath) {
        String[] lines = response.split("\r\n");
        
        for (String line : lines) {
            if (line.toLowerCase().startsWith("set-cookie:")) {
                String cookieData = line.substring("Set-Cookie:".length()).trim();
                String[] parts = cookieData.split(";");
                
                String name = null;
                String value = null;
                long maxAge = -1;
                Date expires = null;
                String cookiePath = null;
                
                for (String part : parts) {
                    part = part.trim();
                    
                    if (part.contains("=") && 
                        !part.toLowerCase().startsWith("path") && 
                        !part.toLowerCase().startsWith("max-age") &&
                        !part.toLowerCase().startsWith("expires")) {
                        String[] kv = part.split("=", 2);
                        name = kv[0].trim();
                        value = kv[1].trim();
                    }
                    
                    if (part.toLowerCase().startsWith("max-age=")) {
                        try {
                            maxAge = Long.parseLong(part.substring(8));
                        } catch (NumberFormatException e) {
                            maxAge = -1;
                        }
                    }
                    
                    if (part.toLowerCase().startsWith("expires=")) {
                        String expiresStr = part.substring(8);
                        try {
                            SimpleDateFormat[] dateFormats = {
                                new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
                                new SimpleDateFormat("EEE, dd-MMM-yyyy HH:mm:ss zzz", Locale.US),
                                new SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", Locale.US)
                            };
                            
                            for (SimpleDateFormat sdf : dateFormats) {
                                try {
                                    expires = sdf.parse(expiresStr);
                                    break;
                                } catch (java.text.ParseException e) {
                                }
                            }
                        } catch (Exception e) {
                        }
                    }
                    
                    if (part.toLowerCase().startsWith("path=")) {
                        cookiePath = part.substring(5);
                    }
                }
                
                if (name != null && value != null) {
                    long expiresAt;
                    
                    if (maxAge > 0) {
                        expiresAt = System.currentTimeMillis() + (maxAge * 1000);
                    } else if (expires != null) {
                        expiresAt = expires.getTime();
                    } else {
                        expiresAt = Long.MAX_VALUE;
                    }
                    
                    if (cookiePath == null || cookiePath.isEmpty()) {
                        cookiePath = "/";
                    }
                    
                    cookies.put(name, new CookieData(value, expiresAt, cookiePath));
                }
            }
        }
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new HTTPClientGUI());
    }
}