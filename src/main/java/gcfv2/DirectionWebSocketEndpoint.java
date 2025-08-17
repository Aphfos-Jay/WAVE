package gcfv2;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;
import java.io.IOException;

@WebSocket
public class DirectionWebSocketEndpoint {

    private final ImageUploadHandler uploader = new ImageUploadHandler();

    @OnWebSocketConnect
    public void onConnect(Session session) {
        System.out.println("WebSocket connected: " + session.getRemoteAddress());
    }

    //이 골뱅이가 jetty가 쓰는 api라서 jetty가 자동호출하여 콜백함.
    @OnWebSocketMessage 
    public void onMessage(Session session, String message) throws IOException {
        //JSON이면 그대로 처리 아니라면 텍스트 처리
        if(message!=null && message.trim().startsWith("{")){
            uploader.handleText(session,message);
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