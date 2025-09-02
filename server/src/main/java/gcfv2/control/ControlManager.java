package gcfv2.control;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import gcfv2.gcs.GcsUrlUtil;
import org.eclipse.jetty.websocket.api.Session;
import gcfv2.ingest.IngestFirestoreService;

import java.io.IOException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ControlManager
 * - (유지) CapRequest 포워딩, Cap 분석 후 결과 회신/저장
 * - [ADDED] STT(Text) → OpenAI → 결과 회신(SttResult) + 합본 저장
 * - [ADDED] 세션별 대화 기억(chatMemory) 유지 (최근 6턴)
 */
public class ControlManager {

    private static final Gson GSON = new com.google.gson.GsonBuilder()
        .disableHtmlEscaping()
        .create();

    private final Map<String, Session> active = new ConcurrentHashMap<>();
    private final ExecutorService exec = Executors.newFixedThreadPool(3);

    private static final ZoneId ZONE_SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter OUT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private IngestFirestoreService ingestService;
    public void setIngestService(IngestFirestoreService s) { this.ingestService = s; }

    // [ADDED] 세션별 대화 메모리 (최근 12 메시지 = 6턴)
    private static final int MAX_MEMORY_MSGS = 12;
    private final Map<Session, List<Map<String, String>>> chatMemory = new ConcurrentHashMap<>();

    private List<Map<String,String>> history(Session s) {
        return chatMemory.computeIfAbsent(s, k -> Collections.synchronizedList(new ArrayList<>()));
    }
    private void pushHistory(Session s, String role, String content) {
        List<Map<String,String>> h = history(s);
        h.add(Map.of("role", role, "content", content));
        while (h.size() > MAX_MEMORY_MSGS) h.remove(0);
    }

    // ===== 세션 등록/해제 =====
    public void registerClient(String clientId, Session session) {
        active.put(clientId, session);
        System.out.println("[CONNECT] " + clientId + " / active=" + active.keySet());
    }
    public void unregisterClient(String clientId) {
        Session s = active.remove(clientId);             // [MODIFIED] 세션 객체 회수
        if (s != null) chatMemory.remove(s);            // [ADDED] 메모리 정리
        System.out.println("[DISCONNECT] " + clientId + " / active=" + active.keySet());
    }

    // ===== 라우팅 유틸 =====
    private Session getRc() { return active.get("android_rc"); }
    private void sendTo(Session s, String msg) {
        if (s != null && s.isOpen()) {
            try { s.getRemote().sendString(msg); } catch (IOException e) { e.printStackTrace(); }
        }
    }
    private void sendToRc(String msg) { sendTo(getRc(), msg); }

    // ===== 메시지 처리 (캡처 제어만) =====
    public String handle(Session session, String rawJson) {
        JsonObject obj = GSON.fromJson(rawJson, JsonObject.class);
        String type = obj.has("Type") ? obj.get("Type").getAsString() : "";

        if ("CapRequest".equals(type)) {
            sendToRc(rawJson);
            return "[CapRequest] forwarded to RC";
        }
        return null;
    }

