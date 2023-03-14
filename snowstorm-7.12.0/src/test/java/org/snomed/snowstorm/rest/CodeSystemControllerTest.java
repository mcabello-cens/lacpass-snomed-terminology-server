package org.snomed.snowstorm.rest;

import io.kaicode.elasticvc.api.PathUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snomed.snowstorm.AbstractTest;
import org.snomed.snowstorm.TestConfig;
import org.snomed.snowstorm.core.data.domain.CodeSystem;
import org.snomed.snowstorm.core.data.domain.CodeSystemVersion;
import org.snomed.snowstorm.core.data.services.CodeSystemService;
import org.snomed.snowstorm.core.data.services.CodeSystemUpgradeService;
import org.snomed.snowstorm.core.data.services.ServiceException;
import org.snomed.snowstorm.rest.pojo.ItemsPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;


@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TestConfig.class)
class CodeSystemControllerTest extends AbstractTest {
    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private CodeSystemService codeSystemService;

    @Autowired
    private CodeSystemUpgradeService codeSystemUpgradeService;

    @BeforeEach
    public void setUp() throws ServiceException {
        givenAuthoringCycles();
    }

    @Test
    public void listCodeSystems_ShouldReturnCodeSystems_WhenCodeSystemsExists() {
        //given
        String requestUrl = listCodeSystems();

        //when
        ItemsPage<?> result = testRestTemplate.getForObject(requestUrl, ItemsPage.class);

        //then
        assertEquals(2, result.getTotal()); //SNOMEDCT & SNOMEDCT-DM
    }

    @Test
    public void listCodeSystems_ShouldReturnInternationalWithNoDependsOnProperty() {
        //given
        String requestUrl = listCodeSystems();

        //when
        ItemsPage<ItemsPage<?>> response = testRestTemplate.getForObject(requestUrl, ItemsPage.class);
        Integer dependantVersionEffectiveTime = getDependantVersionEffectiveTimeFromResponse(response, 0);

        //then
        assertNull(dependantVersionEffectiveTime); //International doesn't depend on any version.
    }

    @Test
    public void listCodeSystems_ShouldReturnCodeSystemWithExpectedProperty_WhenCodeSystemDependsOnInternational() {
        //given
        String requestUrl = listCodeSystems();

        //when
        ItemsPage<ItemsPage<?>> response = testRestTemplate.getForObject(requestUrl, ItemsPage.class);
        Integer dependantVersionEffectiveTime = getDependantVersionEffectiveTimeFromResponse(response, 1);

        //then
        assertEquals(20200731, dependantVersionEffectiveTime); //SNOMEDCT-DM has not versioned/published 2021 yet.
    }

    @Test
    public void findCodeSystem_ShouldReturnError_WhenCodeSystemCannotBeFound() {
        //given
        String requestUrl = findCodeSystem("idontexist");

        //when
        Map<String, Object> response = testRestTemplate.getForObject(requestUrl, Map.class);

        //then
        assertEquals("NOT_FOUND", response.get("error"));
        assertEquals("Code System not found", response.get("message"));
    }

    @Test
    public void findCodeSystem_ShouldReturnCodeSystems_WhenCodeSystemsExists() {
        //given
        String requestUrl = findCodeSystem("SNOMEDCT-DM");

        //when
        CodeSystem response = testRestTemplate.getForObject(requestUrl, CodeSystem.class);

        //then
        assertNotNull(response);
        assertEquals("SNOMEDCT-DM", response.getShortName());
    }

    @Test
    public void findCodeSystem_ShouldReturnInternationalWithNoDependsOnProperty() {
        String requestUrl = findCodeSystem("SNOMEDCT");

        //when
        ItemsPage<ItemsPage<?>> response = testRestTemplate.getForObject(requestUrl, ItemsPage.class);
        Integer dependantVersionEffectiveTime = getDependantVersionEffectiveTimeFromResponse(response, 0);

        //then
        assertNull(dependantVersionEffectiveTime); //International doesn't depend on any version.
    }

    @Test
    public void findCodeSystem_ShouldReturnCodeSystemWithExpectedProperty_WhenCodeSystemDependsOnInternational() {
        //given
        String requestUrl = findCodeSystem("SNOMEDCT-DM");

        //when
        CodeSystem response = testRestTemplate.getForObject(requestUrl, CodeSystem.class);
        Integer dependantVersionEffectiveTime = response.getLatestVersion().getDependantVersionEffectiveTime();

        //then
        assertEquals(20200731, dependantVersionEffectiveTime); //SNOMEDCT-DM has not versioned/published 2021 yet.
    }

