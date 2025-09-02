package gcfv2;

import com.google.gson.Gson;                   // [ADDED]
import com.google.gson.JsonObject;             // [ADDED]
import gcfv2.control.ControlManager;
import gcfv2.ingest.IngestFirestoreService;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;

import java.io.IOException;
import java.util.List;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

/**
 * DirectionWebSocketEndpoint
 * - STT는 DB에 개별 저장하지 않고, GPT 응답 시점에 SttResult(합본) 1건만 저장
 */
@WebSocket
public class DirectionWebSocketEndpoint {

    private static final Gson GSON = new Gson();                 // [ADDED]

    private final IngestFirestoreService ingestService = new IngestFirestoreService();
    private final ControlManager controlManager = new ControlManager();
    private static final ScheduledExecutorService KA_EXEC = Executors.newScheduledThreadPool(1);
    private static final ConcurrentHashMap<Session, ScheduledFuture<?>> KA_TASKS = new ConcurrentHashMap<>();
    public DirectionWebSocketEndpoint() {
        controlManager.setIngestService(ingestService);
    }

    @OnWebSocketConnect
    public void onConnect(Session session) {
        try {
            String clientId = session.getUpgradeRequest()
                    .getParameterMap()
                    .getOrDefault("id", List.of("unknown"))
                    .get(0);

            controlManager.registerClient(clientId, session);
            session.setIdleTimeout(Duration.ofMinutes(30));

            ScheduledFuture<?> task = KA_EXEC.scheduleAtFixedRate(() -> {
                try {
                    if (session.isOpen()) {
                        session.getRemote().sendPing(ByteBuffer.wrap(new byte[0]));
                    }
                } catch (Throwable t) {}
            }, 20, 20, TimeUnit.SECONDS);
            KA_TASKS.put(session, task);

            System.out.println("[CONNECT] clientId=" + clientId +
                    " / addr=" + session.getRemoteAddress());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @OnWebSocketMessage
    public void onMessage(Session session, String message) throws IOException {
        if (message != null && message.trim().startsWith("{")) {
            try {
                // [ADDED] Type 미리 파싱
                JsonObject obj = GSON.fromJson(message, JsonObject.class);
                String type = obj.has("Type") ? obj.get("Type").getAsString() : "";

                // [MODIFIED] STt는 개별 DB 저장하지 않고 즉시 분석만
                if ("Stt".equals(type)) {
                    controlManager.SttAnalyze(session, message);
                    return; // 여기서 종료 (이후 SttResult가 같은 세션으로 전송/저장됨)
                }

                // 1) 캡처 제어만 ControlManager가 먼저 처리
                String result = controlManager.handle(session, message);
                if (result != null) {
                    session.getRemote().sendString(result);
                    return;
                }

                // 2) 나머지 JSON은 DB/스토리지 처리
                String dbResult = ingestService.handle(message);
                session.getRemote().sendString(dbResult);

                // 3) Cap 저장 직후 GPT 분석 (필요 시)
                controlManager.CapAnalyze(session, message, dbResult);

                // [REMOVED] SttAnalyze는 위 STt 분기에서만 호출

            } catch (Exception e) {
                session.getRemote().sendString("처리 실패: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    @OnWebSocketClose
    public void onClose(Session session, int statusCode, String reason) {
        try {
            ScheduledFuture<?> f = KA_TASKS.remove(session);
            if (f != null) f.cancel(true);

            String clientId = session.getUpgradeRequest()
                    .getParameterMap()
                    .getOrDefault("id", List.of("unknown"))
                    .get(0);

            controlManager.unregisterClient(clientId);

            System.out.println("[CLOSE] clientId=" + clientId +
                    " code=" + statusCode +
                    " reason=" + reason);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @OnWebSocketError
    public void onError(Session session, Throwable cause) {
        System.err.println("[ERROR] " + cause.getMessage());
        ScheduledFuture<?> f = KA_TASKS.remove(session);
        if (f != null) f.cancel(true);
        cause.printStackTrace();
    }
}
