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
 *  - Type: Con(조종), Cap(사진), Jet(물 분사), Stt, Tts
 *  - Firestore 컬렉션: controls, photos, jets, stt, tts
 *  - 문서ID: {컬렉션명}_{yyyyMMdd_HHmmss}_{난수4}
 *  - Datetime은 Timestamp로 저장(범위 조회 정확성)
 *  - 모든 JSON 원문을 'raw' 필드(Map)로 함께 저장  
 *  - 사진(Cap)은 ImageBase64를 GCS에 저장, GCS URI를 Firestore에 기록 
 *  - 사진 범위 조회: CapQuery({From, To}) → 배열로 반환
 */
public class IngestFirestoreService {

    //private static final Gson GSON = new Gson();
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

            case "Stt":
                return saveStt(obj, rawJson);      

            case "Tts":
                return saveTts(obj, rawJson);      

            case "CapUploadInit":
                return initCapUpload(obj); // 사진 업로드

            case "FindCap":
                return getCapAsset(obj); // 단건 사진 조회
                    
            case "FindCaps":  
                return findCapInRange(obj); // 범위 사진 조회

            case "Find":                       
                return genericRangeQuery(obj);  // 범용 조회(컬렉션=Type명)

            case "Ai":              
                return saveAi(obj, rawJson);