    @Test
    public void findAllVersions_ShouldReturnEmpty_WhenCodeSystemCannotBeFound() {
        //given
        String requestUrl = findAllVersions("idontexist");

        //when
        ItemsPage<ItemsPage<?>> response = testRestTemplate.getForObject(requestUrl, ItemsPage.class);

        //then
        assertEquals(0, response.getItems().size());
    }

    @Test
    public void findAllVersions_ShouldReturnInternationalVersionsWithNoDependsOnProperty() {
        //given
        String requestUrl = findAllVersions("SNOMEDCT");

        //when
        ItemsPage<ItemsPage<?>> response = testRestTemplate.getForObject(requestUrl, ItemsPage.class);

        //then
        assertEquals(5, response.getItems().size());
        assertNull(getDependantVersionEffectiveTimeFromResponse(response, 0));
        assertNull(getDependantVersionEffectiveTimeFromResponse(response, 1));
        assertNull(getDependantVersionEffectiveTimeFromResponse(response, 2));
        assertNull(getDependantVersionEffectiveTimeFromResponse(response, 3));
        assertNull(getDependantVersionEffectiveTimeFromResponse(response, 4));
    }

    @Test
    public void findAllVersions_ShouldReturnExpectedDependsOnPropertyValues() {
        //given
        String requestUrl = findAllVersions("SNOMEDCT-DM");

        //when
        ItemsPage<ItemsPage<?>> response = testRestTemplate.getForObject(requestUrl, ItemsPage.class);

        //then
        assertEquals(3, response.getItems().size());
        assertEquals(20190731, getDependantVersionEffectiveTimeFromResponse(response, 0));
        assertEquals(20200131, getDependantVersionEffectiveTimeFromResponse(response, 1));
        assertEquals(20200731, getDependantVersionEffectiveTimeFromResponse(response, 2));
    }

    @Test
    public void startNewAuthoringCycle_ShouldReturnExpectedMetadata() {
        //given
        CodeSystem codeSystem = codeSystemService.find("SNOMEDCT");
        String requestUrl = startNewAuthoringCycle(codeSystem.getShortName());

        //when
        this.testRestTemplate.postForEntity(requestUrl, null, void.class);

        //then
        ResponseEntity<Object> responseEntity = this.testRestTemplate.getForEntity(getBranch(codeSystem.getBranchPath()), Object.class);
        LinkedHashMap<String, Object> receivedMetaData = getMetadata(responseEntity);
        assertNotNull(receivedMetaData);

        CodeSystemVersion codeSystemVersion = codeSystemService.findLatestImportedVersion(codeSystem.getShortName());
        assertEquals(String.valueOf(codeSystemVersion.getEffectiveDate()), receivedMetaData.get("previousRelease"));
        assertEquals(codeSystemVersion.getReleasePackage(), receivedMetaData.get("previousPackage"));
        assertNull(receivedMetaData.get("previousDependencyPackage"));
    }

    @Test
    public void startNewAuthoringCycle_ShouldReturnExpectedMetadata_WhenCodeSystemDependsOnInternational() {
        //given
        CodeSystem codeSystem = codeSystemService.find("SNOMEDCT-DM");
        String requestUrl = startNewAuthoringCycle(codeSystem.getShortName());

        //when
        this.testRestTemplate.postForEntity(requestUrl, null, void.class);

        //then
        ResponseEntity<Object> responseEntity = this.testRestTemplate.getForEntity(getBranch(codeSystem.getBranchPath()), Object.class);
        LinkedHashMap<String, Object> receivedMetaData = getMetadata(responseEntity);
        assertNotNull(receivedMetaData);

        CodeSystemVersion codeSystemVersion = codeSystemService.findLatestImportedVersion(codeSystem.getShortName());
        assertEquals(String.valueOf(codeSystemVersion.getEffectiveDate()), receivedMetaData.get("previousRelease"));
        assertEquals(codeSystemVersion.getReleasePackage(), receivedMetaData.get("previousPackage"));

        Optional<CodeSystem> parentCodeSystem = codeSystemService.findByBranchPath(PathUtil.getParentPath(codeSystem.getBranchPath()));
        CodeSystemVersion parentCodeSystemVersion = codeSystemService.findVersion(parentCodeSystem.get().getShortName(), codeSystem.getDependantVersionEffectiveTime());
        assertEquals(parentCodeSystemVersion.getReleasePackage(), receivedMetaData.get("previousDependencyPackage"));
    }

