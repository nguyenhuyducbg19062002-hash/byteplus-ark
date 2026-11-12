package ark.integration.byteplus;

import java.nio.file.Path;

/** A generated file saved locally plus the original API URL it was downloaded from (valid 24h). */
public record SavedOutput(Path file, String sourceUrl) {}
