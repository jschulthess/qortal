package org.qortal.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.utils.Base58;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


public class DataFileChunk extends DataFile {

    private static final Logger LOGGER = LogManager.getLogger(DataFileChunk.class);

    public DataFileChunk() {
    }

    public DataFileChunk(String filePath) {
        super(filePath);
    }

    public DataFileChunk(File file) {
        super(file);
    }

    public DataFileChunk(byte[] fileContent) {
        super(fileContent);
    }

    public static DataFileChunk fromBase58Digest(String base58Digest) {
        String filePath = DataFile.getOutputFilePath(base58Digest, false);
        return new DataFileChunk(filePath);
    }

    public static DataFileChunk fromDigest(byte[] digest) {
        return DataFileChunk.fromBase58Digest(Base58.encode(digest));
    }

    @Override
    public ValidationResult isValid() {
        // DataChunk validation applies here too
        ValidationResult superclassValidationResult = super.isValid();
        if (superclassValidationResult != ValidationResult.OK) {
            return superclassValidationResult;
        }

        Path path = Paths.get(this.filePath);
        try {
            // Validate the file size (chunks have stricter limits)
            long fileSize = Files.size(path);
            if (fileSize > CHUNK_SIZE) {
                LOGGER.error(String.format("DataFileChunk is too large: %d bytes (max chunk size: %d bytes)", fileSize, CHUNK_SIZE));
                return ValidationResult.FILE_TOO_LARGE;
            }

        } catch (IOException e) {
            return ValidationResult.FILE_NOT_FOUND;
        }

        return ValidationResult.OK;
    }
}