    //Wrapper for given blocks as used throughout test class
    private void givenAuthoringCycles() throws ServiceException {
        //given
        //International created and versioned several times.
        givenCodeSystemExists("SNOMEDCT", "MAIN");
        givenCodeSystemVersionExists("SNOMEDCT", 20190131, "2019 January.");
        givenCodeSystemVersionExists("SNOMEDCT", 20190731, "2019 July.");
        givenCodeSystemVersionExists("SNOMEDCT", 20200131, "2020 January.");
        givenCodeSystemVersionExists("SNOMEDCT", 20200731, "2020 July.");
        givenCodeSystemVersionExists("SNOMEDCT", 20210131, "2021 January.");

        //Test code system created in 20190131.
        givenCodeSystemExists("SNOMEDCT-DM", "MAIN/SNOMEDCT-DM", 20190131);

        //Test code system upgraded and versioned throughout 2019 & 2020.
        givenCodeSystemUpgraded("SNOMEDCT-DM", 20190731);
        givenCodeSystemVersionExists("SNOMEDCT-DM", 20190813, "2019 August.", 20190731);
        givenCodeSystemUpgraded("SNOMEDCT-DM", 20200131);
        givenCodeSystemVersionExists("SNOMEDCT-DM", 20200213, "2020 February.", 20200131);
        givenCodeSystemUpgraded("SNOMEDCT-DM", 20200731);
        givenCodeSystemVersionExists("SNOMEDCT-DM", 20200813, "2020 August.", 20200731);
        givenCodeSystemUpgraded("SNOMEDCT-DM", 20210131); //Upgraded but not yet released.
    }

    private void givenCodeSystemExists(String shortName, String branchPath) {
        codeSystemService.createCodeSystem(new CodeSystem(shortName, branchPath));
    }

    private void givenCodeSystemVersionExists(String shortName, int effectiveDate, String description) {
        codeSystemService.createVersion(codeSystemService.find(shortName), effectiveDate, description);
        CodeSystemVersion codeSystemVersion = codeSystemService.findVersion(shortName, effectiveDate);
        codeSystemService.updateCodeSystemVersionPackage(codeSystemVersion, "SnomedCT_InternationalRF2_PRODUCTION_" + effectiveDate + "T120000Z.zip");
    }

    private void givenCodeSystemExists(String shortName, String branchPath, Integer dependantVersion) {
        CodeSystem newCodeSystem = new CodeSystem(shortName, branchPath);
        newCodeSystem.setDependantVersionEffectiveTime(dependantVersion);
        codeSystemService.createCodeSystem(newCodeSystem);
    }

    private void givenCodeSystemUpgraded(String shortName, int newDependentOnVersion) throws ServiceException {
        codeSystemUpgradeService.upgrade(codeSystemService.find(shortName), newDependentOnVersion, false);
    }

    private void givenCodeSystemVersionExists(String shortName, int effectiveDate, String description, int dependsOn) {
        CodeSystem codeSystem = codeSystemService.find(shortName);
        codeSystem.setDependantVersionEffectiveTime(dependsOn);
        codeSystemService.createVersion(codeSystem, effectiveDate, description);
        CodeSystemVersion codeSystemVersion = codeSystemService.findVersion(shortName, effectiveDate);
        codeSystemService.updateCodeSystemVersionPackage(codeSystemVersion, "SnomedCT_DMEditionRF2_PRODUCTION_" + effectiveDate + "T120000Z.zip");
    }

    private Integer getDependantVersionEffectiveTimeFromResponse(ItemsPage<ItemsPage<?>> response, int index) {
        Collection<ItemsPage<?>> items = response.getItems();

        if (items instanceof ArrayList) {
            List<LinkedHashMap<?, ?>> itemsList = (ArrayList) items;
            LinkedHashMap<?, ?> linkedHashMap = itemsList.get(index);
            LinkedHashMap<?, ?> latestVersion = (LinkedHashMap) linkedHashMap.get("latestVersion");
            if (latestVersion != null) {
                return (Integer) latestVersion.get("dependantVersionEffectiveTime");
            } else {
                return (Integer) linkedHashMap.get("dependantVersionEffectiveTime");
            }
        }

        return null;
    }

    private LinkedHashMap<String, Object> getMetadata(ResponseEntity<Object> responseEntity) {
        LinkedHashMap<String, Object> body = (LinkedHashMap<String, Object>) responseEntity.getBody();
        if (body == null) {
            return new LinkedHashMap<>();
        }

        return (LinkedHashMap<String, Object>) body.get("metadata");
    }

    private String listCodeSystems() {
        return "http://localhost:" + port + "/codesystems";
    }

    private String findCodeSystem(String shortName) {
        return "http://localhost:" + port + "/codesystems/" + shortName;
    }

    private String findAllVersions(String shortName) {
        return "http://localhost:" + port + "/codesystems/" + shortName + "/versions";
    }

    private String startNewAuthoringCycle(String shortName) {
        return "http://localhost:" + port + "/codesystems/" + shortName + "/new-authoring-cycle";
    }

    private String getBranch(String branchPath) {
        return "http://localhost:" + port + "/branches/" + branchPath;
    }
}
