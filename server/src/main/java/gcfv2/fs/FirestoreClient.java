package gcfv2.fs;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;

/**
 * Firestore 싱글턴 클라이언트
 * - wave-db 데이터베이스로 고정 연결
 * - GOOGLE_CLOUD_PROJECT 값이 있을 때만 projectId 적용(조건부 안전)
 * - 초기화 시 진단용 로그 출력(projectId / databaseId / host / 에뮬레이터 여부)
 */
public final class FirestoreClient {

    private static volatile Firestore firestore;

    private FirestoreClient() {}

    public static Firestore get() {
        if (firestore == null) {
            synchronized (FirestoreClient.class) {
                if (firestore == null) {
                    // 실행 환경에서 프로젝트 ID 읽기 (Cloud Run이면 자동 세팅됨)
                    String projectId  = System.getenv("GOOGLE_CLOUD_PROJECT");
                    // 고정 DB 이름
                    String databaseId = "wave-db";

                    // Builder 사용: projectId가 있을 때만 설정(로컬 등 null 대비)
                    FirestoreOptions.Builder builder = FirestoreOptions.newBuilder();
                    if (projectId != null && !projectId.isBlank()) {
                        builder.setProjectId(projectId);
                    }
                    builder.setDatabaseId(databaseId);

                    FirestoreOptions opts = builder.build();

                    // 진단 로그: 실제로 어디(DB/프로젝트/호스트)로 붙는지 확인
                    //   - host가 emulator 주소면 에뮬레이터에 붙는 중
                    //   - FIRESTORE_EMULATOR_HOST 환경변수가 설정돼 있으면 에뮬레이터 사용 중
                    String emulatorHost = System.getenv("FIRESTORE_EMULATOR_HOST");
                    System.out.println(
                        "[Firestore DIAG] projectId=" + opts.getProjectId()
                        + ", databaseId=" + opts.getDatabaseId()
                        + ", host=" + opts.getHost()
                        + ", emulatorEnv=" + (emulatorHost == null ? "null" : emulatorHost)
                    );

                    firestore = opts.getService();
                }
            }
        }
        return firestore;
    }
}
