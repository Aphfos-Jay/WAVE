package gcfv2;

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
 * - Jetty 네이티브 WebSocket API(@WebSocket, @OnWebSocketXxx) 사용
 * - 중요: Jetty의 Session.getUpgradeRequest()에는 setAttribute/getAttribute가 없음
 *   -> clientId는 매번 Query Param에서 읽어 처리
 */
@WebSocket
public class DirectionWebSocketEndpoint {

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
            // 1) 쿼리스트링에서 clientId를 직접 읽는다.
            //    예: wss://host/direction?id=android_ctrl
            String clientId = session.getUpgradeRequest()
                    .getParameterMap()
                    .getOrDefault("id", List.of("unknown"))
                    .get(0);

            // 2) ControlManager에 등록 (세션 보관/루팅용)
            controlManager.registerClient(clientId, session);

            // [ADDED] 세션 유휴 타임아웃 30분
            session.setIdleTimeout(Duration.ofMinutes(30));

            // [ADDED] 20초마다 WebSocket PING 전송(유휴 방지)
            ScheduledFuture<?> task = KA_EXEC.scheduleAtFixedRate(() -> {
                try {
                    if (session.isOpen()) {
                        session.getRemote().sendPing(ByteBuffer.wrap(new byte[0]));
                    }
                } catch (Throwable t) {
                    // 전송 실패해도 다음 턴에서 재시도. 끊기면 onClose에서 취소됨.
                }
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
                // 1) 캡처 제어만 ControlManager가 먼저 처리 (그 외는 null 반환)
                String result = controlManager.handle(session, message);
                if (result != null) {
                    session.getRemote().sendString(result);
                    // 캡처 요청은 여기서 끝. 이후 RC가 Cap/CapUploadInit 등을 다시 보냄.
                    return;
                }

                // 2) DB/스토리지 처리 (변경 없음)
                String dbResult = ingestService.handle(message);
                session.getRemote().sendString(dbResult);

                // 3) [ADDED] Cap 저장 직후 OpenAI 분석 트리거 (필요 시에만 내부에서 동작)
                controlManager.CapAnalyze(session, message, dbResult);


            } catch (Exception e) {
                session.getRemote().sendString("처리 실패: " + e.getMessage());
                e.printStackTrace();
            }
        } 
    }

    @OnWebSocketClose
    public void onClose(Session session, int statusCode, String reason) {
        try {

            // [ADDED] PING 스케줄러 정리
            ScheduledFuture<?> f = KA_TASKS.remove(session);
            if (f != null) f.cancel(true);

            // onClose에서도 upgradeRequest는 접근 가능하므로 동일 방식으로 clientId를 재구성
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
        // [ADDED] 에러 시도중에 열린 세션 정리
        ScheduledFuture<?> f = KA_TASKS.remove(session);
        if (f != null) f.cancel(true);
        cause.printStackTrace();
    }
}
