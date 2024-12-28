package com.example.demo.proxy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class BrowserProxy {
    private ServerSocket serverSocket;
    private final ExecutorService executorService = Executors.newFixedThreadPool(50);
    private volatile boolean running = true;

    @PostConstruct
    public void start() {
        try {
            serverSocket = new ServerSocket(8082);
            log.info("Proxy server started on port 8082");
            
            new Thread(this::acceptConnections).start();
        } catch (IOException e) {
            log.error("Failed to start proxy server", e);
        }
    }

    private void acceptConnections() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                executorService.submit(() -> handleConnection(clientSocket));
            } catch (IOException e) {
                if (running) {
                    log.error("Error accepting connection", e);
                }
            }
        }
    }

    private void handleConnection(Socket clientSocket) {
        try {
            InputStream inputStream = clientSocket.getInputStream();
            
            // 读取HTTP请求头
            StringBuilder requestHeader = new StringBuilder();
            int previousByte = -1;
            int currentByte;
            
            // 读取直到遇到空行（\r\n\r\n）
            while ((currentByte = inputStream.read()) != -1) {
                requestHeader.append((char) currentByte);
                if (previousByte == '\r' && currentByte == '\n'
                        && requestHeader.toString().endsWith("\r\n\r\n")) {
                    break;
                }
                previousByte = currentByte;
            }
            
            String header = requestHeader.toString();
            log.info("Received request:\n{}", header);
            
            // 解析请求方法和目标地址
            String[] headerLines = header.split("\r\n");
            String[] requestLine = headerLines[0].split(" ");
            String method = requestLine[0];
            String target = requestLine[1];
            
            if ("CONNECT".equalsIgnoreCase(method)) {
                handleHttpsConnection(clientSocket, target);
            } else {
                handleHttpConnection(clientSocket, header);
            }
            
        } catch (Exception e) {
            log.error("Error handling connection", e);
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                log.error("Error closing client socket", e);
            }
        }
    }

    private void handleHttpsConnection(Socket clientSocket, String target) throws IOException {
        String[] hostPort = target.split(":");
        String host = hostPort[0];
        int port = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 443;
        
        // 连接目标服务器
        try (Socket targetSocket = new Socket(host, port)) {
            // 发送 200 OK
            clientSocket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
            
            // 创建双向通道
            Thread serverToClient = new Thread(() -> 
                transfer(targetSocket, clientSocket, "server -> client"));
            Thread clientToServer = new Thread(() -> 
                transfer(clientSocket, targetSocket, "client -> server"));
            
            serverToClient.start();
            clientToServer.start();
            
            // 等待连接结束
            serverToClient.join();
            clientToServer.join();
        } catch (Exception e) {
            log.error("Error in HTTPS connection to " + target, e);
        }
    }

    private void handleHttpConnection(Socket clientSocket, String header) throws IOException {
        // 从header中提取host和路径
        String host = null;
        for (String line : header.split("\r\n")) {
            if (line.toLowerCase().startsWith("host: ")) {
                host = line.substring(6).trim();
                break;
            }
        }
        
        if (host == null) {
            log.error("No host header found");
            return;
        }
        
        // 连接目标服务器
        String[] hostPort = host.split(":");
        int port = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 80;
        
        try (Socket targetSocket = new Socket(hostPort[0], port)) {
            // 转发请求
            targetSocket.getOutputStream().write(header.getBytes());
            
            // 转发响应
            transfer(targetSocket, clientSocket, "HTTP response");
        }
    }

    private void transfer(Socket from, Socket to, String direction) {
        try {
            byte[] buffer = new byte[8192];
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int length;
            while ((length = in.read(buffer)) != -1) {
                out.write(buffer, 0, length);
                out.flush();
            }
        } catch (IOException e) {
            log.debug("Transfer completed or connection closed: " + direction);
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.error("Error closing server socket", e);
        }
        executorService.shutdown();
    }
} 