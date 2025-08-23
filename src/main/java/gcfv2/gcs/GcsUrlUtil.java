package gcfv2.gcs;

import com.google.cloud.storage.*;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * GCS V4 서명 URL 발급 유틸
 * - 클라이언트가 직접 HTTP PUT으로 업로드/다운로드 가능
 */
public final class GcsUrlUtil {

    private static volatile Storage storage;

    private static Storage get() {
        if (storage == null) {
            synchronized (GcsUrlUtil.class) {
                if (storage == null) {
                    storage = StorageOptions.getDefaultInstance().getService();
                }
            }
        }
        return storage;
    }

    /** 업로드용 V4 서명 URL 생성 (PUT) */
    public static URL createUploadUrl(String bucket, String objectName, Duration ttl, String contentType) {
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, objectName))
                                    .setContentType(contentType)
                                    .build();
        return get().signUrl(
                blobInfo,
                ttl.getSeconds(),
                TimeUnit.SECONDS,
                Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
                Storage.SignUrlOption.withV4Signature()
        );
    }

    /** 다운로드용 V4 서명 URL 생성 (GET) */
    public static URL createDownloadUrl(String bucket, String objectName, Duration ttl) {
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, objectName)).build();
        return get().signUrl(
                blobInfo,
                ttl.getSeconds(),
                TimeUnit.SECONDS,
                Storage.SignUrlOption.withV4Signature()
        );
    }
}
