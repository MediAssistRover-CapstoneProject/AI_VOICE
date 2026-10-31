package com.mediassistrover.backend.mapping;
import com.mediassistrover.backend.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.util.*;

/** Immutable content-addressed blobs. A rolled-back DB transaction may leave a safe orphan, never a receipt. */
@Component @RequiredArgsConstructor
public class MappingArtifactStorage {
    private final MappingProperties properties;
    private final MappingArchiveReader reader;
    private Path path(UUID artifact,String archiveHash) throws IOException {
        if(!archiveHash.matches("[a-f0-9]{64}"))throw new BusinessRuleException("Invalid stored hash");
        Path root=Path.of(properties.getStorageDir()).toAbsolutePath().normalize();Files.createDirectories(root);
        if(!root.toRealPath().equals(root))throw new BusinessRuleException("Artifact root must not contain symbolic links");
        Path dir=root.resolve(artifact.toString());Files.createDirectories(dir);
        if(!dir.toRealPath().equals(dir))throw new BusinessRuleException("Artifact directory must not be a symbolic link");
        return dir.resolve(archiveHash+".tar.gz");
    }
    public void store(UUID artifact,MappingArchiveReader.Verified verified) {
        Path temporary=null;
        try {
            Path target=path(artifact,verified.archiveHash());
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {read(artifact,verified.archiveHash());return;}
            temporary=Files.createTempFile(target.getParent(),"upload-",".tmp");
            try(var file=FileChannel.open(temporary,StandardOpenOption.WRITE)) {ByteBuffer data=ByteBuffer.wrap(verified.archive());while(data.hasRemaining())file.write(data);file.force(true);}
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);temporary=null;
            // Directory fsync is supported on Unix. Windows persists the file plus atomic rename.
            if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))try(var dir=FileChannel.open(target.getParent(),StandardOpenOption.READ)){dir.force(true);}
        } catch(IOException ex){throw new BusinessRuleException("Artifact durable storage failed");}
        finally {if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
    }
    public byte[] read(UUID artifact,String hash) {
        try {
            Path path=path(artifact,hash);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new BusinessRuleException("Artifact file missing");
            byte[] archive;try(var input=Files.newInputStream(path)){archive=reader.readBounded(input,properties.getMaxArchiveBytes());}
            if(!MappingArchiveReader.hash(archive).equals(hash))throw new BusinessRuleException("Stored archive integrity failed");return archive;
        } catch(IOException ex){throw new BusinessRuleException("Stored archive cannot be read");}
    }
}
