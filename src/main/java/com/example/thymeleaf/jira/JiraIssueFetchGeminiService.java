package com.example.thymeleaf.jira;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class JiraIssueFetchGeminiService {

    // 替换为您的 Jira 实例地址
    private static final String JIRA_URL = "https://your-domain.atlassian.net";
    // 替换为您的注册邮箱
    private static final String EMAIL = "your-email@example.com";
    // 替换为您已有的 API Token
    private static final String API_TOKEN = "YOUR_API_TOKEN_HERE";

    /**
     * 根据 JQL 批量获取 Issue
     * @param jql 查询语句，例如 "project = 'PROJ' AND status = 'Open'"
     * @param maxResults 每次请求获取的数量（建议最大 100）
     * @param startAt 分页起始位置
     */
    public void getIssuesByJql(String jql, int maxResults, int startAt) {
        try {
            // 1. 构建 API URL (注意：使用 /rest/api/2/search 接口)
            String apiUrl = JIRA_URL + "/rest/api/2/search";

            // 2. 构建请求体 (JSON 格式)
            // 你可以根据需要添加 "fields" 数组来限制返回的字段，提高性能
            String jsonBody = String.format(
                    "{\"jql\": \"%s\", \"startAt\": %d, \"maxResults\": %d, \"fields\": [\"summary\", \"status\", \"assignee\"]}",
                    jql.replace("\"", "\\\""), // 处理引号转义
                    startAt,
                    maxResults
            );

            // 3. 构建 Basic Auth 认证头 (email:api_token 的 Base64)
            String auth = EMAIL + ":" + API_TOKEN;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));

            // 4. 发送请求
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Basic " + encodedAuth)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // 5. 处理结果
            if (response.statusCode() == 200) {
                System.out.println("成功获取数据: ");
                System.out.println(response.body()); // 这里通常会用 Jackson 或 Gson 解析 JSON
            } else {
                System.out.println("请求失败，状态码: " + response.statusCode());
                System.out.println("错误详情: " + response.body());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        JiraIssueFetchGeminiService service = new JiraIssueFetchGeminiService();
        // 示例：查询所有项目键为 "TEST" 的问题
        service.getIssuesByJql("project = TEST", 50, 0);
    }
}