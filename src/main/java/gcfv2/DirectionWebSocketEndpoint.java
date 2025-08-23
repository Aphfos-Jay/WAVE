package gcfv2;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;
import java.io.IOException;

import gcfv2.ingest.IngestFirestoreService; // 클래스명 주의(앞에 Json 제거 반영)

/**
 * WebSocket 텍스트 수신:
 * - JSON: IngestFirestoreService.handle() 호출 → 저장/조회 수행
 * - 일반 텍스트: 기존 방향 명령 응답
 */
@WebSocket
public class DirectionWebSocketEndpoint {

    private final IngestFirestoreService ingestService = new IngestFirestoreService(); // 이름 확인

    @OnWebSocketConnect
    public void onConnect(Session session) {
        System.out.println("WebSocket connected: " + session.getRemoteAddress());
    }

    @OnWebSocketMessage
    public void onMessage(Session session, String message) throws IOException {
        if (message != null && message.trim().startsWith("{")) {
            try {
                String result = ingestService.handle(message);
                session.getRemote().sendString(result);
            } catch (Exception e) {
                session.getRemote().sendString("Firestore/GCS 처리 실패: " + e.getMessage());
                e.printStackTrace();
            }
            return;
        }

        String result;
        switch (message == null ? "" : message.toUpperCase()) {
            case "UP":    result = "전진합니다"; break;
            case "DOWN":  result = "후진합니다"; break;
            case "LEFT":  result = "좌회전합니다"; break;
            case "RIGHT": result = "우회전합니다"; break;
            default:      result = "알 수 없는 명령입니다";
        }
        session.getRemote().sendString(result);
    }

    @OnWebSocketClose
    public void onClose(Session session, int statusCode, String reason) {
        System.out.println("WebSocket closed: " + statusCode + " / " + reason);
    }

    @OnWebSocketError
    public void onError(Session session, Throwable cause) {
        System.err.println("WebSocket error: " + cause.getMessage());
    }
}
