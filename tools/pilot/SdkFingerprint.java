import com.miec.recipient.api.OperationPayload;
import java.nio.charset.StandardCharsets;

/** Computes probe identities with the actual recipient SDK artifact, without reimplementing UTF8-V1. */
public final class SdkFingerprint {
    /** Reads the exact UTF-8 request from stdin; arguments contain only the trusted destination. */
    public static void main(String[] args) throws Exception {
        String body = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        System.out.print(new OperationPayload(args[0], "application/json", body).fingerprint());
    }
}