            default:
                throw new IllegalArgumentException("지원하지 않는 Type: " + type);
        }
    }

    // ====== Type별 저장 ======

    /**
     * 컬렉션: Cap
     * - 원본 이미지를 GCS에 저장, Firestore에는 GCS URI/랑 메타데이터만 저장  
     * - 원문(raw)도 함께 저장                                   
     */
    private String saveCap(JsonObject obj, String rawJson) throws Exception {
        Timestamp ts = toTimestamp(getRequiredText(obj, "Datetime"));
        Double lat = getAsDouble(obj, "Lang");
        Double lon = getAsDouble(obj, "Long");
        String ext = getRequiredText(obj, "확장자");
        String gcsUri = getRequiredText(obj, "GcsUri");  // GcsUri 필수

        Map<String, Object> doc = new HashMap<>();
        doc.put("type", "Cap");
        doc.put("datetime", ts);
        if (lat != null) doc.put("latitude", lat);
        if (lon != null) doc.put("longitude", lon);
        doc.put("ext", ext.toLowerCase());
        doc.put("gcsUri", gcsUri);

        // 원문 raw 저장
        doc.put("raw", GSON.fromJson(rawJson, Map.class));

        String collection = "Cap";
        String id = buildId(collection, ts);
        writeDoc(collection, id, doc);

        return ackSaved(collection, id) + " (gcs=" + gcsUri + ")";
    }

    /**
     * Stt: {Type:Stt, Datetime:"...", Voice:"..."}
     */
    private String saveStt(JsonObject obj, String rawJson) throws Exception { 
        Timestamp ts = toTimestamp(getRequiredText(obj, "Datetime"));
        String voice = getRequiredText(obj, "Voice");

        Map<String, Object> doc = new HashMap<>();
        doc.put("type", "Stt");
        doc.put("datetime", ts);
        doc.put("voice", voice);
        doc.put("raw", GSON.fromJson(rawJson, Map.class)); 

        String collection = "Stt";
        String id = buildId(collection, ts);
        writeDoc(collection, id, doc);
        return ackSaved(collection, id);
    }

    /**
     * Tts: {Type:Tts, Datetime:"...", Text:"..."}
     */
    private String saveTts(JsonObject obj, String rawJson) throws Exception { 
        Timestamp ts = toTimestamp(getRequiredText(obj, "Datetime"));
        String text = getRequiredText(obj, "Text");

        Map<String, Object> doc = new HashMap<>();
        doc.put("type", "Tts");
        doc.put("datetime", ts);
        doc.put("text", text);
        doc.put("raw", GSON.fromJson(rawJson, Map.class)); 

        String collection = "Tts";
        String id = buildId(collection, ts);
        writeDoc(collection, id, doc);
        return ackSaved(collection, id);
    }

    /*
     * 사진 업로드를 위한 함수
     */
    private String initCapUpload(JsonObject obj) throws Exception {
        String ext = getRequiredText(obj, "확장자");
        String datetime = getRequiredText(obj, "Datetime");

        // GCS 객체명 생성
        ZonedDateTime z = toZonedDateTime(toTimestamp(datetime));
        String datePart = z.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        String timePart = z.format(DateTimeFormatter.ofPattern("HHmmss"));
        int rnd = 1000 + RND.nextInt(9000);
        String objectName = String.format("photos/%s/%s_%04d.%s", datePart, timePart, rnd, ext.toLowerCase());

        String bucket = System.getenv("GCS_BUCKET");
        if (bucket == null || bucket.isBlank()) throw new IllegalStateException("GCS_BUCKET 환경변수 누락");

        String gcsUri = "gs://" + bucket + "/" + objectName;

        // 업로드용 서명 URL 생성 (유효기간 10분)
        var url = gcfv2.gcs.GcsUrlUtil.createUploadUrl(bucket, objectName, Duration.ofMinutes(10),
                        ext.equalsIgnoreCase("png") ? "image/png" : "image/jpeg");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("Type", "CapUploadInitResult");
        resp.put("UploadUrl", url.toExternalForm());
        resp.put("GcsUri", gcsUri);

        return GSON.toJson(resp);
    }

    // [ADDED] 단일 사진 재전달 (항상 URL만 반환)
    private String getCapAsset(JsonObject obj) throws Exception {
        // 요청 예:
        // { "Type":"CapGet", "Id":"Cap_20250819_213422_5678", "TtlSec":600 }
        // 또는 { "Type":"CapGet", "GcsUri":"gs://bucket/photos/...", "TtlSec":600 }

        int ttlSec = 900; // 기본 15분
        if (obj.has("TtlSec") && obj.get("TtlSec").isJsonPrimitive()) {
            try { ttlSec = obj.get("TtlSec").getAsInt(); } catch (Exception ignore) {}
        }

        String id = getAsText(obj, "Id");
        String gcsUri = getAsText(obj, "GcsUri");

        // gcsUri 우선, 없으면 Id로 Firestore에서 조회
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

        String[] bo = splitGsUri(gcsUri); // [bucket, object]
        var url = GcsUrlUtil.createDownloadUrl(bo[0], bo[1], Duration.ofSeconds(ttlSec));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("Type", "CapGetResult");
        resp.put("GcsUri", gcsUri);
        resp.put("Url", url.toExternalForm());

        return GSON.toJson(resp);
    }

        // [ADDED] 범위 내 여러 장 사진 조회 (항상 URL만 반환)
    private String findCapInRange(JsonObject obj) throws Exception {
        // 요청 예:
        // {
        //   "Type":"FindCap",
        //   "From":"2025-08-19 21:34:00",
        //   "To":"2025-08-19 21:40:00",
        //   "Limit": 100,      // 선택
        //   "TtlSec": 600      // 선택
        // }

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

            // datetime → 문자열(yyyy-MM-dd HH:mm:ss)
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

    // ========= [ADDED] 범용 범위 조회(Find) =========

    /**
     * { "Type":"Find", "Collection":"Cap|Stt|Tts", "From":"...", "To":"..." }
     * - 해당 컬렉션에서 datetime 범위로 문서 조회
     * - 각 문서의 raw(Map)만 모아 배열로 반환
     * - 응답은 wrapper 오브젝트로 감싸서 Type/Collection/From/To 포함
     */
    private String genericRangeQuery(JsonObject obj) throws Exception { 
        String collection = getRequiredText(obj, "Collection");
        if (!Set.of("Stt","Tts","Ai").contains(collection)) { 
            throw new IllegalArgumentException("Collection은 Stt|Tts|Ai 중 하나여야 합니다.");
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

    // [ADDED] ChatGPT 결과 저장: Type=Ai
    private String saveAi(JsonObject obj, String rawJson) throws Exception {
        // 필수 필드
        Timestamp ts = toTimestamp(getRequiredText(obj, "Datetime"));
        // Cap 문서 ID는 CapId 또는 ID로 받도록 유연하게 처리
        String capId = getAsText(obj, "CapId");
        if (capId == null || capId.isBlank()) capId = getRequiredText(obj, "ID");
        String result = getRequiredText(obj, "Result");

        // 선택 필드
        String gcsUri = getAsText(obj, "GcsUri");   // 원본 gs://
        String url    = getAsText(obj, "Url");      // 서명 URL

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", "Ai");
        doc.put("datetime", ts);
        doc.put("capId", capId);
        if (gcsUri != null && !gcsUri.isBlank()) doc.put("gcsUri", gcsUri);
        if (url != null && !url.isBlank())       doc.put("url", url);
        doc.put("result", result);
        doc.put("raw", GSON.fromJson(rawJson, Map.class)); // 원문 보관

        String collection = "Ai";
        // Ai 문서는 Cap 문서와 1:1로 매핑되도록 ID를 고정
        String id = "Ai_" + capId;                          // 예: Ai_Cap_20250825_214512_1
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
        try {
            return (obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsDouble() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Timestamp toTimestamp(String datetimeStr) {
        LocalDateTime ldt = LocalDateTime.parse(datetimeStr, INPUT_FMT);
        ZonedDateTime zdt = ldt.atZone(ZONE_SEOUL);
        return Timestamp.ofTimeSecondsAndNanos(zdt.toEpochSecond(), zdt.getNano());
    }

    private static ZonedDateTime toZonedDateTime(Timestamp ts) { // GCS 이름 생성용
        Instant inst = Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
        return inst.atZone(ZONE_SEOUL);
    }

    private static synchronized String buildId(String collection, Timestamp ts) {
        Instant inst = Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
        ZonedDateTime z = inst.atZone(ZONE_SEOUL);

        long currentSecond = ts.getSeconds();

        // 초가 바뀌면 카운터 초기화
        if (currentSecond != lastSecond) {
            lastSecond = currentSecond;
            counter = 0;
        }
        counter++;

        String hhmmss = z.format(ID_FMT); // yyyyMMdd_HHmmss
        return collection + "_" + hhmmss + "_" + counter;
    }


    // Timestamp -> "yyyy-MM-dd HH:mm:ss"
    private String formatTimestamp(com.google.cloud.Timestamp ts) {
        Instant inst = Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
        ZonedDateTime z = inst.atZone(ZONE_SEOUL);
        return z.format(INPUT_FMT);
    }

    // "gs://bucket/path/to/object" -> [bucket, object]
    private static String[] splitGsUri(String gcsUri) {
        String noPrefix = gcsUri.replaceFirst("^gs://", "");
        int idx = noPrefix.indexOf('/');
        if (idx < 0) throw new IllegalArgumentException("잘못된 GCS URI: " + gcsUri);
        return new String[]{ noPrefix.substring(0, idx), noPrefix.substring(idx + 1) };
    }

}


