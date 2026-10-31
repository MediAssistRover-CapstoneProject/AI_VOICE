package com.mediassistrover.backend;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.mapping.MappingProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class MapBundleIntegrationTest extends MySqlIntegrationTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;
    @Autowired FloorRepository floors;@Autowired AccountRepository accounts;@Autowired HospitalInformationRepository hospitals;
    @Autowired MappingProperties properties;
    @Autowired jakarta.persistence.EntityManager entities;
    @Autowired FacilityMapRepository maps;
    @TempDir Path directory;
    byte[] yaml="image: map.pgm\nmode: trinary\nresolution: 0.5\norigin: [0.0, 0.0, 0.0]\nnegate: 0\noccupied_thresh: 0.65\nfree_thresh: 0.25\n".getBytes();
    byte[] pgm=new byte[]{'P','5','\n','2',' ','2','\n','2','5','5','\n',0,(byte)254,(byte)254,0};
    UUID floor() {
        String code=UUID.randomUUID().toString();
        var a=accounts.save(Account.builder().accountCode(code).username(code).fullName("Map test").role(Role.ADMIN).authProvider(AuthProvider.LOCAL).status(AccountStatus.ACTIVE).passwordHash("unused").build());
        var h=hospitals.save(HospitalInformation.builder().updatedBy(a).infoCategory("GENERAL").title("Map test").content("Test").languageCode("vi").keywordsJson(json.createArrayNode()).status(HospitalInformationStatus.PUBLISHED).build());
        return floors.save(Floor.builder().hospitalInformation(h).floorCode(code).floorName("Test").active(true).displayOrder(1).build()).getFloorId();
    }
    @Test @Transactional @WithMockUser(roles="ADMIN") void importsGeometryAndDownloadsSameAssets() throws Exception {
        String old=properties.getStorageDir();properties.setStorageDir(directory.toString());
        try {
            var result=mvc.perform(multipart("/api/v1/maps/import")
                .file(new MockMultipartFile("yaml","map.yaml","application/yaml",yaml))
                .file(new MockMultipartFile("pgm","map.pgm","application/octet-stream",pgm))
                .param("floorId",floor().toString()).param("mapCode","BUNDLE_TEST").param("mapName","Map import"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT")).andReturn();
            String id=json.readTree(result.getResponse().getContentAsByteArray()).path("mapId").asText();
            byte[] zip=mvc.perform(get("/api/v1/pi/maps/{id}/bundle",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            Files.createDirectories(Path.of("target/map-contract"));Files.write(Path.of("target/map-contract/be-bundle.zip"),zip);
            Files.writeString(Path.of("target/map-contract/map-id.txt"),id);
            try(var in=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
                int count=0;while(in.getNextEntry()!=null){count++;assertThat(in.readAllBytes()).isNotEmpty();}assertThat(count).isEqualTo(3);
            }
        } finally {properties.setStorageDir(old);}
    }
    @Test @Transactional @WithMockUser(roles="ADMIN") void rejectsPlaceholderResolution() throws Exception {
        byte[] bad=new String(yaml).replace("0.5","REPLACE_WITH_METERS_PER_PIXEL").getBytes();
        mvc.perform(multipart("/api/v1/maps/import")
            .file(new MockMultipartFile("yaml",bad)).file(new MockMultipartFile("pgm",pgm))
            .param("floorId",floor().toString()).param("mapCode","BAD_MAP").param("mapName","Bad map"))
            .andExpect(status().isUnprocessableEntity());
    }
    @Test @WithMockUser(roles="STAFF") void staffCannotImportMaps() throws Exception {
        mvc.perform(multipart("/api/v1/maps/import")
            .file(new MockMultipartFile("yaml",yaml)).file(new MockMultipartFile("pgm",pgm))
            .param("floorId",UUID.randomUUID().toString()).param("mapCode","FORBIDDEN").param("mapName","Forbidden"))
            .andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="DEVICE") void downloadRequiresActualDeviceIdentity() throws Exception {
        mvc.perform(get("/api/v1/pi/maps/{id}/bundle",UUID.randomUUID())).andExpect(status().isForbidden());
    }
    @Test @Transactional @WithMockUser(roles="ADMIN") void downloadsRealScanGeometryAfterDatabaseRounding() throws Exception {
        String old=properties.getStorageDir();properties.setStorageDir(directory.toString());
        try {
            byte[] preciseYaml=new String(yaml).replace("resolution: 0.5","resolution: 0.02500000037252903")
                .replace("origin: [0.0, 0.0, 0.0]","origin: [-3.7304232240790096, -1.9355943845186525, 0.123456789]").getBytes();
            var imported=mvc.perform(multipart("/api/v1/maps/import")
                .file(new MockMultipartFile("yaml",preciseYaml)).file(new MockMultipartFile("pgm",pgm))
                .param("floorId",floor().toString()).param("mapCode","ROUNDED_SCAN").param("mapName","Precise scan"))
                .andExpect(status().isCreated()).andReturn();
            UUID id=UUID.fromString(json.readTree(imported.getResponse().getContentAsByteArray()).path("mapId").asText());
            entities.flush();entities.clear();
            assertThat(maps.findById(id).orElseThrow().getOriginXM()).isEqualByComparingTo("-3.7304");
            byte[] zip=mvc.perform(get("/api/v1/pi/maps/{id}/bundle",id).accept("application/zip"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/zip"))
                .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("attachment;")))
                .andReturn().getResponse().getContentAsByteArray();
            Map<String,byte[]> files=new HashMap<>();
            try(var input=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
                java.util.zip.ZipEntry entry;while((entry=input.getNextEntry())!=null)files.put(entry.getName(),input.readAllBytes());
            }
            assertThat(files.get("map.yaml")).isEqualTo(preciseYaml);
            assertThat(json.readTree(files.get("metadata.json")).path("origin_x_m").asDouble()).isEqualTo(-3.7304232240790096);
            maps.findById(id).orElseThrow().setOriginXM(new java.math.BigDecimal("-3.7404"));
            entities.flush();entities.clear();
            mvc.perform(get("/api/v1/pi/maps/{id}/bundle",id).accept("application/zip"))
                .andExpect(status().isUnprocessableEntity()).andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.message").value("Map dimensions differ from catalog"));
        } finally {properties.setStorageDir(old);}
    }
    @Test @WithMockUser(roles="ADMIN") void zipRequestReturnsJsonForMissingMap() throws Exception {
        mvc.perform(get("/api/v1/pi/maps/{id}/bundle",UUID.randomUUID()).accept("application/zip"))
            .andExpect(status().isNotFound()).andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));
    }
    @Test void bundleStillRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/pi/maps/{id}/bundle",UUID.randomUUID()).accept("application/zip"))
            .andExpect(status().isUnauthorized());
    }
    @Test void internalErrorDispatchDoesNotBecomeAnAuthenticationFailure() throws Exception {
        mvc.perform(get("/error").with(request->{request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);return request;})
                .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE,500))
            .andExpect(status().isInternalServerError());
    }
}
