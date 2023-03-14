package org.snomed.snowstorm.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kaicode.elasticvc.api.BranchService;
import io.kaicode.elasticvc.api.ComponentService;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snomed.snowstorm.AbstractTest;
import org.snomed.snowstorm.TestConfig;
import org.snomed.snowstorm.config.Config;
import org.snomed.snowstorm.core.data.domain.*;
import org.snomed.snowstorm.core.data.services.*;
import org.snomed.snowstorm.core.data.services.pojo.AsyncConceptChangeBatch;
import org.snomed.snowstorm.core.data.services.pojo.ConceptHistory;
import org.snomed.snowstorm.core.data.services.pojo.RefSetMemberPageWithBucketAggregations;
import org.snomed.snowstorm.core.pojo.BranchTimepoint;
import org.snomed.snowstorm.loadtest.ItemsPagePojo;
import org.snomed.snowstorm.rest.pojo.ConceptBulkLoadRequest;
import org.snomed.snowstorm.util.ConceptControllerTestConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.*;
import static org.snomed.snowstorm.core.data.domain.Concepts.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TestConfig.class)
class ConceptControllerTest extends AbstractTest {

	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private BranchService branchService;

	@Autowired
	private BranchMergeService branchMergeService;

	@Autowired
	private ConceptService conceptService;

	@Autowired
	private CodeSystemService codeSystemService;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ReferenceSetMemberService referenceSetMemberService;

	private Date timepointWithOneRelationship;

