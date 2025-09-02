package gcfv2.ingest;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import gcfv2.fs.FirestoreClient;
import gcfv2.gcs.GcsUrlUtil;

import java.security.SecureRandom;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 요구사항 반영:
 *  - Type: Cap(사진), Stt(음성질의-하위호환), Ai(GPT 이미지결과), SttResult(음성결과)
 *  - Firestore 컬렉션: Cap, Ai, SttResult (Stt는 하위호환만; 새 플로우는 SttResult만 저장)
 *  - 문서ID: {컬렉션명}_{yyyyMMdd_HHmmss}_{증가}
 *  - Datetime은 Timestamp로 저장
 *  - 모든 JSON 원문은 'raw' 필드(Map)로 함께 저장
 *  - 사진 업로드: CapUploadInit → 서명URL 발급
 */
public class IngestFirestoreService {

    private static final Gson GSON = new com.google.gson.GsonBuilder()
        .disableHtmlEscaping()
        .create();

    private static final ZoneId ZONE_SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter INPUT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ID_FMT    = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"); // ID용
    private static final SecureRandom RND = new SecureRandom();
    private static volatile long lastSecond = -1;
    private static volatile int counter = 0;

    // ====== 외부 진입점 ======
    public String handle(String rawJson) throws Exception {
        JsonObject obj = GSON.fromJson(rawJson, JsonObject.class);
        String type = getAsText(obj, "Type");
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("JSON에 'Type' 필드가 필요합니다.");
        }

