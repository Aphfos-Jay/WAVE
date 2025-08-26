package gcfv2.control;

import com.google.cloud.Timestamp;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import gcfv2.gcs.GcsUrlUtil;
import org.eclipse.jetty.websocket.api.Session;
import gcfv2.ingest.IngestFirestoreService;    


import java.io.IOException;
import java.net.URL;
import java.net.http.HttpClient;              // [ADDED] OpenAI 호출용
import java.net.http.HttpRequest;            // [ADDED] OpenAI 호출용
import java.net.http.HttpResponse;           // [ADDED] OpenAI 호출용
import java.time.Duration;                   // [ADDED] URL TTL
import java.time.Instant;
import java.time.ZoneId;                     // [ADDED] 날짜 포맷
import java.time.ZonedDateTime;              // [ADDED] 날짜 포맷
import java.time.format.DateTimeFormatter;   // [ADDED] 날짜 포맷
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService; // [ADDED] 비동기 분석
import java.util.concurrent.Executors;       // [ADDED] 비동기 분석
import java.util.regex.Matcher;              // [ADDED] dbResult 파싱
import java.util.regex.Pattern;              // [ADDED] dbResult 파싱


/**
 * ControlManager (캡처 전용)
 *
 * [REMAINED]
 * - 세션 등록/해제 및 라우팅 관리
 *
 * [ADDED]
 * - "CapRequest" 만 RC(android_rc)로 중계
 * - Cap(사진) 문서가 Firestore 저장된 뒤, OpenAI로 즉시 분석해서 RC에 결과 JSON 푸시
 *   결과 JSON 형식: { "Type":"CapAnalysis", "Datetime", "ID", "gcsurl", "result" }
 *
 * 주의:
 * - 실제 Firestore 저장은 IngestFirestoreService에서 처리 (변경 없음)
 * - 저장 완료 직후를 알기 위해 DirectionWebSocketEndpoint가
 *   dbResult를 이 클래스의 CapAnalyze(...)에 전달한다.  // [ADDED]
 */
public class ControlManager {

    // [UNCHANGED] JSON 유틸
    private static final Gson GSON = new com.google.gson.GsonBuilder()
        .disableHtmlEscaping()
        .create();

    // [UNCHANGED] 세션 관리
    private final Map<String, Session> active = new ConcurrentHashMap<>();

    // [ADDED] 분석용 스레드 풀 (응답 지연 방지)
    private final ExecutorService exec = Executors.newFixedThreadPool(2);

    // [ADDED] 포맷/타임존
    private static final ZoneId ZONE_SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter OUT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // [ADDED] DB 저장은 Ingest에 위임하기 위해 주입
    private IngestFirestoreService ingestService;
    public void setIngestService(IngestFirestoreService s) { this.ingestService = s; }   // [ADDED]


    // =========================
    // 세션 등록/해제 (UNCHANGED)
    // =========================
    public void registerClient(String clientId, Session session) {
        active.put(clientId, session);
        System.out.println("[CONNECT] " + clientId + " / active=" + active.keySet());
    }

    public void unregisterClient(String clientId) {
        active.remove(clientId);
        System.out.println("[DISCONNECT] " + clientId + " / active=" + active.keySet());
    }

    // [UNCHANGED] 세션 헬퍼
    private Session getRc() { return active.get("android_rc"); }
    private void sendTo(Session s, String msg) {
        if (s != null && s.isOpen()) {
            try { s.getRemote().sendString(msg); } catch (IOException e) { e.printStackTrace(); }
        }
    }
    private void sendToRc(String msg) { sendTo(getRc(), msg); }


    // =========================
    // 메시지 처리 (캡처만 유지)
    // =========================
    public String handle(Session session, String rawJson) {
        JsonObject obj = GSON.fromJson(rawJson, JsonObject.class);
        String type = obj.has("Type") ? obj.get("Type").getAsString() : "";

        // [MODIFIED] 오직 CapRequest 만 RC로 중계
        if ("CapRequest".equals(type)) {
            // 예: {"Type":"CapRequest","Datetime":"2025-08-25 21:45:12"}
            sendToRc(rawJson); // [ADDED] 그대로 RC(AP)에 전달
            return "[CapRequest] forwarded to RC";
        }

        // [REMOVED] Stt/Tts/Con/Jet 등 다른 제어/음성 처리
        return null; // 나머지는 DB 서비스가 처리하도록
    }

