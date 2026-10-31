package com.mediassistrover.backend;
import com.fasterxml.jackson.databind.*;
import com.mediassistrover.backend.mapping.*;
import com.mediassistrover.backend.validation.MappingArtifactContractValidator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MappingArchiveReaderTest {
    ObjectMapper json=new ObjectMapper();MappingProperties config=new MappingProperties();MappingArchiveReader reader=new MappingArchiveReader(json,new MappingArtifactContractValidator(),config);
    UUID id=UUID.randomUUID();com.fasterxml.jackson.databind.node.ObjectNode manifest=MappingTestArchive.manifest(json,id,MappingTestArchive.YAML,MappingTestArchive.PGM);
    MappingArchiveReader.Verified verify(byte[] archive){return reader.verify(new ByteArrayInputStream(archive),id,manifest.path("asset_hash").asText(),MappingArchiveReader.hash(archive));}
    @Test void readsBinaryPixelsIncludingWhitespaceWithoutNormalizingRawAssetHash(){var result=verify(MappingTestArchive.archive(json,manifest));assertThat(result.manifest()).isEqualTo(manifest);assertThat(result.assetHash()).isEqualTo(manifest.path("asset_hash").asText());}
    @Test void rejectsBothHashMismatches(){byte[] a=MappingTestArchive.archive(json,manifest);assertThatThrownBy(()->reader.verify(new ByteArrayInputStream(a),id,"b".repeat(64),MappingArchiveReader.hash(a))).hasMessageContaining("Asset SHA");assertThatThrownBy(()->reader.verify(new ByteArrayInputStream(a),id,manifest.path("asset_hash").asText(),"b".repeat(64))).hasMessageContaining("Archive SHA");}
    @Test void rejectsForeignArtifactIdentity(){byte[] a=MappingTestArchive.archive(json,manifest);assertThatThrownBy(()->reader.verify(new ByteArrayInputStream(a),UUID.randomUUID(),manifest.path("asset_hash").asText(),MappingArchiveReader.hash(a))).hasMessageContaining("artifact/hash");}
    @Test void rejectsPathsAndLinks(){for(String path:List.of("../map.yaml","/map.yaml","./map.yaml"))assertThatThrownBy(()->verify(MappingTestArchive.archive(MappingTestArchive.YAML,MappingTestArchive.PGM,MappingTestArchive.bytes(json,manifest),path,'0',false))).hasMessageContaining("regular root");assertThatThrownBy(()->verify(MappingTestArchive.archive(MappingTestArchive.YAML,MappingTestArchive.PGM,MappingTestArchive.bytes(json,manifest),"map.yaml",'2',false))).hasMessageContaining("regular root");}
    @Test void rejectsDuplicateArchiveEntry(){assertThatThrownBy(()->verify(MappingTestArchive.archive(MappingTestArchive.YAML,MappingTestArchive.PGM,MappingTestArchive.bytes(json,manifest),"map.yaml",'0',true))).hasMessageContaining("unique");}
    @Test void rejectsYamlImagePathAndObjectTags(){for(String text:List.of(new String(MappingTestArchive.YAML).replace("map.pgm","../map.pgm"),"!!java.lang.ProcessBuilder {}")){byte[] yaml=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);manifest=MappingTestArchive.manifest(json,id,yaml,MappingTestArchive.PGM);assertThatThrownBy(()->verify(MappingTestArchive.archive(yaml,MappingTestArchive.PGM,MappingTestArchive.bytes(json,manifest),"map.yaml",'0',false))).isInstanceOf(com.mediassistrover.backend.exception.BusinessRuleException.class);}}
    @Test void rejectsDuplicateYamlKey(){byte[] yaml=(new String(MappingTestArchive.YAML)+"resolution: 0.10\n").getBytes();manifest=MappingTestArchive.manifest(json,id,yaml,MappingTestArchive.PGM);assertThatThrownBy(()->verify(MappingTestArchive.archive(yaml,MappingTestArchive.PGM,MappingTestArchive.bytes(json,manifest),"map.yaml",'0',false))).hasMessageContaining("Invalid mapping archive");}
    @Test void rejectsPixelAndMetricMismatch(){manifest.put("width_px",3);assertThatThrownBy(()->verify(MappingTestArchive.archive(json,manifest))).hasMessageContaining("pixel dimensions");manifest.put("width_px",2).put("resolution_m_per_pixel",.10);assertThatThrownBy(()->verify(MappingTestArchive.archive(json,manifest))).hasMessageContaining("YAML mismatch");}
    @Test void rejectsOversizedCompressedAndUncompressedBodies(){byte[] a=MappingTestArchive.archive(json,manifest);config.setMaxArchiveBytes(a.length-1);assertThatThrownBy(()->verify(a)).hasMessageContaining("byte limit");config.setMaxArchiveBytes(16000);config.setMaxUncompressedBytes(1024);assertThatThrownBy(()->verify(a)).hasMessageContaining("byte limit");}
    @Test void rejectsTruncatedAndExcessPgmRaster(){byte[] pgm=Arrays.copyOf(MappingTestArchive.PGM,MappingTestArchive.PGM.length-1);manifest=MappingTestArchive.manifest(json,id,MappingTestArchive.YAML,pgm);assertThatThrownBy(()->verify(MappingTestArchive.archive(MappingTestArchive.YAML,pgm,MappingTestArchive.bytes(json,manifest),"map.yaml",'0',false))).hasMessageContaining("raster size");}
    @Test void rejectsExcessManifestFields(){manifest.put("shell","run");assertThatThrownBy(()->verify(MappingTestArchive.archive(json,manifest))).hasMessageContaining("shell is forbidden");}
    @Test void durableBlobRetryChecksContentAndMissingFile(@TempDir Path directory) throws Exception {
        config.setStorageDir(directory.toString());var storage=new MappingArtifactStorage(config,reader);var verified=verify(MappingTestArchive.archive(json,manifest));
        storage.store(id,verified);storage.store(id,verified);assertThat(storage.read(id,verified.archiveHash())).isEqualTo(verified.archive());
        Path blob=directory.resolve(id.toString()).resolve(verified.archiveHash()+".tar.gz");Files.write(blob,new byte[]{1});assertThatThrownBy(()->storage.read(id,verified.archiveHash())).hasMessageContaining("integrity");Files.delete(blob);assertThatThrownBy(()->storage.read(id,verified.archiveHash())).hasMessageContaining("missing");
    }
}
