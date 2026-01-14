package com.example.thymeleaf.jira;

/**
 *  JQL中的project是可选的：
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
 *  <dependency>
 *       <groupId>com.fasterxml.jackson.core</groupId>
 *       <artifactId>jackson-databind</artifactId>
 *       <version>2.15.2</version>
 *   </dependency>
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class JiraIssueFetchClaudeService {

    private final String baseUrl;
    private final String authHeader;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * 构造函数
     * @param baseUrl Jira服务器地址，如 https://your-domain.atlassian.net
     * @param username 用户名（邮箱）
     * @param apiToken API Token（在Jira账户设置中生成）
     */
    public JiraIssueFetchClaudeService(String baseUrl, String username, String apiToken) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + apiToken).getBytes());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 批量获取Jira Issue
     * @param jql JQL查询语句，如 "project = MYPROJECT AND status = Open"
     * @param fields 需要返回的字段，如 "summary,status,assignee"，传null返回所有字段
     * @param maxResults 最大返回数量，-1表示获取所有
     * @return Issue列表
     */
    public List<JiraIssue> batchGetIssues(String jql, String fields, int maxResults) throws IOException, InterruptedException {
        List<JiraIssue> allIssues = new ArrayList<>();
        int startAt = 0;
        int pageSize = 100; // Jira API 单次最大返回100条
        int total;

        do {
            int fetchSize = (maxResults > 0)
                    ? Math.min(pageSize, maxResults - allIssues.size())
                    : pageSize;

            String response = searchIssues(jql, fields, startAt, fetchSize);
            JsonNode root = objectMapper.readTree(response);

            total = root.get("total").asInt();
            JsonNode issues = root.get("issues");

            for (JsonNode issueNode : issues) {
                JiraIssue issue = parseIssue(issueNode);
                allIssues.add(issue);
            }

            startAt += issues.size();

            // 如果指定了maxResults且已达到，则停止
            if (maxResults > 0 && allIssues.size() >= maxResults) {
                break;
            }

        } while (startAt < total);

        return allIssues;
    }

    /**
     * 根据Issue Key批量获取Issue
     * @param issueKeys Issue Key列表，如 ["PROJ-1", "PROJ-2"]
     * @return Issue列表
     */
    public List<JiraIssue> batchGetIssuesByKeys(List<String> issueKeys) throws IOException, InterruptedException {
        if (issueKeys == null || issueKeys.isEmpty()) {
            return new ArrayList<>();
        }

        // 构建JQL: key in (PROJ-1, PROJ-2, ...)
        String jql = "key in (" + String.join(",", issueKeys) + ")";
        return batchGetIssues(jql, null, -1);
    }

    /**
     * 调用Jira Search API
     */
    private String searchIssues(String jql, String fields, int startAt, int maxResults) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(baseUrl)
                .append("/rest/api/3/search")
                .append("?jql=").append(java.net.URLEncoder.encode(jql, "UTF-8"))
                .append("&startAt=").append(startAt)
                .append("&maxResults=").append(maxResults);

        if (fields != null && !fields.isEmpty()) {
            url.append("&fields=").append(java.net.URLEncoder.encode(fields, "UTF-8"));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Jira API请求失败，状态码: " + response.statusCode() + ", 响应: " + response.body());
        }

        return response.body();
    }

    /**
     * 解析Issue JSON
     */
    private JiraIssue parseIssue(JsonNode issueNode) {
        JiraIssue issue = new JiraIssue();
        issue.setId(issueNode.get("id").asText());
        issue.setKey(issueNode.get("key").asText());
        issue.setSelf(issueNode.get("self").asText());

        JsonNode fields = issueNode.get("fields");
        if (fields != null) {
            if (fields.has("summary")) {
                issue.setSummary(fields.get("summary").asText(null));
            }
            if (fields.has("status") && !fields.get("status").isNull()) {
                issue.setStatus(fields.get("status").get("name").asText(null));
            }
            if (fields.has("assignee") && !fields.get("assignee").isNull()) {
                issue.setAssignee(fields.get("assignee").get("displayName").asText(null));
            }
            if (fields.has("priority") && !fields.get("priority").isNull()) {
                issue.setPriority(fields.get("priority").get("name").asText(null));
            }
            if (fields.has("issuetype") && !fields.get("issuetype").isNull()) {
                issue.setIssueType(fields.get("issuetype").get("name").asText(null));
            }
            if (fields.has("created")) {
                issue.setCreated(fields.get("created").asText(null));
            }
            if (fields.has("updated")) {
                issue.setUpdated(fields.get("updated").asText(null));
            }
            if (fields.has("description") && !fields.get("description").isNull()) {
                issue.setDescription(fields.get("description").toString());
            }
        }

        return issue;
    }

    /**
     * Jira Issue 数据类
     */
    public static class JiraIssue {
        private String id;
        private String key;
        private String self;
        private String summary;
        private String status;
        private String assignee;
        private String priority;
        private String issueType;
        private String created;
        private String updated;
        private String description;

        // Getters and Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }

        public String getSelf() { return self; }
        public void setSelf(String self) { this.self = self; }

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getAssignee() { return assignee; }
        public void setAssignee(String assignee) { this.assignee = assignee; }

        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }

        public String getIssueType() { return issueType; }
        public void setIssueType(String issueType) { this.issueType = issueType; }

        public String getCreated() { return created; }
        public void setCreated(String created) { this.created = created; }

        public String getUpdated() { return updated; }
        public void setUpdated(String updated) { this.updated = updated; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        @Override
        public String toString() {
            return "JiraIssue{" +
                    "key='" + key + '\'' +
                    ", summary='" + summary + '\'' +
                    ", status='" + status + '\'' +
                    ", assignee='" + assignee + '\'' +
                    '}';
        }
    }

    // 使用示例
    public static void main(String[] args) {
        try {
            JiraIssueFetchClaudeService service = new JiraIssueFetchClaudeService(
                    "https://your-domain.atlassian.net",
                    "your-email@example.com",
                    "your-api-token"
            );

            // 示例1: 使用JQL查询获取Issue
            List<JiraIssue> issues = service.batchGetIssues(
                    "project = MYPROJECT AND status = Open",
                    "summary,status,assignee",
                    50
            );

            System.out.println("获取到 " + issues.size() + " 个Issue:");
            for (JiraIssue issue : issues) {
                System.out.println(issue);
            }

            // 示例2: 根据Key批量获取
            List<String> keys = List.of("PROJ-1", "PROJ-2", "PROJ-3");
            List<JiraIssue> issuesByKeys = service.batchGetIssuesByKeys(keys);
            System.out.println("按Key获取到 " + issuesByKeys.size() + " 个Issue");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
