package com.mediassistrover.backend.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.entity.FacilityMap;
import com.mediassistrover.backend.enums.MapStatus;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.DevicePrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

@Service @RequiredArgsConstructor
public class MapBundleService {
    private final FacilityMapRepository maps;
    private final FloorRepository floors;
    private final RobotRepository robots;
    private final MappingArchiveReader reader;
    private final MappingArtifactService artifacts;
    private final MapArtifactRepository receipts;
    private final MappingArtifactStorage storage;
    private final MappingProperties properties;
    private final ObjectMapper json;
    private final RobotNavigationSessionRepository navigationSessions;
    private final EndpointRepository endpoints;
    private final java.time.Clock clock;
    public record Imported(UUID mapId,String assetHash,String status) { }
    private Path imported(UUID mapId) throws IOException {
        Path p=Path.of(properties.getStorageDir()).toAbsolutePath().normalize().resolve("imports").resolve(mapId.toString());
        Files.createDirectories(p);
        if(!p.toRealPath().equals(p))throw new BusinessRuleException("Imported map directory contains a symbolic link");
        return p;
    }
    @Transactional
    public Imported importMap(UUID floorId,String code,int version,String name,byte[] yaml,byte[] pgm) {
        if(maps.existsByMapCodeIgnoreCaseAndVersionNo(code,version))throw new ConflictException("Map code/version already exists");
        var floor=floors.findById(floorId).orElseThrow(()->new ResourceNotFoundException("Floor not found"));
        if(!floor.isActive())throw new BusinessRuleException("Map import requires an active floor");
        var meta=reader.inspectMap(yaml,pgm);String hash=fingerprint(yaml,pgm);
        var map=maps.saveAndFlush(FacilityMap.builder().floor(floor).mapCode(code).versionNo(version).mapName(name).status(MapStatus.DRAFT)
            .widthM(meta.path("width_m").decimalValue()).heightM(meta.path("height_m").decimalValue())
            .resolutionMPerPixel(meta.path("resolution_m_per_pixel").decimalValue()).originXM(meta.path("origin_x_m").decimalValue())
            .originYM(meta.path("origin_y_m").decimalValue()).originYawRad(meta.path("origin_yaw_rad").decimalValue())
            .frameId("map").mapFileUri("pending").assetHash(hash).build());
        try {
            Path root=imported(map.getMapId());Files.write(root.resolve("map.yaml"),yaml,StandardOpenOption.CREATE_NEW);
            Files.write(root.resolve("map.pgm"),pgm,StandardOpenOption.CREATE_NEW);
        } catch(IOException e){throw new BusinessRuleException("Cannot persist imported map");}
        map.setMapFileUri("/api/v1/pi/maps/"+map.getMapId()+"/bundle");
        return new Imported(map.getMapId(),hash,"DRAFT");
    }
    @Transactional(readOnly=true)
    public byte[] bundle(UUID id,DevicePrincipal device) {
        var map=maps.findById(id).orElseThrow(()->new ResourceNotFoundException("Map not found"));
        if(device!=null) {
            var robot=robots.findById(device.robotId()).orElseThrow();
            var temporary=navigationSessions.findById(device.robotId()).filter(r->r.open(java.time.Instant.now(clock))).orElse(null);
            boolean granted=temporary!=null&&temporary.getMap().getMapId().equals(id)&&temporary.getMapAssetHash().equalsIgnoreCase(map.getAssetHash());
            if(!granted&&(robot.getCurrentMap()==null||!robot.getCurrentMap().getMapId().equals(id)))throw new AccessDeniedException("Map is not assigned to this robot or its current test session");
        }
        try {
            Map<String,byte[]> files;
            String uri=map.getMapFileUri();
            if(uri.equals("/api/v1/pi/maps/"+id+"/bundle")) {
                Path root=imported(id);files=Map.of("map.yaml",read(root.resolve("map.yaml"),65536),"map.pgm",read(root.resolve("map.pgm"),4_100_000));
            } else if(uri.matches("/map-assets/[A-Za-z0-9_-]+/v[0-9]+/map.yaml")) {
                var yaml=new ClassPathResource("static"+uri);var pgm=new ClassPathResource("static"+uri.replace("map.yaml","map.pgm"));
                try(var a=yaml.getInputStream();var b=pgm.getInputStream()) {files=Map.of("map.yaml",reader.readBounded(a,65536),"map.pgm",reader.readBounded(b,4_100_000));}
            } else if(uri.startsWith("/api/v1/pi/mapping-artifacts/")) {
                UUID artifact=UUID.fromString(uri.substring(uri.lastIndexOf('/')+1));
                var receipt=receipts.findById(artifact).orElseThrow(()->new ResourceNotFoundException("Map artifact not found"));
                if(!id.equals(receipt.getMapId()))throw new BusinessRuleException("Map artifact is not linked to this map");
                // The map/temporary-session scope was checked above, including the linked map ID.
                files=reader.mapFiles(storage.read(artifact,receipt.getArchiveHash()));
            } else throw new BusinessRuleException("Map URI is not a supported local asset/import; use the map import API");
            String hash=fingerprint(files.get("map.yaml"),files.get("map.pgm"));
            if(!hash.equalsIgnoreCase(map.getAssetHash()))throw new BusinessRuleException("Map asset hash differs from catalog");
            var meta=reader.inspectMap(files.get("map.yaml"),files.get("map.pgm"));
            // MySQL DECIMAL columns round geometry on write. Compare at that stored precision;
            // keep the original, hash-verified file geometry in the downloaded metadata.
            if(!catalogNumber(meta,"resolution_m_per_pixel",map.getResolutionMPerPixel(),6)
                    ||!catalogNumber(meta,"width_m",map.getWidthM(),4)
                    ||!catalogNumber(meta,"height_m",map.getHeightM(),4)
                    ||!catalogNumber(meta,"origin_x_m",map.getOriginXM(),4)
                    ||!catalogNumber(meta,"origin_y_m",map.getOriginYM(),4)
                    ||!catalogNumber(meta,"origin_yaw_rad",map.getOriginYawRad(),6))
                throw new BusinessRuleException("Map dimensions differ from catalog");
            meta.put("map_id",id.toString()).put("asset_hash",hash).put("frame_id",map.getFrameId());
            var output=new ByteArrayOutputStream();
            try(var zip=new ZipOutputStream(output)) {
                for(var e:files.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}
                zip.putNextEntry(new ZipEntry("metadata.json"));zip.write(json.writeValueAsBytes(meta));zip.closeEntry();
            }
            return output.toByteArray();
        } catch(IOException e){throw new BusinessRuleException("Map files are not available");}
    }
    private boolean catalogNumber(com.fasterxml.jackson.databind.JsonNode meta,String field,BigDecimal stored,int scale) {
        return meta.path(field).decimalValue().setScale(scale,RoundingMode.HALF_UP).compareTo(stored)==0;
    }
    private Map<String,byte[]> verifiedFiles(UUID id){
        Map<String,byte[]> files=new HashMap<>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bundle(id,null)))){
            ZipEntry e;while((e=zip.getNextEntry())!=null)files.put(e.getName(),reader.readBounded(zip,e.getName().equals("map.pgm")?4_100_000:65536));
        }catch(IOException e){throw new BusinessRuleException("Map bundle unavailable");}return files;
    }
    private Map<String,Object> yaml(byte[] bytes){return new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions())).load(new ByteArrayInputStream(bytes));}
    private java.awt.image.BufferedImage raster(byte[] pgm){try{return javax.imageio.ImageIO.read(new ByteArrayInputStream(PgmPngRenderer.render(pgm)));}catch(IOException e){throw new BusinessRuleException("Map raster unavailable");}}
    private boolean negated(Map<String,Object> config){var value=config.get("negate");return Boolean.TRUE.equals(value)||value instanceof Number n&&n.intValue()!=0;}
    public boolean navigationSafe(UUID id){
        var f=verifiedFiles(id);var y=yaml(f.get("map.yaml"));
        if(!"trinary".equals(Objects.toString(y.getOrDefault("mode","trinary")))||negated(y))return true;
        if(((Number)y.get("free_thresh")).doubleValue()<50./255.)return true;
        var image=raster(f.get("map.pgm"));boolean gray=false;
        for(int v=0;v<image.getHeight();v++)for(int u=0;u<image.getWidth();u++){
            int value=image.getRaster().getSample(u,v,0);if(value==205)gray=true;else if(value!=0&&value!=254)return true;
        }
        return !gray;
    }
    public void requireFreePoint(UUID id,double x,double y){
        var f=verifiedFiles(id);var meta=reader.inspectMap(f.get("map.yaml"),f.get("map.pgm"));var config=yaml(f.get("map.yaml"));
        double a=meta.path("origin_yaw_rad").asDouble(),dx=x-meta.path("origin_x_m").asDouble(),dy=y-meta.path("origin_y_m").asDouble(),r=meta.path("resolution_m_per_pixel").asDouble();
        int u=(int)Math.floor((Math.cos(a)*dx+Math.sin(a)*dy)/r),v=(int)Math.floor((-Math.sin(a)*dx+Math.cos(a)*dy)/r);
        var image=raster(f.get("map.pgm"));
        if(u<0||v<0||u>=image.getWidth()||v>=image.getHeight())throw new BusinessRuleException("Initial pose is outside the map");
        double shade=image.getRaster().getSample(u,image.getHeight()-v-1,0)/255.;
        double occupancy=negated(config)?shade:1-shade;
        if(occupancy>=((Number)config.get("free_thresh")).doubleValue())throw new BusinessRuleException("Initial pose must be in known free space; this is only a seed, not localization proof");
    }
    @Transactional
    public Imported navigationCopy(UUID id){
        var old=maps.findById(id).orElseThrow();if(navigationSafe(id))return new Imported(id,old.getAssetHash(),old.getStatus().name());
        var files=verifiedFiles(id);var config=yaml(files.get("map.yaml"));config.put("free_thresh",.196);
        byte[] yaml=new org.yaml.snakeyaml.Yaml().dump(config).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String code=old.getMapCode().substring(0,Math.min(48,old.getMapCode().length()))+"_NAV_"+UUID.randomUUID().toString().substring(0,8);
        Imported copy=importMap(old.getFloor().getFloorId(),code,1,old.getMapName().substring(0,Math.min(140,old.getMapName().length()))+" (Nav2)",yaml,files.get("map.pgm"));
        var map=maps.findById(copy.mapId()).orElseThrow();
        for(var e:endpoints.findByMapMapIdOrderByEndpointCodeAsc(id))endpoints.save(com.mediassistrover.backend.entity.Endpoint.builder().map(map)
                .endpointCode(e.getEndpointCode()).endpointName(e.getEndpointName()).endpointType(e.getEndpointType()).xM(e.getXM()).yM(e.getYM()).yawRad(e.getYawRad())
                .active(e.isActive()).accessible(e.isAccessible()).publicVisible(e.isPublicVisible()).description(e.getDescription()).build());
        return copy;
    }
    private byte[] read(Path p,int max) throws IOException {
        if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new BusinessRuleException("Map file is missing");
        try(var input=Files.newInputStream(p)){return reader.readBounded(input,max);}
    }
    public static String fingerprint(byte[] yaml,byte[] pgm) {
        try{var d=MessageDigest.getInstance("SHA-256");d.update(yaml);d.update((byte)0);d.update(pgm);return HexFormat.of().formatHex(d.digest());}
        catch(Exception e){throw new IllegalStateException(e);}
    }
}
