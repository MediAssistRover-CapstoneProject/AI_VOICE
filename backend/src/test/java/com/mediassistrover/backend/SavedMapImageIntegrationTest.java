package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.mapping.MappingProperties;
import com.mediassistrover.backend.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.UUID;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SavedMapImageIntegrationTest extends MySqlIntegrationTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired MappingProperties properties;
    @Autowired FloorRepository floors;@Autowired AccountRepository accounts;@Autowired HospitalInformationRepository hospitals;
    @Autowired EntityManager entities;
    @TempDir Path directory;
    byte[] yaml="image: map.pgm\nmode: trinary\nresolution: 0.02500000037252903\norigin: [-3.7304232240790096, -1.9355943845186525, 0.35]\nnegate: 0\noccupied_thresh: 0.65\nfree_thresh: 0.25\n".getBytes();
    byte[] pgm={'P','5','\n','2',' ','3','\n','2','5','5','\n',0,(byte)205,(byte)254,35,10,13};
    UUID floor() {
        String code=UUID.randomUUID().toString();
        var a=accounts.save(Account.builder().accountCode(code).username(code).fullName("Image test").role(Role.ADMIN)
            .authProvider(AuthProvider.LOCAL).status(AccountStatus.ACTIVE).passwordHash("unused").build());
        var h=hospitals.save(HospitalInformation.builder().updatedBy(a).infoCategory("GENERAL").title("Image test").content("Test")
            .languageCode("vi").keywordsJson(json.createArrayNode()).status(HospitalInformationStatus.PUBLISHED).build());
        return floors.save(Floor.builder().hospitalInformation(h).floorCode(code).floorName("Image floor").active(true).displayOrder(1).build()).getFloorId();
    }
    UUID imported(byte[] pixels) throws Exception {
        var response=mvc.perform(multipart("/api/v1/maps/import").file(new MockMultipartFile("yaml",yaml))
            .file(new MockMultipartFile("pgm",pixels)).param("floorId",floor().toString()).param("mapCode",UUID.randomUUID().toString())
            .param("mapName","Saved image")).andExpect(status().isCreated()).andReturn().getResponse();
        UUID id=UUID.fromString(json.readTree(response.getContentAsByteArray()).path("mapId").asText());
        entities.flush();entities.clear();return id;
    }
    @Test @Transactional @WithMockUser(roles="ADMIN") void rendersTheRequestedMapAndExactGeometryWithoutALiveSession() throws Exception {
        String previous=properties.getStorageDir();properties.setStorageDir(directory.toString());
        try {
            UUID first=imported(pgm),second=imported("P2\n2 3\n255\n254 254 254 0 0 0\n".getBytes());
            var firstResponse=mvc.perform(get("/api/v1/maps/{id}/image.png",first).accept("image/png"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Map-Id",first.toString())).andExpect(header().string("X-Map-Width-Px","2"))
                .andExpect(header().string("X-Map-Height-Px","3")).andReturn().getResponse();
            var firstImage=ImageIO.read(new ByteArrayInputStream(firstResponse.getContentAsByteArray()));
            assertThat(firstImage.getRaster().getSamples(0,0,2,3,0,(int[])null)).containsExactly(0,205,254,35,10,13);
            byte[] secondPng=mvc.perform(get("/api/v1/maps/{id}/image.png",second).accept("image/png"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            var secondImage=ImageIO.read(new ByteArrayInputStream(secondPng));
            assertThat(secondImage.getRaster().getSamples(0,0,2,3,0,(int[])null)).containsExactly(254,254,254,0,0,0);
            var body=mvc.perform(get("/api/v1/maps/{id}/image-metadata",first)).andExpect(status().isOk()).andReturn().getResponse();
            var meta=json.readTree(body.getContentAsByteArray());
            assertThat(meta.path("origin_x_m").asDouble()).isEqualTo(-3.7304232240790096);
            assertThat(meta.path("pixel_origin").asText()).isEqualTo("TOP_LEFT");
            var matrix=meta.path("pixel_center_to_map");double r=0.02500000037252903,c=Math.cos(.35),s=Math.sin(.35);
            for(int u=0;u<2;u++)for(int v=0;v<3;v++) {
                double x=matrix.get(0).get(0).asDouble()*u+matrix.get(0).get(1).asDouble()*v+matrix.get(0).get(2).asDouble();
                double y=matrix.get(1).get(0).asDouble()*u+matrix.get(1).get(1).asDouble()*v+matrix.get(1).get(2).asDouble();
                assertThat(x).isCloseTo(-3.7304232240790096+c*r*(u+.5)-s*r*(3-v-.5),within(1e-12));
                assertThat(y).isCloseTo(-1.9355943845186525+s*r*(u+.5)+c*r*(3-v-.5),within(1e-12));
            }
        } finally {properties.setStorageDir(previous);}
    }
    @Test @Transactional @WithMockUser(roles="ADMIN") void missingOrModifiedPixelsReturnAnErrorInsteadOfAFabricatedMap() throws Exception {
        String previous=properties.getStorageDir();properties.setStorageDir(directory.toString());
        try {
            UUID id=imported(pgm);Path image=directory.resolve("imports").resolve(id.toString()).resolve("map.pgm");
            byte[] changed=pgm.clone();changed[changed.length-1]=0;Files.write(image,changed);
            mvc.perform(get("/api/v1/maps/{id}/image.png",id).accept("image/png"))
                .andExpect(status().isUnprocessableEntity()).andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.message").value("Map asset hash differs from catalog"));
            Files.delete(image);
            mvc.perform(get("/api/v1/maps/{id}/image.png",id).accept("image/png"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").value("Map file is missing"));
        } finally {properties.setStorageDir(previous);}
    }
    @Test @WithMockUser(roles="STAFF") void staffCanReachTheImageApiAndMissingMapsReturn404Json() throws Exception {
        mvc.perform(get("/api/v1/maps/{id}/image.png",UUID.randomUUID()).accept("image/png"))
            .andExpect(status().isNotFound()).andExpect(content().contentType("application/json"));
    }
    @Test void imageRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/maps/{id}/image.png",UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/maps/{id}/image-metadata",UUID.randomUUID())).andExpect(status().isUnauthorized());
    }
    @Test @WithMockUser(roles="DEVICE") void deviceCannotUseTheStaffCatalogImageApi() throws Exception {
        mvc.perform(get("/api/v1/maps/{id}/image.png",UUID.randomUUID())).andExpect(status().isForbidden());
    }
}
