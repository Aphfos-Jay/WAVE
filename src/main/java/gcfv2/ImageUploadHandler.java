package gcfv2;

import com.google.cloud.storage.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.eclipse.jetty.websocket.api.Session;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

parseRequestJson(message) → JSON 파싱

validateContentType(req.contentType) → jpg/png 허용만 체크

decodeBase64(req.dataBase64) → 이미지 바이트로 변환

validateSize(bytes, 5MB) → 크기 제한 검사

validateMagicBytes(bytes, req.contentType) → 실제 파일 시그니처 검사

ensureUniqueName(storage, bucket, req.filename, ext) → 중복 시 이름 증가

uploadToGcs(storage, bucket, objectName, req.contentType, bytes) → GCS 업로드

buildSuccessJson(...) 또는 buildErrorJson(...) → 결과 JSON 만들어서 회신

//JSON 형식지정
class UploadRequest{
    String filename;
    String contentType;
    String dataBase64;
}


public class ImageUploadHandler {

    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final Gson GSON = new Gson();

    private final Storage storage = StorageOptions.getDefaultInstance().getService();
    private final String bucket = System.getenv().getOrDefault("GCS_BUCKET","my-robot-image");

    




    
}
