package com.example.demo.controller;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.*;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;

@Slf4j
@RestController
public class ProxyController {

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxyRequest(HttpServletRequest request) throws URISyntaxException, IOException {
        // 记录请求基本信息
        log.info("Received {} request from: {}", request.getMethod(), request.getRemoteAddr());
        
        // 获取原始URL和主机信息
        String host = request.getHeader("Host");
        log.info("Host header: {}", host);
        if (host == null) {
            log.error("Missing Host header");
            return ResponseEntity.badRequest().body("Missing Host header".getBytes());
        }
        
        // 如果是CONNECT方法（用于HTTPS），返回200表示隧道建立
        if ("CONNECT".equalsIgnoreCase(request.getMethod())) {
            log.info("Handling CONNECT request for: {}", host);
            return ResponseEntity.ok().build();
        }

        // 构建目标URL
        String targetUrl;
        String requestUri = request.getRequestURI();
        String queryString = request.getQueryString();
        
        log.info("Request URI: {}", requestUri);
        log.info("Query String: {}", queryString);
        
        // 如果URI是根路径，直接使用Host
        if (requestUri.equals("/")) {
            targetUrl = "http://" + host;
            log.info("Root path request, using host as target");
        } else {
            targetUrl = "http://" + host + requestUri;
            log.info("Non-root path request");
        }
        
        // 添加查询参数
        if (queryString != null && !queryString.isEmpty()) {
            targetUrl += "?" + queryString;
        }

        log.info("Final target URL: {}", targetUrl);
        
        // 创建HTTP客户端，配置SSL
        try (CloseableHttpClient httpClient = HttpClients.custom()
                .setSSLHostnameVerifier((hostname, session) -> true)
                .build()) {
            // 创建代理请求
            HttpRequestBase proxyRequest = createProxyRequest(request, targetUrl);
            log.info("Created proxy request: {} {}", proxyRequest.getMethod(), proxyRequest.getURI());
            
            // 复制原始请求的headers
            copyRequestHeaders(request, proxyRequest);

            // 执行请求
            log.info("Executing proxy request...");
            try (CloseableHttpResponse proxyResponse = httpClient.execute(proxyRequest)) {
                log.info("Received response with status: {}", proxyResponse.getStatusLine().getStatusCode());
                return buildResponse(proxyResponse);
            }
        } catch (Exception e) {
            log.error("Error during proxy request", e);
            throw e;
        }
    }

    private HttpRequestBase createProxyRequest(HttpServletRequest request, String targetUrl) throws URISyntaxException {
        String method = request.getMethod();
        URI uri = new URI(targetUrl);

        switch (method) {
            case "GET":
                return new HttpGet(uri);
            case "POST":
                HttpPost post = new HttpPost(uri);
                try {
                    post.setEntity(new InputStreamEntity(request.getInputStream()));
                } catch (IOException e) {
                    log.error("Error reading request body", e);
                }
                return post;
            case "PUT":
                HttpPut put = new HttpPut(uri);
                try {
                    put.setEntity(new InputStreamEntity(request.getInputStream()));
                } catch (IOException e) {
                    log.error("Error reading request body", e);
                }
                return put;
            case "DELETE":
                return new HttpDelete(uri);
            default:
                throw new UnsupportedOperationException("Unsupported HTTP method: " + method);
        }
    }

    private void copyRequestHeaders(HttpServletRequest request, HttpRequestBase proxyRequest) {
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            String headerValue = request.getHeader(headerName);
            // 跳过一些特殊的header
            if (!"content-length".equalsIgnoreCase(headerName) && 
                !"host".equalsIgnoreCase(headerName)) {
                proxyRequest.setHeader(headerName, headerValue);
            }
        }
    }

    private ResponseEntity<byte[]> buildResponse(CloseableHttpResponse proxyResponse) throws IOException {
        // 读取响应内容
        HttpEntity entity = proxyResponse.getEntity();
        byte[] body = entity != null ? org.apache.commons.io.IOUtils.toByteArray(entity.getContent()) : new byte[0];

        // 构建响应header
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        for (Header header : proxyResponse.getAllHeaders()) {
            headers.add(header.getName(), header.getValue());
        }

        // 返回响应
        return new ResponseEntity<>(
            body,
            headers,
            HttpStatus.valueOf(proxyResponse.getStatusLine().getStatusCode())
        );
    }
} 