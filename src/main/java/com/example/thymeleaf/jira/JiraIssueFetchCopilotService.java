package com.example.thymeleaf.jira;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 *   JQL中的project是可选的：
 *
 *   | 情况          | JQL示例                          | 说明                   |
 *   |---------------|----------------------------------|------------------------|
 *   | 不指定project | status = Open                    | 搜索你有权限的所有项目 |
 *   | 指定单个      | project = TEST AND status = Open | 只搜索TEST项目         |
 *   | 指定多个      | project in (TEST, DEMO)          | 搜索多个项目           |
 *
 *   但建议指定project，原因：
 *   1. 不指定会搜索所有有权限的项目，结果可能很多
 *   2. 查询速度更快
 *   3. 结果更精确可控
 *
 *   如果你不知道project key，可以通过这个API获取项目列表：
 *   GET /rest/api/2/project
 * 简单 Jira client：按 JQL 分页获取所有 issues（使用 /rest/api/2/search）。
 *
 * 运行前请设置环境变量：
 *   JIRA_BASE_URL    e.g. https://your-domain.atlassian.net
 *   JIRA_EMAIL       你的 Atlassian 邮箱
 *   JIRA_API_TOKEN   你的 API token
 *
 * 示例运行（在项目根目录）:
 *   mvn -q compile exec:java -Dexec.mainClass="com.example.jira.JiraClient"
 */
public class JiraIssueFetchCopilotService {
    private final String baseUrl; // e.g. https://your-domain.atlassian.net
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String authHeaderValue;

    private JiraIssueFetchCopilotService(String baseUrl, String authHeaderValue) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.authHeaderValue = authHeaderValue;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    // 使用 Basic auth (email + apiToken)
    public static JiraIssueFetchCopilotService withBasic(String baseUrl, String email, String apiToken) {
        String raw = email + ":" + apiToken;
        String b64 = Base64.getEncoder().encodeToString(raw.getBytes());
        String header = "Basic " + b64;
        return new JiraIssueFetchCopilotService(baseUrl, header);
    }

    /**
     * 按 JQL 获取所有 issue（会自动分页）。
     *
     * @param jql      JQL 查询
     * @param pageSize 每页最大数量（maxResults），建议 <=100
     * @return List<JsonNode> 每个元素为 issue 对象（原始 JSON）
     * @throws IOException
     * @throws InterruptedException
     */
    public List<JsonNode> searchAllIssues(String jql, int pageSize) throws IOException, InterruptedException {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be > 0");
        }
        List<JsonNode> all = new ArrayList<>();
        int startAt = 0;
        int total = Integer.MAX_VALUE;

        while (startAt < total) {
            ObjectNode body = mapper.createObjectNode();
            body.put("jql", jql);
            body.put("startAt", startAt);
            body.put("maxResults", pageSize);
            // 指定常用字段，减少返回体积。根据需要调整或删除此字段以返回全部字段。
            body.putPOJO("fields", new String[] { "summary", "status", "assignee", "created", "issuetype", "priority", "labels", "reporter", "description", "key" });

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/rest/api/2/search"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", authHeaderValue)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                throw new IOException("Jira API returned HTTP " + code + ": " + resp.body());
            }

            JsonNode root = mapper.readTree(resp.body());
            if (root.has("total")) {
                total = root.get("total").asInt();
            }
            if (root.has("issues") && root.get("issues").isArray()) {
                for (JsonNode issue : root.get("issues")) {
                    all.add(issue);
                }
            } else {
                break; // 没有 issues 字段，结束
            }

            // next page
            startAt += pageSize;
        }

        return all;
    }

    // 工具：打印 Issue 的 key 和 summary（安全读取）
    private static void printIssueSummary(JsonNode issue) {
        String key = Optional.ofNullable(issue.get("key")).map(JsonNode::asText).orElse("<no-key>");
        String summary = Optional.ofNullable(issue.get("fields"))
                .map(f -> f.get("summary"))
                .map(JsonNode::asText)
                .orElse("<no-summary>");
        System.out.println(key + " - " + summary);
    }

    public static void main(String[] args) throws Exception {
        String base = System.getenv("JIRA_BASE_URL");
        String email = System.getenv("JIRA_EMAIL");
        String apiToken = System.getenv("JIRA_API_TOKEN");

        if (base == null || email == null || apiToken == null) {
            System.err.println("Please set environment variables: JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN");
            System.err.println("Example:");
            System.err.println("  export JIRA_BASE_URL=https://your-domain.atlassian.net");
            System.err.println("  export JIRA_EMAIL=you@example.com");
            System.err.println("  export JIRA_API_TOKEN=your_api_token");
            System.exit(2);
        }

        JiraIssueFetchCopilotService client = JiraIssueFetchCopilotService.withBasic(base, email, apiToken);

        // 你可以从 args 或环境变量传入 JQL；下面使用一个默认 JQL（近30天内创建的 issue）
        String jql = System.getenv().getOrDefault("JQL", "project = MYPROJ AND created >= -30d ORDER BY created DESC");
        int pageSize = 50; // 可调整，通常不超过100

        System.out.println("Running JQL: " + jql);
        List<JsonNode> issues = client.searchAllIssues(jql, pageSize);

        System.out.println("Total fetched: " + issues.size());
        for (JsonNode issue : issues) {
            printIssueSummary(issue);
        }

        // 如果需要把 JsonNode 转成字符串或保存文件，可以如下示例：
        ObjectMapper mapper = new ObjectMapper();
        String allAsJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(issues);
        // 下面示例仅打印前 500 字符以避免过长输出；根据需要注释掉
        System.out.println("\nJSON preview (first 500 chars):");
        System.out.println(allAsJson.substring(0, Math.min(500, allAsJson.length())));
    }
}
