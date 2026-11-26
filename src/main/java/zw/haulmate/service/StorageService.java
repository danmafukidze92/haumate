package zw.haulmate.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import zw.haulmate.config.AppProps;
import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import java.util.UUID;

/** Local disk storage (mounted volume shared with the worker). Swap for S3/MinIO in production. */
@Service
public class StorageService {
    private static final Set<String> ALLOWED = Set.of("image/jpeg", "image/png", "image/webp", "application/pdf");
    private final Path root;

    public StorageService(AppProps props) throws IOException {
        this.root = Paths.get(props.storageDir()).toAbsolutePath();
        Files.createDirectories(root);
    }

    public String save(MultipartFile f, String folder) {
        if (f == null || f.isEmpty()) throw ApiException.bad("File is required");
        if (!ALLOWED.contains(String.valueOf(f.getContentType()))) throw ApiException.bad("Only JPEG, PNG, WEBP or PDF allowed");
        String ext = switch (String.valueOf(f.getContentType())) {
            case "image/png" -> ".png"; case "image/webp" -> ".webp"; case "application/pdf" -> ".pdf"; default -> ".jpg"; };
        try {
            Path dir = root.resolve(folder);
            Files.createDirectories(dir);
            Path dest = dir.resolve(UUID.randomUUID() + ext);
            f.transferTo(dest);
            return root.relativize(dest).toString();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    public Path resolve(String relative) {
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root)) throw ApiException.forbidden("Bad path");
        return p;
    }
}
