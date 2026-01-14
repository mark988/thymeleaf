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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;

public class JiraIssueFetchGrokService {

    private static final String BASE_URL = "https://你的域名.atlassian.net";  // ← 修改成你的域名
    private static final String TOKEN     = "你的_ATLASSIAN_PAT_TOKEN";      // ← 填你的 Token

    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * 获取符合 JQL 的所有 issues（自动分页）
     */
    public static List<JsonNode> searchIssues(String jql, List<String> fields, int maxPerPage) throws Exception {

        List<JsonNode> allIssues = new ArrayList<>();

        int startAt = 0;
        boolean hasMore = true;

        while (hasMore) {
            JsonNode searchResult = searchOnePage(jql, startAt, maxPerPage, fields);

            JsonNode issues = searchResult.path("issues");
            int total = searchResult.path("total").asInt();
            int returned = issues.size();

            System.out.printf("Fetched %d issues (startAt=%d, total=%d)%n", returned, startAt, total);

            if (issues.isArray()) {
                for (JsonNode issue : issues) {
                    allIssues.add(issue);
                }
            }

            startAt += maxPerPage;
            hasMore = startAt < total;
        }

        return allIssues;
    }

    private static JsonNode searchOnePage(String jql, int startAt, int maxResults, List<String> fields) throws Exception {

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("jql", jql);
        requestBody.put("startAt", startAt);
        requestBody.put("maxResults", maxResults);

        if (fields != null && !fields.isEmpty()) {
            ArrayNode fieldsArray = mapper.createArrayNode();
            fields.forEach(fieldsArray::add);
            requestBody.set("fields", fieldsArray);
        }

        String jsonBody = mapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/rest/api/2/search"))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());

        int statusCode = response.statusCode();
        String body = response.body();

        if (statusCode < 200 || statusCode >= 300) {
            throw new RuntimeException(
                    "Jira search failed. Status: " + statusCode + "\nResponse: " + body);
        }

        return mapper.readTree(body);
    }

    // 使用範例
    public static void main(String[] args) {
        try {
            String jql = "project = MYPROJ AND status != Done AND created >= -30d ORDER BY created DESC";

            List<String> fields = List.of(
                    "key", "summary", "status", "assignee", "priority",
                    "created", "updated", "issuetype", "labels"
            );

            List<JsonNode> issues = searchIssues(jql, fields, 100);

            System.out.println("總共找到 issues 數量: " + issues.size());

            // 印前 5 筆看看
            for (int i = 0; i < Math.min(5, issues.size()); i++) {
                JsonNode issue = issues.get(i);

                String key = issue.path("key").asText();
                JsonNode fieldsNode = issue.path("fields");

                String summary = fieldsNode.path("summary").asText();
                String status = fieldsNode.path("status").path("name").asText();

                System.out.printf("%s | %s | %s%n", key, status, summary);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