        switch (type) {
            case "Cap":
                return saveCap(obj, rawJson);

            // [ADDED/USED] 음성-답변 합본 결과 저장 (새 플로우 핵심)
            case "SttResult":
                return saveSttResult(obj, rawJson);

            case "CapUploadInit":
                return initCapUpload(obj); // 사진 업로드

            case "FindCap":
                return getCapAsset(obj); // 단건 사진 조회
                
            case "FindCaps":
                return findCapInRange(obj); // 범위 사진 조회

            case "Find":
                return genericRangeQuery(obj);  // 범용 조회

            case "Ai":
                return saveAi(obj, rawJson);

            default:
                throw new IllegalArgumentException("지원하지 않는 Type: " + type);
        }
    }

    // ====== Type별 저장 ======

    private String saveCap(JsonObject obj, String rawJson) throws Exception {
        Timestamp ts = toTimestamp(getRequiredText(obj, "Datetime"));
        Double lat = getAsDouble(obj, "Lang");
        Double lon = getAsDouble(obj, "Long");
        String ext = getRequiredText(obj, "확장자");
        String gcsUri = getRequiredText(obj, "GcsUri");

        Map<String, Object> doc = new HashMap<>();
        doc.put("type", "Cap");
        doc.put("datetime", ts);
        if (lat != null) doc.put("latitude", lat);
        if (lon != null) doc.put("longitude", lon);
        doc.put("ext", ext.toLowerCase());
        doc.put("gcsUri", gcsUri);
        doc.put("raw", GSON.fromJson(rawJson, Map.class));

        String collection = "Cap";
        String id = buildId(collection, ts);
        writeDoc(collection, id, doc);

        return ackSaved(collection, id) + " (gcs=" + gcsUri + ")";
    }

    /**
     * [ADDED] 최종 음성 결과 저장 (인바운드+아웃바운드 합본)
     * 입력 JSON 예:
     * {
     *   "Type":"SttResult",
     *   "Datetime":"yyyy-MM-dd HH:mm:ss",   // 아웃바운드 시각
     *   "Text":"질문: ...\n답변: ...",       // 합본 문자열
     *   "raw_inbound":  { ... STT 원문 ... },    // [선택] 합본 raw에 보관
     *   "raw_outbound": { ... 응답 JSON ... }     // [선택] 합본 raw에 보관
     * }
     */
    private String saveSttResult(JsonObject obj, String rawJson) throws Exception {
        Timestamp ts = toTimestamp(getRequiredText(obj, "Datetime"));
        String text  = getRequiredText(obj, "Text");

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", "SttResult");
        doc.put("datetime", ts);
        doc.put("text", text);
        doc.put("raw", GSON.fromJson(rawJson, Map.class)); // 합본 raw 그대로 저장

        String collection = "SttResult";
        String id = buildId(collection, ts);
        writeDoc(collection, id, doc);
        return ackSaved(collection, id);
    }

    // 사진 업로드용 사전 서명 URL
    private String initCapUpload(JsonObject obj) throws Exception {
        String ext = getRequiredText(obj, "확장자");
        String datetime = getRequiredText(obj, "Datetime");

        ZonedDateTime z = toZonedDateTime(toTimestamp(datetime));
        String datePart = z.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        String timePart = z.format(DateTimeFormatter.ofPattern("HHmmss"));
        int rnd = 1000 + RND.nextInt(9000);
        String objectName = String.format("photos/%s/%s_%04d.%s", datePart, timePart, rnd, ext.toLowerCase());

        String bucket = System.getenv("GCS_BUCKET");
        if (bucket == null || bucket.isBlank()) throw new IllegalStateException("GCS_BUCKET 환경변수 누락");

        String gcsUri = "gs://" + bucket + "/" + objectName;

        var url = gcfv2.gcs.GcsUrlUtil.createUploadUrl(bucket, objectName, Duration.ofMinutes(10),
                ext.equalsIgnoreCase("png") ? "image/png" : "image/jpeg");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("Type", "CapUploadInitResult");
        resp.put("UploadUrl", url.toExternalForm());
        resp.put("GcsUri", gcsUri);

        return GSON.toJson(resp);
    }

    private String getCapAsset(JsonObject obj) throws Exception {
        int ttlSec = 900;
        if (obj.has("TtlSec") && obj.get("TtlSec").isJsonPrimitive()) {
            try { ttlSec = obj.get("TtlSec").getAsInt(); } catch (Exception ignore) {}
        }

        String id = getAsText(obj, "Id");
        String gcsUri = getAsText(obj, "GcsUri");

        if (gcsUri == null || gcsUri.isBlank()) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Id 또는 GcsUri 중 하나는 필요합니다.");
            }
            DocumentSnapshot doc = FirestoreClient.get()
                    .collection("Cap").document(id).get().get();
            if (!doc.exists()) throw new IllegalArgumentException("문서 없음: " + id);
            Object got = doc.get("gcsUri");
            if (!(got instanceof String) || ((String) got).isBlank()) {
                throw new IllegalStateException("문서에 gcsUri 없음: " + id);
            }
            gcsUri = (String) got;
        }

        String[] bo = splitGsUri(gcsUri);
        var url = GcsUrlUtil.createDownloadUrl(bo[0], bo[1], Duration.ofSeconds(ttlSec));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("Type", "CapGetResult");
        resp.put("GcsUri", gcsUri);
        resp.put("Url", url.toExternalForm());

        return GSON.toJson(resp);
    }

    private String findCapInRange(JsonObject obj) throws Exception {
        String fromStr = getRequiredText(obj, "From");
        String toStr   = getRequiredText(obj, "To");
        Integer limit  = null;
        if (obj.has("Limit") && obj.get("Limit").isJsonPrimitive()) {
            try { limit = obj.get("Limit").getAsInt(); } catch (Exception ignore) {}
        }
        int ttlSec = 600;
        if (obj.has("TtlSec") && obj.get("TtlSec").isJsonPrimitive()) {
            try { ttlSec = obj.get("TtlSec").getAsInt(); } catch (Exception ignore) {}
        }

        Timestamp fromTs = toTimestamp(fromStr);
        Timestamp toTs   = toTimestamp(toStr);

        CollectionReference col = FirestoreClient.get().collection("Cap");
        Query q = col.whereGreaterThanOrEqualTo("datetime", fromTs)
                .whereLessThanOrEqualTo("datetime", toTs)
                .orderBy("datetime", Query.Direction.ASCENDING);
        if (limit != null && limit > 0) q = q.limit(limit);

        List<QueryDocumentSnapshot> docs = q.get().get().getDocuments();

        List<Map<String, Object>> items = new ArrayList<>();
        for (QueryDocumentSnapshot d : docs) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", d.getId());

            Object tsObj = d.get("datetime");
            if (tsObj instanceof com.google.cloud.Timestamp) {
                one.put("datetime", formatTimestamp((com.google.cloud.Timestamp) tsObj));
            }

            Object gcsObj = d.get("gcsUri");
            if (gcsObj instanceof String) {
                String gcsUri = (String) gcsObj;
                one.put("gcsUri", gcsUri);

                String[] bo = splitGsUri(gcsUri);
                var url = GcsUrlUtil.createDownloadUrl(bo[0], bo[1], Duration.ofSeconds(ttlSec));
                one.put("url", url.toExternalForm());
            }
            items.add(one);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("Type", "FindCapResult");
        resp.put("From", fromStr);
        resp.put("To", toStr);
        resp.put("Count", items.size());
        resp.put("Items", items);

        return GSON.toJson(resp);
    }

    /**
     * [MODIFIED] 범용 범위 조회(Find)
     * 허용: SttResult | Ai (필요 시 Stt 추가 가능)
     */
    private String genericRangeQuery(JsonObject obj) throws Exception {
        String collection = getRequiredText(obj, "Collection");
        if (!Set.of("SttResult","Ai").contains(collection)) {
            throw new IllegalArgumentException("Collection은 SttResult|Ai 중 하나여야 합니다.");
        }

        String fromStr = getRequiredText(obj, "From");
        String toStr   = getRequiredText(obj, "To");
        Timestamp fromTs = toTimestamp(fromStr);
        Timestamp toTs   = toTimestamp(toStr);

        CollectionReference col = FirestoreClient.get().collection(collection);
        Query q = col.whereGreaterThanOrEqualTo("datetime", fromTs)
                .whereLessThanOrEqualTo("datetime", toTs)
                .orderBy("datetime", Query.Direction.ASCENDING);

        List<QueryDocumentSnapshot> docs = q.get().get().getDocuments();

        List<Map<String, Object>> raws = new ArrayList<>();
        for (QueryDocumentSnapshot d : docs) {
            Object rawObj = d.get("raw");
            if (rawObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = (Map<String, Object>) rawObj;
                raws.add(raw);
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("Type", "FindResult");
        resp.put("Collection", collection);
        resp.put("From", fromStr);
        resp.put("To", toStr);
        resp.put("Results", raws);

        return GSON.toJson(resp);
    }

    // ====== AI 이미지 결과 저장 (기존 유지) ======
    private String saveAi(JsonObject obj, String rawJson) throws Exception {
        Timestamp ts = toTimestamp(getRequiredText(obj, "Datetime"));
        String capId = getAsText(obj, "CapId");
        if (capId == null || capId.isBlank()) capId = getRequiredText(obj, "ID");
        String result = getRequiredText(obj, "Result");
        String gcsUri = getAsText(obj, "GcsUri");
        String url    = getAsText(obj, "Url");

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", "Ai");
        doc.put("datetime", ts);
        doc.put("capId", capId);
        if (gcsUri != null && !gcsUri.isBlank()) doc.put("gcsUri", gcsUri);
        if (url != null && !url.isBlank())       doc.put("url", url);
        doc.put("result", result);
        doc.put("raw", GSON.fromJson(rawJson, Map.class));

        String collection = "Ai";
        String id = "Ai_" + capId;
        writeDoc(collection, id, doc);
        return ackSaved(collection, id);
    }

    // ====== 공통 유틸 ======
    private void writeDoc(String collection, String id, Map<String, Object> doc) throws Exception {
        Firestore db = FirestoreClient.get();
        db.collection(collection).document(id).set(doc).get();
    }

    private String ackSaved(String collection, String id) {
        return "Firestore 저장 완료 [" + collection + "/" + id + "]";
    }

    private static String getRequiredText(JsonObject obj, String key) {
        String v = getAsText(obj, key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("필수 필드 누락: " + key);
        }
        return v;
    }
    private static String getAsText(JsonObject obj, String key) {
        return (obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsString() : null;
    }
    private static Double getAsDouble(JsonObject obj, String key) {
        try { return (obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsDouble() : null; }
        catch (Exception e) { return null; }
    }
    private static Timestamp toTimestamp(String datetimeStr) {
        LocalDateTime ldt = LocalDateTime.parse(datetimeStr, INPUT_FMT);
        ZonedDateTime zdt = ldt.atZone(ZONE_SEOUL);
        return Timestamp.ofTimeSecondsAndNanos(zdt.toEpochSecond(), zdt.getNano());
    }
    private static ZonedDateTime toZonedDateTime(Timestamp ts) {
        Instant inst = Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
        return inst.atZone(ZONE_SEOUL);
    }
    private static synchronized String buildId(String collection, Timestamp ts) {
        Instant inst = Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
        ZonedDateTime z = inst.atZone(ZONE_SEOUL);
        long currentSecond = ts.getSeconds();
        if (currentSecond != lastSecond) { lastSecond = currentSecond; counter = 0; }
        counter++;
        String hhmmss = z.format(ID_FMT);
        return collection + "_" + hhmmss + "_" + counter;
    }
    private String formatTimestamp(com.google.cloud.Timestamp ts) {
        Instant inst = Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
        ZonedDateTime z = inst.atZone(ZONE_SEOUL);
        return z.format(INPUT_FMT);
    }
    private static String[] splitGsUri(String gcsUri) {
        String noPrefix = gcsUri.replaceFirst("^gs://", "");
        int idx = noPrefix.indexOf('/');
        if (idx < 0) throw new IllegalArgumentException("잘못된 GCS URI: " + gcsUri);
        return new String[]{ noPrefix.substring(0, idx), noPrefix.substring(idx + 1) };
    }
}