	@BeforeEach
	void setup() throws ServiceException, InterruptedException {
		// Create dummy concept with descriptions containing quotes
		String conceptId = "257751006";
		Concept concept = conceptService.create(
				new Concept(conceptId)
						.addDescription(new Description("Wallace \"69\" side-to-end anastomosis - action (qualifier value)")
								.setTypeId(Concepts.FSN)
								.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
						.addDescription(new Description("Wallace \"69\" side-to-end anastomosis - action")
								.setTypeId(Concepts.SYNONYM)
								.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
						.addAxiom(new Relationship(Concepts.ISA, SNOMEDCT_ROOT)),
				"MAIN");

		// Add 1 second sleeps because the timepoint URI format uses second as the finest level
		Thread.sleep(1_000);

		// Create a project branch and add a relationship to the dummy concept
		branchService.create("MAIN/projectA");
		concept.getRelationships().add(new Relationship(Concepts.ISA, Concepts.CLINICAL_FINDING));
		conceptService.update(concept, "MAIN/projectA");

		Thread.sleep(1_000);

		// Make a note of the time the dummy concept had one relationship and two descriptions
		timepointWithOneRelationship = new Date();

		Thread.sleep(1_000);

		// Add a synonym on MAIN and rebase
		concept = conceptService.find(conceptId, "MAIN");
		concept.getDescriptions().add(new Description("New syn on MAIN"));
		conceptService.update(concept, "MAIN");
		branchMergeService.mergeBranchSync("MAIN", "MAIN/projectA", Collections.emptySet());

		// Add another relationship and description making two relationships and four descriptions
		concept = conceptService.find(conceptId, "MAIN/projectA");
		concept.getRelationships().add(new Relationship(Concepts.ISA, Concepts.CLINICAL_FINDING));
		concept.getDescriptions().add(new Description("Test"));
		conceptService.update(concept, "MAIN/projectA");

		// Version content to fill effectiveTime fields
		CodeSystem codeSystem = new CodeSystem("SNOMEDCT", "MAIN");
		codeSystemService.createCodeSystem(codeSystem);
		codeSystemService.createVersion(codeSystem, 20190731, "");
	}

	@Test
	void testLoadConceptTimepoints() {
		// Load initial version of dummy concept
		String timepoint = "@-";
		Concept initialConceptVersion = this.restTemplate.getForObject("http://localhost:" + port + "/browser/MAIN/projectA" + timepoint + "/concepts/257751006", Concept.class);
		assertEquals("257751006", initialConceptVersion.getConceptId());
		assertEquals(0, initialConceptVersion.getRelationships().size());
		assertEquals(2, initialConceptVersion.getDescriptions().size());

		// Load intermediate version of dummy concept
		timepoint = "@" + BranchTimepoint.DATE_FORMAT.format(timepointWithOneRelationship);
		System.out.println("Intermediate version timepoint " + timepoint);
		Concept intermediateConceptVersion = this.restTemplate.getForObject("http://localhost:" + port + "/browser/MAIN/projectA" + timepoint + "/concepts/257751006", Concept.class);
		assertEquals(1, intermediateConceptVersion.getRelationships().size());
		assertEquals(2, intermediateConceptVersion.getDescriptions().size());

		// Load base version of the concept (from parent branch)
		timepoint = "@^";
		Concept baseConceptVersion = this.restTemplate.getForObject("http://localhost:" + port + "/browser/MAIN/projectA" + timepoint + "/concepts/257751006", Concept.class);
		assertEquals(0, baseConceptVersion.getRelationships().size());
		assertEquals(3, baseConceptVersion.getDescriptions().size());

		// Load current version of dummy concept
		timepoint = "";
		Concept currentConceptVersion = this.restTemplate.getForObject("http://localhost:" + port + "/browser/MAIN/projectA" + timepoint + "/concepts/257751006", Concept.class);
		assertEquals(2, currentConceptVersion.getRelationships().size());
		assertEquals(4, currentConceptVersion.getDescriptions().size());
	}

	@Test
	void testQuotesEscapedAllConceptEndpoints() {
		// Browser Concept
		String responseBody = this.restTemplate.getForObject("http://localhost:" + port + "/browser/MAIN/concepts/257751006", String.class);
		System.out.println(responseBody);

		// Assert that quotes are escaped
		assertThat(responseBody).contains("\"Wallace \\\"69\\\" side-to-end anastomosis - action (qualifier value)\"");
		assertThat(responseBody).contains("\"Wallace \\\"69\\\" side-to-end anastomosis - action\"");


		// Simple Concept
		responseBody = this.restTemplate.getForObject("http://localhost:" + port + "/MAIN/concepts/257751006", String.class);
		System.out.println(responseBody);

		// Assert that quotes are escaped
		assertThat(responseBody).contains("\"Wallace \\\"69\\\" side-to-end anastomosis - action (qualifier value)\"");
		assertThat(responseBody).contains("\"Wallace \\\"69\\\" side-to-end anastomosis - action\"");


		// Simple Concept ECL
		HashMap<String, Object> urlVariables = new HashMap<>();
		urlVariables.put("ecl", "257751006");
		responseBody = this.restTemplate.getForObject("http://localhost:" + port + "/MAIN/concepts", String.class, urlVariables);
		System.out.println(responseBody);

		// Assert that quotes are escaped
		assertThat(responseBody).contains("\"Wallace \\\"69\\\" side-to-end anastomosis - action (qualifier value)\"");
		assertThat(responseBody).contains("\"Wallace \\\"69\\\" side-to-end anastomosis - action\"");
	}

	@Test
	void testConceptEndpointFields() throws IOException {
		// Browser Concept
		String responseBody = this.restTemplate.getForObject("http://localhost:" + port + "/browser/MAIN/concepts/257751006", String.class);
		checkFields(responseBody);
		LinkedHashMap<String, Object> properties = objectMapper.readValue(responseBody, LinkedHashMap.class);
		assertEquals("[conceptId, fsn, pt, active, effectiveTime, released, releasedEffectiveTime, moduleId, definitionStatus, " +
				"descriptions, classAxioms, gciAxioms, relationships, validationResults]", properties.keySet().toString());
		Object fsn = properties.get("fsn");
		assertEquals("LinkedHashMap", fsn.getClass().getSimpleName());
		assertEquals("{term=Wallace \"69\" side-to-end anastomosis - action (qualifier value), lang=en}", fsn.toString());

		// Simple Concept
		responseBody = this.restTemplate.getForObject("http://localhost:" + port + "/MAIN/concepts/257751006", String.class);
		checkFields(responseBody);

		// Simple Concept ECL
		HashMap<String, Object> urlVariables = new HashMap<>();
		urlVariables.put("ecl", "257751006");
		responseBody = this.restTemplate.getForObject("http://localhost:" + port + "/MAIN/concepts", String.class, urlVariables);
		checkFields(responseBody);
	}

	private void checkFields(String responseBody) {
		System.out.println(responseBody);
		assertThat(responseBody).doesNotContain("\"internalId\"");
		assertThat(responseBody).doesNotContain("\"start\"");
		assertThat(responseBody).doesNotContain("\"effectiveTimeI\"");
	}

	@Test
    void testConceptSearchWithCSVResults() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Accept", "text/csv");
        ResponseEntity<String> responseEntity = this.restTemplate.exchange("http://localhost:" + port + "/MAIN/projectA/concepts",
                HttpMethod.GET, new HttpEntity<>(null, headers), String.class, Collections.singletonMap("limit", 100));

        assertEquals(200, responseEntity.getStatusCode().value());
        String responseBody = responseEntity.getBody();
        assertNotNull(responseBody);
        try (BufferedReader reader = new BufferedReader(new StringReader(responseBody))) {
            String header = reader.readLine();
            assertEquals("id\tfsn\teffectiveTime\tactive\tmoduleId\tdefinitionStatus\tpt_900000000000508004\tpt_900000000000509007", header);
            assertEquals("257751006\tWallace \"69\" side-to-end anastomosis - action (qualifier value)\t\ttrue\t900000000000207008\tPRIMITIVE\t\tWallace \"69\" side-to-end anastomosis - action", reader.readLine());
        }
    }

    @Test
    void testConceptSearchWithLanguageRefsets() throws JSONException {
        String conceptId = "257751006";

		// Expected 1 concept found for US_EN language refset
	    ResponseEntity<String> responseEntity = this.restTemplate.exchange("http://localhost:" + port + "/MAIN/projectA/concepts?preferredOrAcceptableIn=" + Long.parseLong(Concepts.US_EN_LANG_REFSET) + "&conceptIds=" + conceptId,
                HttpMethod.GET, new HttpEntity<>(null), String.class);
        assertEquals(200, responseEntity.getStatusCode().value());
		String responseBody = responseEntity.getBody();
        assertNotNull(responseBody);
		JSONObject jsonObject = new JSONObject(responseBody);
		assertEquals(1, jsonObject.get("total"));

		// No result for invalid given language refset
		long belgiumDutchLanguageRefsetId = 31000172101L;
		responseEntity = this.restTemplate.exchange("http://localhost:" + port + "/MAIN/projectA/concepts?preferredOrAcceptableIn=" + belgiumDutchLanguageRefsetId + "&conceptIds=" + conceptId,
				HttpMethod.GET, new HttpEntity<>(null), String.class);
		assertEquals(200, responseEntity.getStatusCode().value());
		responseBody = responseEntity.getBody();
		assertNotNull(responseBody);
		jsonObject = new JSONObject(responseBody);
		assertEquals(0, jsonObject.get("total"));
    }

	@Test
	void testConceptSearchWithValidEclExpression() {
		String validEclExpression = "257751006 |Clinical finding|";

		ResponseEntity<String> responseEntity = this.restTemplate.exchange("http://localhost:" + port + "/MAIN/concepts?ecl=" + validEclExpression,
				HttpMethod.GET, new HttpEntity<>(null), String.class);
		assertEquals(200, responseEntity.getStatusCode().value());
	}

	@Test
	void testFailsConceptSearchWithInactiveConceptIdInEclExpression() throws ServiceException, JSONException {
		String conceptId = "257751006";
		Concept concept = conceptService.find(conceptId, "MAIN");
		concept.setActive(false);
		conceptService.update(concept, "MAIN");
		String eclExpressionWithInactiveConcept = "257751006 |Clinical finding|";

		ResponseEntity<String> responseEntity = this.restTemplate.exchange("http://localhost:" + port + "/MAIN/concepts?ecl=" + eclExpressionWithInactiveConcept,
				HttpMethod.GET, new HttpEntity<>(null), String.class);
		assertEquals(400, responseEntity.getStatusCode().value());
		String responseBody = responseEntity.getBody();
		JSONObject jsonObject = new JSONObject(responseBody);
		assertEquals("Concepts in the ECL request do not exist or are inactive on branch MAIN: 257751006.", jsonObject.get("message"));
	}

	@Test
	void testFailsConceptSearchWithNonexistentConceptIdInEclExpression() throws JSONException {
		String conceptId = "257751006";
		conceptService.deleteConceptAndComponents(conceptId, "MAIN", true);
		String eclExpressionWithNonexistentConcept = "257751006 |Clinical finding|";

		ResponseEntity<String> responseEntity = this.restTemplate.exchange("http://localhost:" + port + "/MAIN/concepts?ecl=" + eclExpressionWithNonexistentConcept,
				HttpMethod.GET, new HttpEntity<>(null), String.class);
		assertEquals(400, responseEntity.getStatusCode().value());
		String responseBody = responseEntity.getBody();
		JSONObject jsonObject = new JSONObject(responseBody);
		assertEquals("Concepts in the ECL request do not exist or are inactive on branch MAIN: 257751006.", jsonObject.get("message"));
	}

	@Test
	void testCreateConceptWithValidationEnabled() {
		branchService.updateMetadata("MAIN", ImmutableMap.of(
				"assertionGroupNames", "common-authoring",
				"previousRelease", "20210131",
				"defaultReasonerNamespace", "",
				"previousPackage", "prod_main_2021-01-31_20201124120000.zip"));
		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.set("Content-Type", "application/json");
		final String responseEntity = restTemplate.exchange(
				UriComponentsBuilder.fromUriString("http://localhost:" + port + "/browser/MAIN/concepts").queryParam("validate", "true").build().toUri(),
				HttpMethod.POST, new HttpEntity<>(ConceptControllerTestConstants.CONCEPT_WITH_VALIDATION_WARNINGS_ONLY, httpHeaders), String.class).toString();
		assertTrue(responseEntity.contains("200"));
		assertTrue(responseEntity.contains("Test resources were not available so assertions like case significance and US specific terms checks will not have run."));
	}

	@Test
	void testCreateConceptWithValidationEnabledWhichContainsErrorsReturnsBadRequest() {
		branchService.updateMetadata("MAIN", ImmutableMap.of(
				"assertionGroupNames", "common-authoring",
				"previousRelease", "20210131",
				"defaultReasonerNamespace", "",
				"previousPackage", "prod_main_2021-01-31_20201124120000.zip"));
		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.setContentType(MediaType.APPLICATION_JSON);
		final ResponseEntity<String> responseEntity = restTemplate.exchange(
				UriComponentsBuilder.fromUriString("http://localhost:" + port + "/browser/MAIN/concepts").queryParam("validate", "true").build().toUri(),
				HttpMethod.POST, new HttpEntity<>(ConceptControllerTestConstants.CONCEPT_WITH_VALIDATION_ERRORS_AND_WARNINGS, httpHeaders), String.class);
		assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
	}

	@Test
	void testUpdateConceptWithValidationEnabled() throws ServiceException {
		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.setContentType(MediaType.APPLICATION_JSON);
		branchService.updateMetadata("MAIN", ImmutableMap.of(
				"assertionGroupNames", "common-authoring",
				"previousRelease", "20210131",
				"defaultReasonerNamespace", "",
				"previousPackage", "prod_main_2021-01-31_20201124120000.zip"));
		conceptService.create(new Concept("99970008"), "MAIN");
		final String responseEntity = restTemplate.exchange(
				UriComponentsBuilder.fromUriString("http://localhost:" + port + "/browser/MAIN/concepts/99970008").queryParam("validate", "true").build().toUri(),
				HttpMethod.PUT, new HttpEntity<>(ConceptControllerTestConstants.CONCEPT_WITH_VALIDATION_WARNINGS_ONLY, httpHeaders), String.class).toString();
		assertTrue(responseEntity.contains("200"));
		assertTrue(responseEntity.contains("Test resources were not available so assertions like case significance and US specific terms checks will not have run."));
	}

	@Test
	void testBulkUpdateConcept() throws ServiceException {
		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.setContentType(MediaType.APPLICATION_JSON);
		conceptService.create(new Concept("99970008"), "MAIN");
		ResponseEntity<String> response = restTemplate.exchange(
				UriComponentsBuilder.fromUriString("http://localhost:" + port + "/browser/MAIN/concepts/bulk").build().toUri(),
				HttpMethod.POST, new HttpEntity<>("[" + ConceptControllerTestConstants.CONCEPT_WITH_VALIDATION_WARNINGS_ONLY + "]", httpHeaders), String.class);
		assertTrue(response.getStatusCode().is2xxSuccessful());

		ControllerTestHelper.waitForStatus(response, AsyncConceptChangeBatch.Status.COMPLETED.name(), AsyncConceptChangeBatch.Status.FAILED.name(),
				httpHeaders, restTemplate);
	}

	@Test
	void testBulkUpdateConceptUsingMissingBranch() throws ServiceException {
		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.setContentType(MediaType.APPLICATION_JSON);
		conceptService.create(new Concept("99970008"), "MAIN");
		ResponseEntity<String> response = restTemplate.exchange(
				UriComponentsBuilder.fromUriString("http://localhost:" + port + "/browser/MAIN/MISSING/concepts/bulk").build().toUri(),
				HttpMethod.POST, new HttpEntity<>("[" + ConceptControllerTestConstants.CONCEPT_WITH_VALIDATION_WARNINGS_ONLY + "]", httpHeaders), String.class);
		assertTrue(response.getStatusCode().is2xxSuccessful());

		// Expect failed status
		ControllerTestHelper.waitForStatus(response, AsyncConceptChangeBatch.Status.FAILED.name(), AsyncConceptChangeBatch.Status.COMPLETED.name(),
				httpHeaders, restTemplate);
	}

	@Test
	void testUpdateConceptWithValidationEnabledWhichContainsErrorsReturnsBadRequest() throws ServiceException {
		branchService.updateMetadata("MAIN", ImmutableMap.of(
				"assertionGroupNames", "common-authoring",
				"previousRelease", "20210131",
				"defaultReasonerNamespace", "",
				"previousPackage", "prod_main_2021-01-31_20201124120000.zip"));
		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.setContentType(MediaType.APPLICATION_JSON);
		conceptService.create(new Concept("9999005"), "MAIN");
		final ResponseEntity<ConceptView> responseEntity = restTemplate.exchange(
				UriComponentsBuilder.fromUriString("http://localhost:" + port + "/browser/MAIN/concepts/99970008").queryParam("validate", "true").build().toUri(),
				HttpMethod.PUT, new HttpEntity<>(ConceptControllerTestConstants.CONCEPT_WITH_VALIDATION_ERRORS_AND_WARNINGS, httpHeaders), ConceptView.class);
		assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
	}

	@Test
	void testECLSearchAfter() throws ServiceException {
		conceptService.create(new Concept(SNOMEDCT_ROOT), "MAIN");
		conceptService.create(new Concept(Concepts.CLINICAL_FINDING).addAxiom(new Relationship(Concepts.ISA, SNOMEDCT_ROOT)), "MAIN");
		assertEquals(3, conceptService.findAll("MAIN", PageRequest.of(1, 100)).getTotalElements());

		// Fetch first page
		ResponseEntity<ItemsPagePojo<ConceptMini>> responseEntity = this.restTemplate.exchange("http://localhost:" + port + "/MAIN/concepts?activeFilter=true&statedEcl=<138875005&limit=1",
				HttpMethod.GET, new HttpEntity<>(null), new ParameterizedTypeReference<>() {
				});
		assertEquals(200, responseEntity.getStatusCode().value());
		ItemsPagePojo<ConceptMini> page = responseEntity.getBody();
		assertNotNull(page);
		assertEquals(2L, page.getTotal());
		assertEquals(1, page.getItems().size());
		String conceptIdFromFirstPage = page.getItems().iterator().next().getConceptId();
		assertEquals("404684003", conceptIdFromFirstPage);
		String searchAfterFromFirstPage = page.getSearchAfter();
		assertNotNull(searchAfterFromFirstPage);

		// Fetch second page
		responseEntity = this.restTemplate.exchange("http://localhost:" + port + "/MAIN/concepts?activeFilter=true&statedEcl=<138875005&limit=1&searchAfter=" + searchAfterFromFirstPage,
				HttpMethod.GET, new HttpEntity<>(null), new ParameterizedTypeReference<>() {
				});
		assertEquals(200, responseEntity.getStatusCode().value());
		page = responseEntity.getBody();
		assertNotNull(page);
		assertEquals(1, page.getItems().size());
		String conceptIdFromSecondPage = page.getItems().iterator().next().getConceptId();
		assertEquals("257751006", conceptIdFromSecondPage);
		assertNotEquals(conceptIdFromFirstPage, conceptIdFromSecondPage);
	}


	@Test
	void testECLSearchAfterWithConceptIdsOnly() throws ServiceException {
		conceptService.create(new Concept(SNOMEDCT_ROOT), "MAIN");
		conceptService.create(new Concept(Concepts.CLINICAL_FINDING).addAxiom(new Relationship(Concepts.ISA, SNOMEDCT_ROOT)), "MAIN");
		assertEquals(3, conceptService.findAll("MAIN", PageRequest.of(1, 100)).getTotalElements());

		// Fetch all in one page
		ResponseEntity<ItemsPagePojo<Long>> responseEntity = this.restTemplate.exchange("http://localhost:" + port + "/MAIN/concepts?activeFilter=true&statedEcl=<138875005&returnIdOnly=true&limit=100",
				HttpMethod.GET, new HttpEntity<>(null), new ParameterizedTypeReference<ItemsPagePojo<Long>>() {});
		assertEquals(200, responseEntity.getStatusCode().value());
		ItemsPagePojo<Long> page = responseEntity.getBody();
		assertNotNull(page);
		assertEquals(2L, page.getTotal());
		assertEquals(2L, page.getItems().size());
		List<Long> results = page.getItems();

		// Fetch first page
		responseEntity = this.restTemplate.exchange("http://localhost:" + port + "/MAIN/concepts?activeFilter=true&statedEcl=<138875005&returnIdOnly=true&limit=1",
				HttpMethod.GET, new HttpEntity<>(null), new ParameterizedTypeReference<ItemsPagePojo<Long>>() {});
		assertEquals(200, responseEntity.getStatusCode().value());
		page = responseEntity.getBody();
		assertNotNull(page);
		assertEquals(2L, page.getTotal());
		assertEquals(1, page.getItems().size());
		Long conceptIdFromFirstPage = page.getItems().iterator().next();
		assertTrue(results.contains(conceptIdFromFirstPage));
		String searchAfterFromFirstPage = page.getSearchAfter();
		assertNotNull(searchAfterFromFirstPage);

		// Fetch second page
		responseEntity = this.restTemplate.exchange("http://localhost:" + port + "/MAIN/concepts?activeFilter=true&statedEcl=<138875005&returnIdOnly=true&limit=1&searchAfter=" + searchAfterFromFirstPage,
				HttpMethod.GET, new HttpEntity<>(null), new ParameterizedTypeReference<ItemsPagePojo<Long>>() {});
		assertEquals(200, responseEntity.getStatusCode().value());
		page = responseEntity.getBody();
		assertNotNull(page);
		assertEquals(1, page.getItems().size());
		Long conceptIdFromSecondPage = page.getItems().iterator().next();
		assertTrue(results.contains(conceptIdFromSecondPage));
		assertNotEquals(conceptIdFromFirstPage, conceptIdFromSecondPage);
	}


	@Test
	void testSearchAfter() throws ServiceException {
		conceptService.create(new Concept(Concepts.CLINICAL_FINDING), "MAIN");
		assertEquals(2, conceptService.findAll("MAIN", PageRequest.of(1, 100)).getTotalElements());

		// Fetch first page
		ResponseEntity<ItemsPagePojo<ConceptMini>> responseEntity = this.restTemplate.exchange("http://localhost:" + port + "/MAIN/concepts?activeFilter=true&limit=1",
				HttpMethod.GET, new HttpEntity<>(null), new ParameterizedTypeReference<ItemsPagePojo<ConceptMini>>() {});
		assertEquals(200, responseEntity.getStatusCode().value());
		ItemsPagePojo<ConceptMini> page = responseEntity.getBody();
		assertNotNull(page);
		assertEquals(2L, page.getTotal());
		assertEquals(1, page.getItems().size());
		String conceptIdFromFirstPage = page.getItems().iterator().next().getConceptId();
		assertEquals("404684003", conceptIdFromFirstPage);
		String searchAfterFromFirstPage = page.getSearchAfter();
		assertNotNull(searchAfterFromFirstPage);

		// Fetch second page
		System.out.println("searchAfter '" + searchAfterFromFirstPage + "'");
		responseEntity = this.restTemplate.exchange("http://localhost:" + port + "/MAIN/concepts?activeFilter=true&limit=1&searchAfter=" + searchAfterFromFirstPage,
				HttpMethod.GET, new HttpEntity<>(null), new ParameterizedTypeReference<ItemsPagePojo<ConceptMini>>() {});
		assertEquals(200, responseEntity.getStatusCode().value());
		page = responseEntity.getBody();
		assertNotNull(page);
		assertEquals(1, page.getItems().size());
		String conceptIdFromSecondPage = page.getItems().iterator().next().getConceptId();
		assertEquals("257751006", conceptIdFromSecondPage);
		assertNotEquals(conceptIdFromFirstPage, conceptIdFromSecondPage);
	}

	@Test
	public void findConceptHistory_ShouldReturnExpectedResponse_WhenConceptCannotBeFound() {
		//given
		String requestUrl = "http://localhost:" + port + "/browser/MAIN/concepts/12345/history";

		//when
		ResponseEntity<ConceptHistory> responseEntity = this.restTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(null), new ParameterizedTypeReference<ConceptHistory>() {
		});

		//then
		assertEquals(404, responseEntity.getStatusCodeValue());
	}

