package com.mediassistrover.backend.mapping;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.validation.MappingArtifactContractValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Bounded, regular-file-only tar reader. Archive names are never used as filesystem paths. */
@Component @RequiredArgsConstructor
public class MappingArchiveReader {
    private final ObjectMapper json;
    private final MappingArtifactContractValidator contracts;
    private final MappingProperties properties;
    public record Verified(byte[] archive,String archiveHash,String assetHash,JsonNode manifest) { }
    public byte[] readBounded(InputStream input,int limit) throws IOException {
        if(limit<1 || limit>64*1024*1024)fail("Invalid mapping byte limit");
        byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit)fail("Mapping file exceeds byte limit");return bytes;
    }
    public Verified verify(InputStream input,UUID artifactId,String assetHash,String archiveHash) {
        if(assetHash==null || archiveHash==null || !assetHash.matches("[a-fA-F0-9]{64}") || !archiveHash.matches("[a-fA-F0-9]{64}"))fail("Both SHA-256 headers are required");
        try {
            byte[] archive=readBounded(input,properties.getMaxArchiveBytes());
            if(!hash(archive).equalsIgnoreCase(archiveHash))fail("Archive SHA-256 mismatch");
            byte[] tar;
            try(var gzip=new GZIPInputStream(new ByteArrayInputStream(archive))) {tar=readBounded(gzip,properties.getMaxUncompressedBytes());}
            Map<String,byte[]> files=tar(tar);
            byte[] yaml=files.get("map.yaml"),pgm=files.get("map.pgm");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(yaml);digest.update((byte)0);digest.update(pgm);
            String actual=HexFormat.of().formatHex(digest.digest());if(!actual.equalsIgnoreCase(assetHash))fail("Asset SHA-256 mismatch");
            JsonNode manifest=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature()).readTree(files.get("manifest.json"));
            contracts.manifest(manifest);
            if(!artifactId.toString().equalsIgnoreCase(manifest.path("artifact_id").asText()) || !actual.equalsIgnoreCase(manifest.path("asset_hash").asText()))fail("Manifest artifact/hash mismatch");
            validateMap(yaml,pgm,manifest);
            return new Verified(archive,hash(archive),actual,manifest);
        } catch(BusinessRuleException ex){throw ex;}
        catch(Exception ex){throw new BusinessRuleException("Invalid mapping archive: "+ex.getClass().getSimpleName());}
    }
    public static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    public ObjectNode inspectMap(byte[] yamlBytes,byte[] pgm) {
        LoaderOptions options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(64*1024);options.setNestingDepthLimit(8);
        Object loaded=new Yaml(new SafeConstructor(options)).load(new String(yamlBytes,StandardCharsets.UTF_8));
        if(!(loaded instanceof Map<?,?>))fail("Map YAML must be an object");
        Map<?,?> yaml=(Map<?,?>)loaded;
        PgmReader parser=new PgmReader(pgm);parser.token();int width=parser.integer(),height=parser.integer();
        double resolution=number(yaml.get("resolution"));
        if(!(yaml.get("origin") instanceof List<?>)||((List<?>)yaml.get("origin")).size()!=3)fail("Map origin requires three numbers");
        List<?> xyz=(List<?>)yaml.get("origin");
        var n=json.createObjectNode().put("width_px",width).put("height_px",height).put("resolution_m_per_pixel",resolution)
            .put("width_m",width*resolution).put("height_m",height*resolution).put("origin_x_m",number(xyz.get(0)))
            .put("origin_y_m",number(xyz.get(1))).put("origin_yaw_rad",number(xyz.get(2))).put("frame_id","map");
        validateMap(yamlBytes,pgm,n);return n;
    }
    public Map<String,byte[]> mapFiles(byte[] archive) {
        try(var gzip=new GZIPInputStream(new ByteArrayInputStream(archive))) {
            var files=tar(readBounded(gzip,properties.getMaxUncompressedBytes()));
            return Map.of("map.yaml",files.get("map.yaml"),"map.pgm",files.get("map.pgm"));
        } catch(IOException e){throw new BusinessRuleException("Invalid stored map archive");}
    }
    private Map<String,byte[]> tar(byte[] bytes) {
        Map<String,byte[]> files=new HashMap<>();int offset=0;
        while(offset+512<=bytes.length) {
            byte[] header=Arrays.copyOfRange(bytes,offset,offset+512);offset+=512;
            boolean zero=true;for(byte b:header)zero&=b==0;
            if(zero) {
                if(bytes.length-offset<512)fail("Tar requires two zero end blocks");
                for(int i=offset;i<bytes.length;i++)if(bytes[i]!=0)fail("Tar contains data after end blocks");
                if(!files.keySet().equals(Set.of("map.yaml","map.pgm","manifest.json")))fail("Archive must contain exactly map.yaml/map.pgm/manifest.json");return files;
            }
            long sum=0;for(int i=0;i<512;i++)sum+=i>=148 && i<156?32:Byte.toUnsignedInt(header[i]);
            if(sum!=octal(header,148,8))fail("Invalid tar checksum");
            String name=cstring(header,0,100);
            if(!Set.of("map.yaml","map.pgm","manifest.json").contains(name) || !cstring(header,345,155).isEmpty()
                    || !cstring(header,157,100).isEmpty() || header[156]!=0 && header[156]!='0' || files.containsKey(name))fail("Only three unique regular root files are allowed");
            long size=octal(header,124,12);
            if(size<1 || size>properties.getMaxUncompressedBytes() || !name.equals("map.pgm") && size>64*1024 || size>bytes.length-offset)fail("Invalid tar entry size");
            files.put(name,Arrays.copyOfRange(bytes,offset,offset+(int)size));
            long padded=(size+511)/512*512;if(padded>bytes.length-offset)fail("Truncated tar padding");offset+=(int)padded;
        }
        fail("Truncated tar archive");return files;
    }
    private String cstring(byte[] bytes,int start,int length) {
        int end=start;while(end<start+length && bytes[end]!=0)end++;
        for(int i=end;i<start+length;i++)if(bytes[i]!=0)fail("Invalid tar string padding");
        return new String(bytes,start,end-start,StandardCharsets.US_ASCII);
    }
    private long octal(byte[] bytes,int start,int length) {
        String value=new String(bytes,start,length,StandardCharsets.US_ASCII).replace('\0',' ').trim();
        if(!value.matches("[0-7]{1,12}"))fail("Tar requires bounded octal sizes/checksum");return Long.parseLong(value,8);
    }
    private void validateMap(byte[] yamlBytes,byte[] pgm,JsonNode manifest) {
        LoaderOptions options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(0);options.setNestingDepthLimit(8);options.setCodePointLimit(64*1024);
        Object document=new Yaml(new SafeConstructor(options)).load(new String(yamlBytes,StandardCharsets.UTF_8));
        if(!(document instanceof Map<?,?>))fail("Map YAML must be an object");Map<?,?> yaml=(Map<?,?>)document;
        if(!Set.of("image","resolution","origin","negate","occupied_thresh","free_thresh","mode").containsAll(yaml.keySet()) || !"map.pgm".equals(yaml.get("image")))fail("Unsupported YAML fields/image path");
        double resolution=number(yaml.get("resolution"));
        Object origin=yaml.get("origin");if(!(origin instanceof List<?> list) || list.size()!=3)fail("YAML origin requires three numbers");
        List<?> xyz=(List<?>)origin;
        double occupied=number(yaml.get("occupied_thresh")),free=number(yaml.get("free_thresh"));
        if(resolution<=0 || free<0 || occupied>1 || free>=occupied || !Set.of(0.0,1.0).contains(number(yaml.get("negate")))
                || yaml.containsKey("mode") && !Set.of("trinary","scale","raw").contains(yaml.get("mode")))fail("Invalid YAML occupancy/resolution settings");
        PgmReader reader=new PgmReader(pgm);String magic=reader.token();int width=reader.integer(),height=reader.integer(),max=reader.integer();
        long cells=(long)width*height;if(width<1 || height<1 || cells>4_000_000 || max!=255 || !Set.of("P5","P2").contains(magic))fail("PGM must be P5/P2 uint8 with at most 4 million cells");
        if("P5".equals(magic)) {
            reader.binarySeparator();if(pgm.length-reader.offset!=cells)fail("PGM raster size mismatch");
        } else {for(long i=0;i<cells;i++){int sample=reader.integer();if(sample<0 || sample>255)fail("Invalid PGM pixel");}reader.skip();if(reader.offset!=pgm.length)fail("PGM has excess samples");}
        if(!manifest.path("width_px").canConvertToInt() || !manifest.path("height_px").canConvertToInt() || manifest.path("width_px").asInt()!=width || manifest.path("height_px").asInt()!=height)fail("Manifest/PGM pixel dimensions mismatch");
        same(manifest,"resolution_m_per_pixel",resolution);same(manifest,"width_m",width*resolution);same(manifest,"height_m",height*resolution);
        same(manifest,"origin_x_m",number(xyz.get(0)));same(manifest,"origin_y_m",number(xyz.get(1)));same(manifest,"origin_yaw_rad",number(xyz.get(2)));
        if(resolution<.000001 || resolution>=10_000 || width*resolution>=100_000_000 || height*resolution>=100_000_000
                || Math.abs(number(xyz.get(0)))>=100_000_000 || Math.abs(number(xyz.get(1)))>=100_000_000 || Math.abs(number(xyz.get(2)))>=10_000
                || manifest.path("frame_id").asText().isBlank() || manifest.path("frame_id").asText().length()>64)fail("Map metadata exceeds database limits");
    }
    private double number(Object n){if(!(n instanceof Number) || !Double.isFinite(((Number)n).doubleValue()))fail("YAML requires finite numbers");return ((Number)n).doubleValue();}
    private void same(JsonNode n,String key,double value){if(!Double.isFinite(n.path(key).asDouble()) || Math.abs(n.path(key).asDouble()-value)>1e-6)fail("Manifest/YAML mismatch: "+key);}
    private static void fail(String message){throw new BusinessRuleException(message);}
    private static class PgmReader {
        final byte[] bytes;int offset;
        PgmReader(byte[] bytes){this.bytes=bytes;}
        boolean whitespace(byte b){return b==' ' || b=='\n' || b=='\r' || b=='\t' || b=='\f';}
        void skip(){while(offset<bytes.length){if(whitespace(bytes[offset]))offset++;else if(bytes[offset]=='#'){while(offset<bytes.length && bytes[offset]!='\n')offset++;}else break;}}
        String token(){skip();int start=offset;while(offset<bytes.length && !whitespace(bytes[offset]) && bytes[offset]!='#')offset++;if(start==offset || offset-start>16)fail("Invalid PGM header/token");return new String(bytes,start,offset-start,StandardCharsets.US_ASCII);}
        int integer(){String token=token();if(!token.matches("[0-9]{1,9}"))fail("Invalid PGM integer");return Integer.parseInt(token);}
        void binarySeparator(){if(offset>=bytes.length || !whitespace(bytes[offset]))fail("PGM binary separator missing");byte separator=bytes[offset++];if(separator=='\r' && offset<bytes.length && bytes[offset]=='\n')offset++;}
    }
}