    // ===========================================================
    // [ADDED] STT(Text) → GPT → 결과 회신 & 합본 저장
    // ===========================================================
    public void SttAnalyze(Session replyTo, String originalJson) {
        try {
            JsonObject in = GSON.fromJson(originalJson, JsonObject.class);
            String type = in.has("Type") ? in.get("Type").getAsString() : "";
            if (!"Stt".equals(type)) return;

            // [MODIFIED] Text만 사용 (Voice 제거)
            String userText = in.has("Text") ? in.get("Text").getAsString() : null;
            if (userText == null || userText.isBlank()) {
                System.err.println("[STT] skip: empty Text");
                return;
            }

            final String resultDatetime = nowString();

            exec.submit(() -> {
                try {
                    // 1) OpenAI 호출 (세션 메모리 포함)
                    String answer = callOpenAIText(replyTo, userText);

                    // 2) 클라이언트로 즉시 전달 (아웃바운드)
                    JsonObject outbound = new JsonObject();
                    outbound.addProperty("Type", "SttResult");
                    outbound.addProperty("Datetime", resultDatetime);
                    outbound.addProperty("Text", answer); // 응답은 answer만 보냄
                    if (replyTo != null && replyTo.isOpen()) {
                        sendTo(replyTo, outbound.toString());
                    }

                    // 3) 합본 문자열 구성 (요구사항)
                    String combined = "질문: " + userText + "\n답변: " + answer;

                    // 4) Firestore 저장은 합본 1건만 (raw에 in/out 모두 보관)
                    if (ingestService != null) {
                        JsonObject wrapper = new JsonObject();
                        wrapper.addProperty("Type", "SttResult");
                        wrapper.addProperty("Datetime", resultDatetime); // 아웃바운드 시각
                        wrapper.addProperty("Text", combined);           // 합본 문자열
                        wrapper.add("raw_inbound",  in);                 // 인바운드 원문
                        wrapper.add("raw_outbound", outbound);           // 아웃바운드 원문
                        try {
                            String saved = ingestService.handle(wrapper.toString());
                            System.out.println("[STT] stored combined: " + saved);
                        } catch (Exception e) {
                            System.err.println("[STT] store failed: " + e.getMessage());
                        }
                    }

                    // 5) 메모리에 user/assistant 축적 (세션 기억)
                    pushHistory(replyTo, "user", userText);
                    pushHistory(replyTo, "assistant", answer);

                } catch (Exception e) {
                    e.printStackTrace();
                    // 실패 시에도 간단 응답
                    JsonObject fail = new JsonObject();
                    fail.addProperty("Type", "SttResult");
                    fail.addProperty("Datetime", resultDatetime);
                    fail.addProperty("Text", "분석 실패: " + e.getMessage());
                    if (replyTo != null && replyTo.isOpen()) sendTo(replyTo, fail.toString());
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ===== OpenAI 호출 (텍스트 with 세션 메모리) =====
    private String callOpenAIText(Session session, String userText) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY 환경변수가 필요합니다.");
        }

        String system = "너는 하수구/배수로 청소 로봇의 보조관제 AI다. "
                + "사용자가 설명한 상황을 바탕으로 위험요소(감전/흡입/전선/날카로움/미끄럼), "
                + "주행 가능성, 청소 우선순위(분사/파쇄/우회)와 즉시 실행할 행동을 "
                + "간결하고 실용적으로 한국어로 제안해라. 이모지나 특수문자 없이.";

        // [ADDED] 세션 메모리 → messages 에 주입
        List<Map<String,String>> mem = history(session);
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role","system","content", system));
        synchronized (mem) {
            for (Map<String,String> m : mem) {
                messages.add(Map.of("role", m.get("role"), "content", m.get("content")));
            }
        }
        messages.add(Map.of("role","user","content", userText));

        Map<String, Object> body = Map.of(
                "model", "gpt-4o-mini",
                "messages", messages,
                "temperature", 0.2
        );

        String json = GSON.toJson(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new RuntimeException("OpenAI 호출 실패: " + res.statusCode() + " " + res.body());
        }

        JsonObject root = GSON.fromJson(res.body(), JsonObject.class);
        try {
            return root.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        } catch (Exception e) {
            throw new RuntimeException("OpenAI 응답 파싱 실패: " + e.getMessage() + " / body=" + res.body());
        }
    }

    // ===== 기존 Cap 분석 로직/유틸 (생략 없이 유지) =====
    public void CapAnalyze(Session replyTo, String originalJson, String dbResult) {
        try {
            JsonObject obj = GSON.fromJson(originalJson, JsonObject.class);
            String type = obj.has("Type") ? obj.get("Type").getAsString() : "";
            if (!"Cap".equals(type)) return;

            String datetime = obj.has("Datetime") ? obj.get("Datetime").getAsString() : nowString();

            String capId = tryExtractCapId(dbResult);
            String gcsUri = tryExtractGcsUri(dbResult);
            if ((gcsUri == null || gcsUri.isBlank()) && obj.has("GcsUri")) {
                gcsUri = obj.get("GcsUri").getAsString();
            }
            if (capId == null || gcsUri == null) {
                System.err.println("[AI] skip analyze: cannot parse id/gcs from dbResult=" + dbResult);
                return;
            }

            final String finalDatetime = datetime;
            final String finalId = capId;
            final String finalGcsUri = gcsUri;

            exec.submit(() -> {
                try {
                    String[] bo = splitGsUri(finalGcsUri);
                    URL signed = GcsUrlUtil.createDownloadUrl(bo[0], bo[1], Duration.ofMinutes(30));
                    String signedUrl = signed.toExternalForm();

                    String resultText = callOpenAIAnalyze(signedUrl);

                    if (ingestService != null) {
                        JsonObject ai = new JsonObject();
                        ai.addProperty("Type", "Ai");
                        ai.addProperty("Datetime", finalDatetime);
                        ai.addProperty("CapId", finalId);
                        ai.addProperty("GcsUri", finalGcsUri);
                        ai.addProperty("Url", signedUrl);
                        ai.addProperty("Result", resultText);
                        try {
                            String saved = ingestService.handle(ai.toString());
                            System.out.println("[AI] saved: " + saved);
                        } catch (Exception e) {
                            System.err.println("[AI] save failed: " + e.getMessage());
                        }
                    }

                    JsonObject resp = new JsonObject();
                    resp.addProperty("Type", "CapAnalysis");
                    resp.addProperty("Datetime", finalDatetime);
                    resp.addProperty("ID", finalId);
                    resp.addProperty("gcsurl", signedUrl);
                    resp.addProperty("result", resultText);

                    if (replyTo != null && replyTo.isOpen()) {
                        sendTo(replyTo, resp.toString());
                    } else {
                        System.out.println("[AI] replyTo session closed or null; result not delivered. capId=" + finalId);
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                    JsonObject fail = new JsonObject();
                    fail.addProperty("Type", "CapAnalysis");
                    fail.addProperty("Datetime", finalDatetime);
                    fail.addProperty("ID", finalId);
                    fail.addProperty("gcsurl", finalGcsUri);
                    fail.addProperty("result", "분석 실패: " + e.getMessage());
                    if (replyTo != null && replyTo.isOpen()) {
                        sendTo(replyTo, fail.toString());
                    }
                }
            });
        } catch (Exception ignore) {
            ignore.printStackTrace();
        }
    }

    private String callOpenAIAnalyze(String imageUrl) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("OPENAI_API_KEY 필요");

        String system = "현재 배수로 및 하수구 청소 로봇이 찍은 사진이다. "
                + "이미지를 보고 객체를 분석하여 알려주고 위험요소(감전/흡입/전선/날카로움/미끄럼), 주행 가능성, "
                + "청소 우선순위(분사/파쇄/우회)등 을 고려하여 분석한 결과를 간결하게 특수문자나 이모티콘없이"
                + "한국어 스크립트로 제안해라. 사용자에게 말하듯이 잘 정리해서";

        Map<String, Object> imagePart = Map.of(
                "type", "image_url",
                "image_url", Map.of("url", imageUrl)
        );
        Map<String, Object> textPart = Map.of(
                "type", "text",
                "text", "현재 상황 요약 + 위험요소 + 즉시 수행 액션(우선순위) 중심으로 말해줘."
        );

        Map<String, Object> body = Map.of(
                "model", "gpt-4o-mini",
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", List.of(textPart, imagePart))
                ),
                "temperature", 0.2
        );

        String json = GSON.toJson(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new RuntimeException("OpenAI 호출 실패: " + res.statusCode() + " " + res.body());
        }

        JsonObject root = GSON.fromJson(res.body(), JsonObject.class);
        try {
            return root.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        } catch (Exception e) {
            throw new RuntimeException("OpenAI 응답 파싱 실패: " + e.getMessage() + " / body=" + res.body());
        }
    }

    // ===== 유틸 =====
    private static String[] splitGsUri(String gcsUri) {
        String noPrefix = gcsUri.replaceFirst("^gs://", "");
        int idx = noPrefix.indexOf('/');
        if (idx < 0) throw new IllegalArgumentException("잘못된 GCS URI: " + gcsUri);
        return new String[]{ noPrefix.substring(0, idx), noPrefix.substring(idx + 1) };
    }
    private static String nowString() {
        return ZonedDateTime.now(ZONE_SEOUL).format(OUT_FMT);
    }
    private static String tryExtractCapId(String dbResult) {
        Pattern p = Pattern.compile("\\[Cap/(Cap_\\d{8}_\\d{6}_\\d+)\\]");
        Matcher m = p.matcher(dbResult);
        return m.find() ? m.group(1) : null;
    }
    private static String tryExtractGcsUri(String dbResult) {
        Pattern p = Pattern.compile("\\(gcs=(gs://[^)]+)\\)");
        Matcher m = p.matcher(dbResult);
        return m.find() ? m.group(1) : null;
    }
}