	@Test
	public void findConceptHistory_ShouldReturnExpectedResponse_WhenConceptFound() {
		//given
		String requestUrl = "http://localhost:" + port + "/browser/MAIN/concepts/257751006/history";

		//when
		ResponseEntity<ConceptHistory> responseEntity = this.restTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(null), new ParameterizedTypeReference<ConceptHistory>() {
		});

		//then
		assertEquals(200, responseEntity.getStatusCodeValue());
	}

	@Test
	public void findConceptHistory_ShouldReturnExpectedConceptHistory_WhenConceptHasHistory() throws Exception {
		//given
		String requestUrl = "http://localhost:" + port + "/browser/MAIN/BROWSE-241/concepts/123456789101112/history";
		Procedure methodTestDataFixture = () -> {
			String projectBranch = "MAIN/BROWSE-241";
			String taskBranch = "MAIN/BROWSE-241/TASK-1";
			//Create project branch
			CodeSystem codeSystem = new CodeSystem(projectBranch, projectBranch);
			codeSystemService.createCodeSystem(codeSystem);

			//Add Concepts to branch
			conceptService.create(
					new Concept("12345678910")
							.addDescription(new Description("Computer structure (computer structure)")
									.setTypeId(Concepts.FSN)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addDescription(new Description("Computer structure")
									.setTypeId(Concepts.SYNONYM)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addAxiom(new Relationship(Concepts.ISA, SNOMEDCT_ROOT)),
					projectBranch);

			conceptService.create(
					new Concept("1234567891011")
							.addDescription(new Description("Non specific site (computer structure)")
									.setTypeId(Concepts.FSN)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addDescription(new Description("Non specific site")
									.setTypeId(Concepts.SYNONYM)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addAxiom(new Relationship(Concepts.ISA, "12345678910")),
					projectBranch);

			conceptService.create(
					new Concept("123456789101112")
							.addDescription(new Description("Specific site (computer structure)")
									.setTypeId(Concepts.FSN)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addDescription(new Description("Specific site")
									.setTypeId(Concepts.SYNONYM)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addAxiom(new Relationship(Concepts.ISA, "12345678910"), new Relationship("12345", "12345678910"))
							.addRelationship(new Relationship(Concepts.ISA, "12345678910")),
					projectBranch);

			//Version project
			codeSystemService.createVersion(codeSystem, 20200131, "Release 2020-01-31.");

			//Create task branch
			branchService.create(taskBranch);

			//Update Concept with new Descriptions on task branch
			Concept concept = conceptService.find("123456789101112", projectBranch);
			concept.getDescriptions()
					.add(
							new Description("Specific")
									.setTypeId(Concepts.SYNONYM)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED)
					);
			conceptService.update(concept, taskBranch);

			//Promote task
			branchMergeService.mergeBranchSync(taskBranch, projectBranch, Collections.emptySet());

			//Version project
			codeSystemService.createVersion(codeSystem, 20200731, "Release 2020-07-31.");
		};
		methodTestDataFixture.insert();

		//when
		ResponseEntity<ConceptHistory> responseEntity = this.restTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(null), new ParameterizedTypeReference<ConceptHistory>() {
		});
		ConceptHistory conceptHistory = responseEntity.getBody();
		List<ConceptHistory.ConceptHistoryItem> history = conceptHistory.getHistory();
		ConceptHistory.ConceptHistoryItem januaryRelease = conceptHistory.getConceptHistoryItem("20200131").get();
		ConceptHistory.ConceptHistoryItem julyRelease = conceptHistory.getConceptHistoryItem("20200731").get();
		List<ComponentType> januaryReleaseComponentTypes = new ArrayList<>(januaryRelease.getComponentTypes());
		List<ComponentType> julyReleaseComponentTypes = new ArrayList<>(julyRelease.getComponentTypes());

		//then
		assertEquals(200, responseEntity.getStatusCodeValue());
		assertEquals(2, history.size()); //Concept has changed since previous version.
		assertEquals(4, januaryReleaseComponentTypes.size()); //Concept was created with Description, Relationship & Axiom
		assertEquals(1, julyReleaseComponentTypes.size()); //Description was added
		assertEquals(ComponentType.Concept, januaryReleaseComponentTypes.get(0));
		assertEquals(ComponentType.Description, januaryReleaseComponentTypes.get(1));
		assertEquals(ComponentType.Relationship, januaryReleaseComponentTypes.get(2));
		assertEquals(ComponentType.Axiom, januaryReleaseComponentTypes.get(3));
		assertEquals(ComponentType.Description, julyReleaseComponentTypes.get(0));
	}

	@Test
	public void findConceptHistory_ShouldReturnExpectedConceptHistory_WhenConceptHasNoHistory() throws Exception {
		//given
		String requestUrl = "http://localhost:" + port + "/browser/MAIN/BROWSE-241/concepts/123456789101112/history";
		Procedure methodTestDataFixture = () -> {
			String projectBranch = "MAIN/BROWSE-241";
			//Create project branch
			CodeSystem codeSystem = new CodeSystem(projectBranch, projectBranch);
			codeSystemService.createCodeSystem(codeSystem);

			//Add Concepts to branch
			conceptService.create(
					new Concept("12345678910")
							.addDescription(new Description("Computer structure (computer structure)")
									.setTypeId(Concepts.FSN)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addDescription(new Description("Computer structure")
									.setTypeId(Concepts.SYNONYM)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addAxiom(new Relationship(Concepts.ISA, SNOMEDCT_ROOT)),
					projectBranch);

			conceptService.create(
					new Concept("1234567891011")
							.addDescription(new Description("Non specific site (computer structure)")
									.setTypeId(Concepts.FSN)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addDescription(new Description("Non specific site")
									.setTypeId(Concepts.SYNONYM)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addAxiom(new Relationship(Concepts.ISA, "12345678910")),
					projectBranch);

			conceptService.create(
					new Concept("123456789101112")
							.addDescription(new Description("Specific site (computer structure)")
									.setTypeId(Concepts.FSN)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addDescription(new Description("Specific site")
									.setTypeId(Concepts.SYNONYM)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addAxiom(new Relationship(Concepts.ISA, "12345678910"), new Relationship("12345", "12345678910"))
							.addRelationship(new Relationship(Concepts.ISA, "12345678910")),
					projectBranch);

			//Version project
			codeSystemService.createVersion(codeSystem, 20200131, "Release 2020-01-31.");
		};
		methodTestDataFixture.insert();

		//when
		ResponseEntity<ConceptHistory> responseEntity = this.restTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(null), new ParameterizedTypeReference<ConceptHistory>() {
		});
		ConceptHistory conceptHistory = responseEntity.getBody();
		List<ConceptHistory.ConceptHistoryItem> history = conceptHistory.getHistory();
		ConceptHistory.ConceptHistoryItem januaryRelease = conceptHistory.getConceptHistoryItem("20200131").get();
		List<ComponentType> januaryReleaseComponentTypes = new ArrayList<>(januaryRelease.getComponentTypes());

		//then
		assertEquals(200, responseEntity.getStatusCodeValue());
		assertEquals(1, history.size()); //Concept has not changed since first release.
		assertEquals(4, januaryReleaseComponentTypes.size()); //Concept was created with Description, Relationship & Axiom
		assertEquals(ComponentType.Concept, januaryReleaseComponentTypes.get(0));
		assertEquals(ComponentType.Description, januaryReleaseComponentTypes.get(1));
		assertEquals(ComponentType.Relationship, januaryReleaseComponentTypes.get(2));
		assertEquals(ComponentType.Axiom, januaryReleaseComponentTypes.get(3));
	}

	@Test
	public void findConceptHistory_ShouldReturnExpectedConceptHistory_WhenShowFutureVersionsFlagIsFalse() throws Exception {
		//given
		String requestUrl = "http://localhost:" + port + "/browser/MAIN/BROWSE-241/concepts/123456789101112/history";
		Procedure methodTestDataFixture = () -> {
			String projectBranch = "MAIN/BROWSE-241";
			String taskBranch = "MAIN/BROWSE-241/TASK-1";
			//Create project branch
			CodeSystem codeSystem = new CodeSystem(projectBranch, projectBranch);
			codeSystemService.createCodeSystem(codeSystem);

			//Add Concepts to branch
			conceptService.create(
					new Concept("12345678910")
							.addDescription(new Description("Computer structure (computer structure)")
									.setTypeId(Concepts.FSN)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addDescription(new Description("Computer structure")
									.setTypeId(Concepts.SYNONYM)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addAxiom(new Relationship(Concepts.ISA, SNOMEDCT_ROOT)),
					projectBranch);

			conceptService.create(
					new Concept("1234567891011")
							.addDescription(new Description("Non specific site (computer structure)")
									.setTypeId(Concepts.FSN)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addDescription(new Description("Non specific site")
									.setTypeId(Concepts.SYNONYM)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addAxiom(new Relationship(Concepts.ISA, "12345678910")),
					projectBranch);

			conceptService.create(
					new Concept("123456789101112")
							.addDescription(new Description("Specific site (computer structure)")
									.setTypeId(Concepts.FSN)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addDescription(new Description("Specific site")
									.setTypeId(Concepts.SYNONYM)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addAxiom(new Relationship(Concepts.ISA, "12345678910"), new Relationship("12345", "12345678910"))
							.addRelationship(new Relationship(Concepts.ISA, "12345678910")),
					projectBranch);

			//Version project
			codeSystemService.createVersion(codeSystem, 20200131, "Release 2020-01-31.");

			//Create task branch
			branchService.create(taskBranch);

			//Update Concept with new Descriptions on task branch
			Concept concept = conceptService.find("123456789101112", projectBranch);
			concept.getDescriptions()
					.add(
							new Description("Specific")
									.setTypeId(Concepts.SYNONYM)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED)
					);
			conceptService.update(concept, taskBranch);

			//Promote task
			branchMergeService.mergeBranchSync(taskBranch, projectBranch, Collections.emptySet());

			//Version project
			Date date = new Date();
			Calendar c = Calendar.getInstance();
			c.setTime(date);
			c.add(Calendar.YEAR, 1);
			date = c.getTime();
			String nextYear = new SimpleDateFormat("yyyyMMdd").format(date);

			codeSystemService.createVersion(codeSystem, Integer.parseInt(nextYear), "");
		};
		methodTestDataFixture.insert();

		//when
		ResponseEntity<ConceptHistory> responseEntity = this.restTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(null), new ParameterizedTypeReference<ConceptHistory>() {
		});
		ConceptHistory conceptHistory = responseEntity.getBody();
		List<ConceptHistory.ConceptHistoryItem> history = conceptHistory.getHistory();

		//then
		assertEquals(1, history.size()); //Future version shouldn't appear
	}

	@Test
	public void findConceptHistory_ShouldReturnExpectedConceptHistory_WhenShowFutureVersionsFlagIsTrue() throws Exception {
		//given
		String requestUrl = "http://localhost:" + port + "/browser/MAIN/BROWSE-241/concepts/123456789101112/history?showFutureVersions=true";
		Procedure methodTestDataFixture = () -> {
			String projectBranch = "MAIN/BROWSE-241";
			String taskBranch = "MAIN/BROWSE-241/TASK-1";
			//Create project branch
			CodeSystem codeSystem = new CodeSystem(projectBranch, projectBranch);
			codeSystemService.createCodeSystem(codeSystem);

			//Add Concepts to branch
			conceptService.create(
					new Concept("12345678910")
							.addDescription(new Description("Computer structure (computer structure)")
									.setTypeId(Concepts.FSN)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addDescription(new Description("Computer structure")
									.setTypeId(Concepts.SYNONYM)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addAxiom(new Relationship(Concepts.ISA, SNOMEDCT_ROOT)),
					projectBranch);

			conceptService.create(
					new Concept("1234567891011")
							.addDescription(new Description("Non specific site (computer structure)")
									.setTypeId(Concepts.FSN)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addDescription(new Description("Non specific site")
									.setTypeId(Concepts.SYNONYM)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addAxiom(new Relationship(Concepts.ISA, "12345678910")),
					projectBranch);

			conceptService.create(
					new Concept("123456789101112")
							.addDescription(new Description("Specific site (computer structure)")
									.setTypeId(Concepts.FSN)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addDescription(new Description("Specific site")
									.setTypeId(Concepts.SYNONYM)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
							.addAxiom(new Relationship(Concepts.ISA, "12345678910"), new Relationship("12345", "12345678910"))
							.addRelationship(new Relationship(Concepts.ISA, "12345678910")),
					projectBranch);

			//Version project
			codeSystemService.createVersion(codeSystem, 20200131, "Release 2020-01-31.");

			//Create task branch
			branchService.create(taskBranch);

			//Update Concept with new Descriptions on task branch
			Concept concept = conceptService.find("123456789101112", projectBranch);
			concept.getDescriptions()
					.add(
							new Description("Specific")
									.setTypeId(Concepts.SYNONYM)
									.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED)
					);
			conceptService.update(concept, taskBranch);

			//Promote task
			branchMergeService.mergeBranchSync(taskBranch, projectBranch, Collections.emptySet());

			//Version project
			Date date = new Date();
			Calendar c = Calendar.getInstance();
			c.setTime(date);
			c.add(Calendar.YEAR, 1);
			date = c.getTime();
			String nextYear = new SimpleDateFormat("yyyyMMdd").format(date);

			codeSystemService.createVersion(codeSystem, Integer.parseInt(nextYear), "");
		};
		methodTestDataFixture.insert();

		//when
		ResponseEntity<ConceptHistory> responseEntity = this.restTemplate.exchange(requestUrl, HttpMethod.GET, new HttpEntity<>(null), new ParameterizedTypeReference<ConceptHistory>() {
		});
		ConceptHistory conceptHistory = responseEntity.getBody();
		List<ConceptHistory.ConceptHistoryItem> history = conceptHistory.getHistory();

		//then
		assertEquals(2, history.size()); //Future version should appear
	}

	@Test
	void testCreateConceptWithConcreteValueInsideAxiomRelationship() throws ServiceException {
		final Concept createdConcept = new Concept("12345678910").addAxiom(new Relationship(ISA, SNOMEDCT_ROOT),
				Relationship.newConcrete("1142135004", ConcreteValue.newDecimal("#55.5")));
		createRangeConstraint("1142135004", "dec(>#0..)");
		conceptService.create(createdConcept, MAIN);

		final Concept retrievedConcept = conceptService.find("12345678910", MAIN);

		retrievedConcept.getClassAxioms().forEach(axiom -> axiom.getRelationships().forEach(relationship -> {
			final ConcreteValue concreteValue = relationship.getConcreteValue();
			if (concreteValue != null) {
				assertEquals("55.5", concreteValue.getValue());
				assertEquals("#55.5", concreteValue.getValueWithPrefix());
				assertEquals(ConcreteValue.DataType.DECIMAL, concreteValue.getDataType());
			} else {
				assertEquals(ISA, relationship.getTypeId());
			}
		}));
	}

	@Test
	void testUpdateConceptWithConcreteValueInsideAxiomRelationship() throws ServiceException {
		final Concept createdConcept = new Concept("12345678910").addAxiom(new Relationship(ISA, SNOMEDCT_ROOT),
				Relationship.newConcrete("1142135004", ConcreteValue.newDecimal("#55.5")));
		createRangeConstraint("1142135004", "dec(>#0..)");
		conceptService.create(createdConcept, MAIN);

		final Concept updatedConcept = new Concept("12345678910").addAxiom(new Relationship(ISA, SNOMEDCT_ROOT),
				Relationship.newConcrete("1142135004", ConcreteValue.newDecimal("#55.9")));
		conceptService.update(updatedConcept, MAIN);

		final Concept retrievedConcept = conceptService.find("12345678910", MAIN);

		retrievedConcept.getClassAxioms().forEach(axiom -> axiom.getRelationships().forEach(relationship -> {
			final ConcreteValue concreteValue = relationship.getConcreteValue();
			if (concreteValue != null) {
				assertEquals("55.9", concreteValue.getValue());
				assertEquals("#55.9", concreteValue.getValueWithPrefix());
				assertEquals(ConcreteValue.DataType.DECIMAL, concreteValue.getDataType());
			} else {
				assertEquals(ISA, relationship.getTypeId());
			}
		}));
	}

	@Test
	void testBulkLoadWithNullConceptIdentifiers() throws URISyntaxException {
		//given
		List<String> conceptIds = Arrays.asList("782964007", "255314001", null, null, null, "308490002");
		ConceptBulkLoadRequest conceptBulkLoadRequest = new ConceptBulkLoadRequest(conceptIds, Collections.emptySet());
		RequestEntity<?> request = new RequestEntity<>(conceptBulkLoadRequest, HttpMethod.POST, new URI("http://localhost:" + port + "/browser/MAIN/concepts/bulk-load"));

		//when
		ResponseEntity<?> responseEntity = this.restTemplate.exchange(request, Collection.class);

		//then
		assertEquals(200, responseEntity.getStatusCodeValue());
	}

	@Test
	void testBulkLoadWithNullDescriptionIdentifiers() throws URISyntaxException {
		//given
		List<String> conceptIds = Arrays.asList("782964007", "255314001", "308490002");
		Set<String> descriptionIds = Stream.of("3756961018", "3756960017", null, null, "705033019", "451847013").collect(Collectors.toSet());
		ConceptBulkLoadRequest conceptBulkLoadRequest = new ConceptBulkLoadRequest(conceptIds, descriptionIds);
		RequestEntity<?> request = new RequestEntity<>(conceptBulkLoadRequest, HttpMethod.POST, new URI("http://localhost:" + port + "/browser/MAIN/concepts/bulk-load"));

		//when
		ResponseEntity<?> responseEntity = this.restTemplate.exchange(request, Collection.class);

		//then
		assertEquals(200, responseEntity.getStatusCodeValue());
	}

	@Test
	void testAcceptLanguageHeaderWithWhitespaceBetweenValues() throws URISyntaxException {
		//given
		MultiValueMap<String, String> headers = new HttpHeaders();
		headers.add("Accept-Language", "en, us");
		RequestEntity<?> request = new RequestEntity<>(headers, HttpMethod.GET, new URI("http://localhost:" + port + "/browser/MAIN/concepts/257751006"));

		//when
		ResponseEntity<?> responseEntity = this.restTemplate.exchange(request, Concept.class);

		//then
		assertEquals(200, responseEntity.getStatusCodeValue());
	}

	@Test
	void testDescModuleNotModifiedWhenLangRefSetChanged() throws ServiceException {
		CodeSystem codeSystem = codeSystemService.createCodeSystem(new CodeSystem("SNOMEDCT-A1", "MAIN/SNOMEDCT-A1"));
		// A has Lait (food) as Acceptable
		branchService.updateMetadata("MAIN/SNOMEDCT-A1", Map.of(Config.DEFAULT_MODULE_ID_KEY, Concepts.CORE_MODULE));
		Concept concept = conceptService.create(new Concept()
				.addDescription(new Description("Milk (food)")
						.setTypeId(Concepts.FSN)
						.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
				.addDescription(new Description("Lait (food)")
						.setTypeId(Concepts.FSN)
						.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.ACCEPTABLE)) // B will change this to Preferred
				.addDescription(new Description("Milk")
						.setTypeId(Concepts.SYNONYM)
						.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
				.addAxiom(new Relationship(ISA, SNOMEDCT_ROOT)), "MAIN/SNOMEDCT-A1");
		assertExpectedModule(conceptService.find(concept.getId(), "MAIN/SNOMEDCT-A1"), "Lait (food)", Concepts.CORE_MODULE);

		// Version
		codeSystemService.createVersion(codeSystem, 20200131, "");

		// B changes Lait (food) to be Preferred
		branchService.create("MAIN/SNOMEDCT-A1/SNOMEDCT-B1", Map.of(Config.DEFAULT_MODULE_ID_KEY, Concepts.COMMON_FRENCH_MODULE));
		for (Description description : concept.getDescriptions()) {
			if (description.getTerm().equals("Lait (food)")) {
				description.clearLanguageRefsetMembers();
				description.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED);
			}
		}

		Concept updatedConcept = conceptService.update(concept, "MAIN/SNOMEDCT-A1/SNOMEDCT-B1");
		assertExpectedModule(updatedConcept, "Lait (food)", Concepts.CORE_MODULE); // Assert response from update
		
		Concept foundConcept = conceptService.find(concept.getId(), "MAIN/SNOMEDCT-A1/SNOMEDCT-B1");
		assertExpectedModule(foundConcept, "Lait (food)", Concepts.CORE_MODULE); // Assert response from find
	}

	@Test
	void testModuleIdNotRestoredWhenTermChanged() throws ServiceException {
		// A has Milk (food) as FSN
		branchService.create("MAIN/SNOMEDCT-A1", Map.of(Config.DEFAULT_MODULE_ID_KEY, Concepts.CORE_MODULE));
		Concept concept = conceptService.create(new Concept()
				.addDescription(new Description("Milk (food)")
						.setTypeId(Concepts.FSN)
						.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
				.addDescription(new Description("Milk")
						.setTypeId(Concepts.SYNONYM)
						.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
				.addAxiom(new Relationship(ISA, SNOMEDCT_ROOT)), "MAIN/SNOMEDCT-A1");
		assertExpectedModule(conceptService.find(concept.getId(), "MAIN/SNOMEDCT-A1"), "Milk (food)", Concepts.CORE_MODULE);

		// B changes FSN to be Lait (food)
		branchService.create("MAIN/SNOMEDCT-A1/SNOMEDCT-B1", Map.of(Config.DEFAULT_MODULE_ID_KEY, Concepts.COMMON_FRENCH_MODULE));
		for (Description description : concept.getDescriptions()) {
			if (description.getTerm().equals("Milk (food)")) {
				description.setTerm("Lait (food)");
			}
		}

		assertExpectedModule(conceptService.update(concept, "MAIN/SNOMEDCT-A1/SNOMEDCT-B1"), "Lait (food)", Concepts.COMMON_FRENCH_MODULE);
		assertExpectedModule(conceptService.find(concept.getId(), "MAIN/SNOMEDCT-A1/SNOMEDCT-B1"), "Lait (food)", Concepts.COMMON_FRENCH_MODULE);
	}

	@Test
	void testModuleIdNotRestoredWhenTypeChanged() throws ServiceException {
		// A has Milk as Synonym
		branchService.create("MAIN/SNOMEDCT-A1", Map.of(Config.DEFAULT_MODULE_ID_KEY, Concepts.CORE_MODULE));
		Concept concept = conceptService.create(new Concept()
				.addDescription(new Description("Milk (food)")
						.setTypeId(Concepts.FSN)
						.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
				.addDescription(new Description("Milk")
						.setTypeId(Concepts.SYNONYM)
						.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
				.addAxiom(new Relationship(ISA, SNOMEDCT_ROOT)), "MAIN/SNOMEDCT-A1");
		assertExpectedModule(conceptService.find(concept.getId(), "MAIN/SNOMEDCT-A1"), "Milk", Concepts.CORE_MODULE);

		// B changes Milk to Text Definition
		branchService.create("MAIN/SNOMEDCT-A1/SNOMEDCT-B1", Map.of(Config.DEFAULT_MODULE_ID_KEY, Concepts.COMMON_FRENCH_MODULE));
		for (Description description : concept.getDescriptions()) {
			if (description.getTerm().equals("Milk")) {
				description.setTypeId(Concepts.TEXT_DEFINITION);
			}
		}

		assertExpectedModule(conceptService.update(concept, "MAIN/SNOMEDCT-A1/SNOMEDCT-B1"), "Milk", Concepts.COMMON_FRENCH_MODULE);
		assertExpectedModule(conceptService.find(concept.getId(), "MAIN/SNOMEDCT-A1/SNOMEDCT-B1"), "Milk", Concepts.COMMON_FRENCH_MODULE);
	}

	@Test
	void testModuleIdNotRestoredWhenActiveChanged() throws ServiceException {
		// A has Milk as active
		branchService.create("MAIN/SNOMEDCT-A1", Map.of(Config.DEFAULT_MODULE_ID_KEY, Concepts.CORE_MODULE));
		Concept concept = conceptService.create(new Concept()
				.addDescription(new Description("Milk (food)")
						.setTypeId(Concepts.FSN)
						.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
				.addDescription(new Description("Milk")
						.setTypeId(Concepts.SYNONYM)
						.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
				.addAxiom(new Relationship(ISA, SNOMEDCT_ROOT)), "MAIN/SNOMEDCT-A1");
		assertExpectedModule(conceptService.find(concept.getId(), "MAIN/SNOMEDCT-A1"), "Milk", Concepts.CORE_MODULE);

		// B changes Milk to be inactive
		branchService.create("MAIN/SNOMEDCT-A1/SNOMEDCT-B1", Map.of(Config.DEFAULT_MODULE_ID_KEY, Concepts.COMMON_FRENCH_MODULE));
		for (Description description : concept.getDescriptions()) {
			if (description.getTerm().equals("Milk")) {
				description.setActive(false);
			}
		}

		assertExpectedModule(conceptService.update(concept, "MAIN/SNOMEDCT-A1/SNOMEDCT-B1"), "Milk", Concepts.COMMON_FRENCH_MODULE);
		assertExpectedModule(conceptService.find(concept.getId(), "MAIN/SNOMEDCT-A1/SNOMEDCT-B1"), "Milk", Concepts.COMMON_FRENCH_MODULE);
	}

	@Test
	void testMemberReturnedInHeadersWhenCreatingReferenceSetConcept() throws ServiceException, JsonProcessingException {
		// data set up
		givenReferenceSetsExist();
		givenRefSetAncestorsExist();

		// create test reference set concept
		ResponseEntity<String> postConcept = postConcept(ConceptControllerTestConstants.CONCEPT_REFERENCE_SET_SIMPLE);
		Concept concept = objectMapper.readValue(postConcept.getBody(), Concept.class);

		// find member associating test concept with descriptor
		ResponseEntity<String> getMembers = getDescriptorMembers(concept);
		RefSetMemberPageWithBucketAggregations<LinkedHashMap> members = objectMapper.readValue(getMembers.getBody(), RefSetMemberPageWithBucketAggregations.class);

		// response from headers should match those from manual search
		String descriptorsInHeader = postConcept.getHeaders().get("Descriptors").iterator().next();
		String[] a = descriptorsInHeader.split(",");
		Arrays.sort(a); // Identifiers in order

		String descriptorsInSearch = members.getContent().stream().map(member -> (String) member.get("memberId")).collect(Collectors.joining(","));
		String[] b = descriptorsInSearch.split(",");
		Arrays.sort(b); // Identifiers in order

		assertThat(a).isEqualTo(b);
	}

	@Test
	void testMemberReturnedInHeadersWhenUpdatingReferenceSetConcept() throws ServiceException, JsonProcessingException {
		// data set up
		givenReferenceSetsExist();
		givenRefSetAncestorsExist();

		// create test concept
		ResponseEntity<String> postConcept = postConcept(ConceptControllerTestConstants.CONCEPT_REFERENCE_SET_SIMPLE);
		Concept concept = objectMapper.readValue(postConcept.getBody(), Concept.class);
		assertNotNull(postConcept.getHeaders().get("Descriptors"));

		// edit test concept
		ResponseEntity<String> putConcept = putConcept(concept.getConceptId(), ConceptControllerTestConstants.CONCEPT_REFERENCE_SET_SIMPLE);
		concept = objectMapper.readValue(postConcept.getBody(), Concept.class);
		assertNotNull(putConcept.getHeaders().get("Descriptors"));

		// find member associating test concept with descriptor
		ResponseEntity<String> getMembers = getDescriptorMembers(concept);
		RefSetMemberPageWithBucketAggregations<LinkedHashMap> members = objectMapper.readValue(getMembers.getBody(), RefSetMemberPageWithBucketAggregations.class);
		assertThat(members).isNotEmpty();

		// response from headers should match those from manual search
		String descriptorsInPostHeader = postConcept.getHeaders().get("Descriptors").iterator().next();
		String[] a = descriptorsInPostHeader.split(",");
		Arrays.sort(a); // Identifiers in order

		String descriptorsInPutHeader = putConcept.getHeaders().get("Descriptors").iterator().next();
		String[] b = descriptorsInPutHeader.split(",");
		Arrays.sort(b); // Identifiers in order

		String descriptorsInSearch = members.getContent().stream().map(member -> (String) member.get("memberId")).collect(Collectors.joining(","));
		String[] c = descriptorsInSearch.split(",");
		Arrays.sort(c); // Identifiers in order

		assertThat(a).isEqualTo(b);
		assertThat(b).isEqualTo(c);
	}

	@Test
	void testMemberNotReturnedInHeadersWhenCreatingNonReferenceSetConcept() throws ServiceException, JsonProcessingException {
		// data set up
		givenReferenceSetsExist();
		givenRefSetAncestorsExist();

		// create test concept
		ResponseEntity<String> postConcept = postConcept(ConceptControllerTestConstants.CONCEPT_WITH_VALIDATION_WARNINGS_ONLY);
		Concept concept = objectMapper.readValue(postConcept.getBody(), Concept.class);
		assertNull(postConcept.getHeaders().get("Descriptors"));

		// find member associating test concept with descriptor (should be none)
		ResponseEntity<String> getMembers = getDescriptorMembers(concept);
		RefSetMemberPageWithBucketAggregations<LinkedHashMap> members = objectMapper.readValue(getMembers.getBody(), RefSetMemberPageWithBucketAggregations.class);
		assertThat(members).isEmpty();
	}

	@Test
	void testDonateConcepts_SourceBranchNotAVersionBranch() throws ServiceException {
		String sourceBranch = "MAIN/SNOMEDCT-BE";
		String destinationBranch = "MAIN/A1";

		// Create extension code system SNOMEDCT-BE
		CodeSystem extension = createSourceCodeSystem("SNOMEDCT-BE", sourceBranch);

		// Create a concept to copy on MAIN/SNOMEDCT-BE
		givenConceptExists("100001", sourceBranch, false, false);

		// Version extension
		codeSystemService.createVersion(extension, 20220228, "February 2022");

		// Create a task under MAIN
		branchService.create(destinationBranch);

		String ecl = "100001 | Milk (food) |";

		ResponseEntity<String> responseEntity = donateConcepts(ecl, sourceBranch, destinationBranch, false);
		assertFalse(responseEntity.getStatusCode().equals(HttpStatus.OK));
		assertTrue(responseEntity.getBody().contains("Source branch must be a version branch."));
	}

	@Test
	void testDonateConcepts() throws ServiceException {
		String sourceBranch = "MAIN/SNOMEDCT-BE";
		String destinationBranch = "MAIN/A1";

		// Create extension code system SNOMEDCT-BE
		CodeSystem extension = createSourceCodeSystem("SNOMEDCT-BE", sourceBranch);

		// Create a concept to copy on MAIN/SNOMEDCT-BE
		givenConceptExists("100001", sourceBranch, false, false);

		// Version extension
		codeSystemService.createVersion(extension, 20220228, "February 2022");

		// Create a task under MAIN
		branchService.create(destinationBranch);

		String ecl = "100001 | Milk (food) |";

		ResponseEntity<String> responseEntity = donateConcepts(ecl, codeSystemService.findVersion("SNOMEDCT-BE", 20220228).getBranchPath(), destinationBranch, false);
		assertTrue(responseEntity.getStatusCode().equals(HttpStatus.OK));

		Concept concept = conceptService.find("100001", destinationBranch);
		assertNull(concept.getEffectiveTimeI());
		assertEquals(CORE_MODULE, concept.getModuleId());

		List<Description> descriptions = concept.getActiveDescriptions();
		assertEquals(3, descriptions.size());
		descriptions.forEach(description -> {
			assertDescription(description, destinationBranch, CORE_MODULE);
		});

		List<ReferenceSetMember> axioms = referenceSetMemberService.findMembers(destinationBranch, concept.getConceptId(), ComponentService.LARGE_PAGE).getContent();
		assertEquals(1, axioms.size());
		axioms.forEach(axiom -> {
			assertRefsetMember(axiom, CORE_MODULE, OWL_AXIOM_REFERENCE_SET);
		});
	}

	@Test
	void testDonateConceptsWithDependencies() throws ServiceException {
		String sourceBranch = "MAIN/SNOMEDCT-BE";
		String destinationBranch = "MAIN/A1";

		// Create extension code system SNOMEDCT-BE
		CodeSystem extension = createSourceCodeSystem("SNOMEDCT-BE", sourceBranch);

		// Create a concept to copy on MAIN/SNOMEDCT-BE
		givenConceptExists("100001", sourceBranch, true, false);

		// Version extension
		codeSystemService.createVersion(extension, 20220228, "February 2022");

		// Create a task under MAIN
		branchService.create(destinationBranch);

		String ecl = "100001 | Milk (food) |";

		ResponseEntity<String> responseEntity = donateConcepts(ecl, codeSystemService.findVersion("SNOMEDCT-BE", 20220228).getBranchPath(), destinationBranch, true);
		assertTrue(responseEntity.getStatusCode().equals(HttpStatus.OK));

		// Concept defined in ECL
		Concept concept = conceptService.find("100001", destinationBranch);
		assertNull(concept.getEffectiveTimeI());
		assertEquals(CORE_MODULE, concept.getModuleId());

		List<Description> descriptions = concept.getActiveDescriptions();
		assertEquals(3, descriptions.size());
		descriptions.forEach(description -> {
			assertDescription(description, destinationBranch, CORE_MODULE);
		});

		List<ReferenceSetMember> axioms = referenceSetMemberService.findMembers(destinationBranch, concept.getConceptId(), ComponentService.LARGE_PAGE).getContent();
		assertEquals(1, axioms.size());
		axioms.forEach(axiom -> {
			assertRefsetMember(axiom, CORE_MODULE, OWL_AXIOM_REFERENCE_SET);
		});

		// Dependant concept
		concept = conceptService.find("100000", destinationBranch);
		assertNull(concept.getEffectiveTimeI());
		assertEquals(CORE_MODULE, concept.getModuleId());

		descriptions = concept.getActiveDescriptions();
		assertEquals(1, descriptions.size());
		descriptions.forEach(description -> {
			assertDescription(description, destinationBranch, CORE_MODULE);
		});

		axioms = referenceSetMemberService.findMembers(destinationBranch, concept.getConceptId(), ComponentService.LARGE_PAGE).getContent();
		assertEquals(1, axioms.size());
		axioms.forEach(axiom -> {
			assertRefsetMember(axiom, CORE_MODULE, OWL_AXIOM_REFERENCE_SET);
		});
	}

	@Test
	void testDonateConceptsWithConcreteValues() throws ServiceException {
		String sourceBranch = "MAIN/SNOMEDCT-BE";
		String destinationBranch = "MAIN/A1";

		// Create extension code system SNOMEDCT-BE
		CodeSystem extension = createSourceCodeSystem("SNOMEDCT-BE", sourceBranch);

		// Create a concept to copy on MAIN/SNOMEDCT-BE
		givenConceptExists("100001", sourceBranch, false, true);

		// Version extension
		codeSystemService.createVersion(extension, 20220228, "February 2022");

		// Create a task under MAIN
		branchService.create(destinationBranch);

		String ecl = "100001 | Milk (food) |";

		ResponseEntity<String> responseEntity = donateConcepts(ecl, codeSystemService.findVersion("SNOMEDCT-BE", 20220228).getBranchPath(), destinationBranch, false);
		assertTrue(responseEntity.getStatusCode().equals(HttpStatus.OK));

		Concept concept = conceptService.find("100001", destinationBranch);
		assertNull(concept.getEffectiveTimeI());
		assertEquals(CORE_MODULE, concept.getModuleId());

		List<Description> descriptions = concept.getActiveDescriptions();
		assertEquals(3, descriptions.size());
		descriptions.forEach(description -> {
			assertDescription(description, destinationBranch, CORE_MODULE);
		});

		List<ReferenceSetMember> axioms = referenceSetMemberService.findMembers(destinationBranch, concept.getConceptId(), ComponentService.LARGE_PAGE).getContent();
		assertEquals(1, axioms.size());
		axioms.forEach(axiom -> {
			assertRefsetMember(axiom, CORE_MODULE, OWL_AXIOM_REFERENCE_SET);
		});
	}

	private ResponseEntity<String> putConcept(String conceptId, String conceptJson) {
		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.set("Content-Type", "application/json");
		HttpEntity<String> httpEntity = new HttpEntity<>(conceptJson, httpHeaders);
		URI requestUrl = UriComponentsBuilder.fromUriString("http://localhost:" + port + "/browser/MAIN/concepts/" + conceptId).queryParam("validate", "false").build().toUri();
		return restTemplate.exchange(requestUrl, HttpMethod.PUT, httpEntity, String.class);
	}

	private ResponseEntity<String> getDescriptorMembers(Concept concept) {
		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.set("Content-Type", "application/json");
		HttpEntity<String> httpEntity = new HttpEntity<>(null, httpHeaders);
		URI requestUrl = UriComponentsBuilder
				.fromUriString(
						"http://localhost:" + port + "/browser/MAIN/members"
				)
				.queryParam("referenceSet", Concepts.REFSET_DESCRIPTOR_REFSET)
				.queryParam("referencedComponentId", concept.getConceptId())
				.build()
				.toUri();

		return restTemplate.exchange(requestUrl, HttpMethod.GET, httpEntity, String.class);
	}

	private ResponseEntity<String> postConcept(String conceptJson) {
		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.set("Content-Type", "application/json");
		HttpEntity<String> httpEntity = new HttpEntity<>(conceptJson, httpHeaders);
		URI requestUrl = UriComponentsBuilder.fromUriString("http://localhost:" + port + "/browser/MAIN/concepts").queryParam("validate", "false").build().toUri();
		return restTemplate.exchange(requestUrl, HttpMethod.POST, httpEntity, String.class);
	}

	private void givenReferenceSetsExist() throws ServiceException {
		conceptService.create(new Concept(Concepts.SNOMEDCT_ROOT), MAIN);
		conceptService.create(new Concept(Concepts.ISA)
				.addAxiom(new Relationship(Concepts.ISA, Concepts.SNOMEDCT_ROOT)), MAIN);
		conceptService.create(new Concept(Concepts.REFSET)
				.addAxiom(new Relationship(Concepts.ISA, Concepts.FOUNDATION_METADATA)).setModuleId(Concepts.MODEL_MODULE), MAIN);
		conceptService.create(new Concept(Concepts.REFSET_SIMPLE)
				.addAxiom(new Relationship(Concepts.ISA, Concepts.REFSET)).setModuleId(Concepts.MODEL_MODULE), MAIN);
		conceptService.create(new Concept(Concepts.REFSET_DESCRIPTOR_REFSET)
				.addAxiom(new Relationship(Concepts.ISA, Concepts.REFSET)).setModuleId(Concepts.MODEL_MODULE), MAIN);
	}

	private void givenRefSetAncestorsExist() throws ServiceException {
		// Create top-level Concept
		Concept foodStructure = conceptService.create(
				new Concept()
						.addDescription(new Description("History (foundation metadata concept)"))
						.addAxiom(new Relationship(Concepts.ISA, Concepts.SNOMEDCT_ROOT)),
				"MAIN"
		);

		// Add top-level Concept to simple refset
		ReferenceSetMember foodStructureMember = new ReferenceSetMember(Concepts.MODEL_MODULE, Concepts.REFSET_SIMPLE, foodStructure.getId());
		referenceSetMemberService.createMember("MAIN", foodStructureMember);

		// Add simple refset to refset descriptor
		ReferenceSetMember refsetInDescriptor = new ReferenceSetMember(Concepts.MODEL_MODULE, Concepts.REFSET_DESCRIPTOR_REFSET, Concepts.REFSET_SIMPLE);
		refsetInDescriptor.setAdditionalFields(Map.of(
				"attributeDescription", Concepts.REFERENCED_COMPONENT,
				"attributeType", Concepts.CONCEPT_TYPE_COMPONENT,
				"attributeOrder", "0")
		);
		referenceSetMemberService.createMember("MAIN", refsetInDescriptor);

		refsetInDescriptor = new ReferenceSetMember(Concepts.MODEL_MODULE, Concepts.REFSET_DESCRIPTOR_REFSET, Concepts.REFSET_SIMPLE);
		refsetInDescriptor.setAdditionalFields(Map.of(
				"attributeDescription", Concepts.REFERENCED_COMPONENT,
				"attributeType", Concepts.CONCEPT_TYPE_COMPONENT,
				"attributeOrder", "1")
		);
		referenceSetMemberService.createMember("MAIN", refsetInDescriptor);
	}

	private CodeSystem createSourceCodeSystem(String shortName, String branchPath) {
		CodeSystem extension = codeSystemService.createCodeSystem(new CodeSystem(shortName, branchPath));

		branchService.updateMetadata(branchPath, ImmutableMap.of(
				Config.DEFAULT_MODULE_ID_KEY, "11000172109",
				Config.DEFAULT_NAMESPACE_KEY, "1000172",
				BranchMetadataKeys.DEPENDENCY_PACKAGE, "International_Release.zip"));

		return extension;
	}

	private void givenConceptExists(String conceptId, String branchPath, boolean isDependantConcept, boolean isConcreteValue) throws ServiceException {
		assertTrue(conceptService.exists("257751006", "MAIN"));

		Concept concept = new Concept(conceptId, "11000172109")
				.addDescription(new Description("Milk (food)")
						.setTypeId(Concepts.FSN)
						.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
				.addDescription(new Description("Milk")
						.setTypeId(Concepts.SYNONYM)
						.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.ACCEPTABLE))
				.addDescription(new Description("Lait")
						.setTypeId(Concepts.SYNONYM)
						.addLanguageRefsetMember("21000172104", Concepts.PREFERRED))
				.addDescription(new Description("Full-fat cow milk, ultra pasteurised, 3.5% fat")
						.setTypeId(Concepts.TEXT_DEFINITION)
						.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED));

		Set<Relationship> relationships = new HashSet<>();
		relationships.add(new Relationship(ISA, "257751006"));

		if (isDependantConcept) {
			// Create a dependant concept in the same module
			conceptService.create(new Concept("100000", "11000172109")
					.addDescription(new Description("Food")
							.setTypeId(Concepts.FSN)
							.addLanguageRefsetMember(Concepts.US_EN_LANG_REFSET, Concepts.PREFERRED))
					.addAxiom(new Relationship(ISA, "257751006")), branchPath);
			// Add a relationship
			relationships.add(new Relationship(ISA, "100000"));
		}
		if (isConcreteValue) {
			// Add a concrete value relationship
			relationships.add(Relationship.newConcrete("1142139005", ConcreteValue.newInteger("#1")));
		}

		concept.addAxiom(relationships.stream().toArray(Relationship[]::new));
		conceptService.create(concept, branchPath);
	}

	private ResponseEntity<String> donateConcepts(String ecl, String sourceBranch, String destinationBranch, boolean includeDependencies) {
		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.set("Content-Type", "application/json");

		return restTemplate.exchange(
				UriComponentsBuilder.fromUriString("http://localhost:" + port + "/" + destinationBranch + "/concepts/donate")
						.queryParam("sourceBranch", sourceBranch)
						.queryParam("ecl", ecl)
						.queryParam("includeDependencies", includeDependencies)
						.build().toUri(),
				HttpMethod.POST, null, String.class);
	}

	private void assertExpectedModule(Concept concept, String descriptionTerm, String expectedModuleId) {
		boolean found = false;
		for (Description description : concept.getDescriptions()) {
			if (description.getTerm().equals(descriptionTerm)) {
				found = true;
				assertEquals(expectedModuleId, description.getModuleId());
			}
		}

		if (!found) {
			fail("Cannot find Description.");
		}
	}

	protected interface Procedure {
		void insert() throws Exception;
	}

	private void assertDescription(Description description, String branchPath, String expectedModuleId) {
		assertNull(description.getEffectiveTimeI());
		assertEquals(expectedModuleId, description.getModuleId());

		Map<String, String> acceptabilityMap = description.getAcceptabilityMap();

		acceptabilityMap.forEach((referenceSetId, acceptability) -> {
			assertThat(referenceSetId.equals(US_EN_LANG_REFSET) || referenceSetId.equals(GB_EN_LANG_REFSET));
			assertThat(acceptability.equals(PREFERRED_CONSTANT) || acceptability.equals(ACCEPTABLE_CONSTANT));
		});

		List<ReferenceSetMember> langRefsetMembers = referenceSetMemberService.findMembers(branchPath, description.getDescriptionId(), ComponentService.LARGE_PAGE).getContent();
		if (acceptabilityMap.get(US_EN_LANG_REFSET).equals(PREFERRED_CONSTANT)) {
			assertEquals(2, langRefsetMembers.size());
			assertTrue(acceptabilityMap.containsKey(GB_EN_LANG_REFSET));
		} else {
			assertEquals(2, langRefsetMembers.size());
		}

		langRefsetMembers.forEach(langRefsetMember -> {
			String refsetId = langRefsetMember.getRefsetId();
			assertThat(refsetId.equals(US_EN_LANG_REFSET) || refsetId.equals(GB_EN_LANG_REFSET));
			assertRefsetMember(langRefsetMember, expectedModuleId, refsetId);
		});
	}

	private void assertRefsetMember(ReferenceSetMember refsetMember, String expectedModuleId, String expectedRefsetId) {
		assertNull(refsetMember.getEffectiveTimeI());
		assertEquals(expectedModuleId, refsetMember.getModuleId());
		assertEquals(expectedRefsetId, refsetMember.getRefsetId());
	}
}