    // ===========================================================
    // [ADDED] DB 저장 직후 후처리 훅
    //  - DirectionWebSocketEndpoint 가 ingest 결과 문자열(dbResult)과 원본 JSON을 넘겨줌
    //  - Cap 저장인 경우에만 OpenAI 분석 수행 → RC로 결과 푸시
    // ===========================================================
    public void CapAnalyze(Session replyTo, String originalJson, String dbResult) {
        try {
            JsonObject obj = GSON.fromJson(originalJson, JsonObject.class);
            String type = obj.has("Type") ? obj.get("Type").getAsString() : "";
            if (!"Cap".equals(type)) return;  // 사진 저장일 때만 동작

            // Datetime (응답 JSON에 그대로 사용)
            String datetime = obj.has("Datetime") ? obj.get("Datetime").getAsString() : nowString();

            // dbResult 예: Firestore 저장 OK [Cap/Cap_YYYYMMDD_HHMMSS_n] (gcs=gs://...)
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
            final String finalId = capId;           // ← [FIX] id → capId
            final String finalGcsUri = gcsUri;

            exec.submit(() -> {
                try {
                    // 1) 다운로드용 서명 URL (기본 30분)
                    String[] bo = splitGsUri(finalGcsUri);
                    URL signed = GcsUrlUtil.createDownloadUrl(bo[0], bo[1], Duration.ofMinutes(30));
                    String signedUrl = signed.toExternalForm();

                    // 2) OpenAI 분석 호출
                    String resultText = callOpenAIAnalyze(signedUrl);

                    // 3) DB 저장은 ingest 서비스에 위임 (Type=Ai)
                    if (ingestService != null) {
                        JsonObject ai = new JsonObject();
                        ai.addProperty("Type", "Ai");
                        ai.addProperty("Datetime", finalDatetime);
                        ai.addProperty("CapId", finalId);
                        ai.addProperty("GcsUri", finalGcsUri); // 원본 gs://
                        ai.addProperty("Url", signedUrl);      // 서명 URL
                        ai.addProperty("Result", resultText);
                        try {
                            String saved = ingestService.handle(ai.toString()); // 저장
                            System.out.println("[AI] saved: " + saved);
                        } catch (Exception e) {
                            System.err.println("[AI] save failed: " + e.getMessage());
                        }
                    }

                    // 4) 결과 JSON: "보낸 세션"에게만 전송 (RC 전송 제거)
                    JsonObject resp = new JsonObject();
                    resp.addProperty("Type", "CapAnalysis");
                    resp.addProperty("Datetime", finalDatetime);
                    resp.addProperty("ID", finalId);
                    resp.addProperty("gcsurl", signedUrl);   // ← 소문자 키로 통일
                    resp.addProperty("result", resultText);  // ← 소문자 키로 통일

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
                    } else {
                        System.out.println("[AI] replyTo session closed or null; fail result not delivered. capId=" + finalId);
                    }
                }
            });
        } catch (Exception ignore) {
            ignore.printStackTrace();
        }
    }

        // =========================
        // [ADDED] OpenAI 호출부
        // =========================
        private String callOpenAIAnalyze(String imageUrl) throws Exception {
            String apiKey = System.getenv("OPENAI_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("OPENAI_API_KEY 환경변수가 필요합니다.");
            }

            // 시스템 프롬프트: 하수구 청소 로봇 관제 시나리오
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

    // =========================
    // [ADDED] 유틸
    // =========================
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
        // 예: Firestore 저장 OK [Cap/Cap_20250823_165000_1] ...
        Pattern p = Pattern.compile("\\[Cap/(Cap_\\d{8}_\\d{6}_\\d+)\\]");
        Matcher m = p.matcher(dbResult);
        return m.find() ? m.group(1) : null;
    }

    private static String tryExtractGcsUri(String dbResult) {
        // 예: ... (gcs=gs://bucket/photos/...)
        Pattern p = Pattern.compile("\\(gcs=(gs://[^)]+)\\)");
        Matcher m = p.matcher(dbResult);
        return m.find() ? m.group(1) : null;
    }
}
