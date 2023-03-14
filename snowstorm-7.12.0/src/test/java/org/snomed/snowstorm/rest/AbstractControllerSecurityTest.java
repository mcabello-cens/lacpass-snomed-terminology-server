package org.snomed.snowstorm.rest;

import io.kaicode.elasticvc.api.BranchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.snomed.snowstorm.AbstractTest;
import org.snomed.snowstorm.TestConfig;
import org.snomed.snowstorm.core.data.domain.CodeSystem;
import org.snomed.snowstorm.core.data.domain.security.Role;
import org.snomed.snowstorm.core.data.services.CodeSystemService;
import org.snomed.snowstorm.core.data.services.PermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.net.URI;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;

import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TestConfig.class)
@ActiveProfiles(profiles = {"test", "secure-test"})
public abstract class AbstractControllerSecurityTest extends AbstractTest {

	@LocalServerPort
	protected int port;

	@Autowired
	protected TestRestTemplate restTemplate;

	@Autowired
	protected PermissionService permissionService;

	@Autowired
	protected BranchService branchService;

	@Autowired
	protected CodeSystemService codeSystemService;

	protected String url;
	protected HttpHeaders userWithoutRoleHeaders;
	protected HttpHeaders authorHeaders;
	protected HttpHeaders extensionAuthorHeaders;
	protected HttpHeaders multiExtensionAuthorHeaders;
	protected HttpHeaders extensionAdminHeaders;
	protected HttpHeaders globalAdminHeaders;
	protected CodeSystem extensionBCodeSystem;

	@Value("${ims-security.roles.enabled}")
	private boolean rolesEnabled;

	@BeforeEach
	void setup() {
		assertTrue(rolesEnabled, "Role based access control must be enabled for security tests.");

		url = "http://localhost:" + port;

		userWithoutRoleHeaders = new HttpHeaders();
		userWithoutRoleHeaders.add("X-AUTH-username", "userA");
		userWithoutRoleHeaders.add("X-AUTH-roles", "");

		authorHeaders = new HttpHeaders();
		authorHeaders.add("X-AUTH-username", "userB");
		authorHeaders.add("X-AUTH-roles", "int-author-group");

		extensionAuthorHeaders = new HttpHeaders();
		extensionAuthorHeaders.add("X-AUTH-username", "userC");
		extensionAuthorHeaders.add("X-AUTH-roles", "extensionA-author-group");

		multiExtensionAuthorHeaders = new HttpHeaders();
		multiExtensionAuthorHeaders.add("X-AUTH-username", "userC");
		multiExtensionAuthorHeaders.add("X-AUTH-roles", "int-author-group,extensionA-author-group");

		extensionAdminHeaders = new HttpHeaders();
		extensionAdminHeaders.add("X-AUTH-username", "userD");
		extensionAdminHeaders.add("X-AUTH-roles", "extensionA-admin,extensionB-admin");

		globalAdminHeaders = new HttpHeaders();
		globalAdminHeaders.add("X-AUTH-username", "userE");
		globalAdminHeaders.add("X-AUTH-roles", "snowstorm-admin");

		permissionService.setGlobalRoleGroups(Role.ADMIN, Collections.singleton("snowstorm-admin"));
		permissionService.setBranchRoleGroups("MAIN/SNOMEDCT-A", Role.ADMIN, Collections.singleton("extensionA-admin"));
		permissionService.setBranchRoleGroups("MAIN/SNOMEDCT-B", Role.ADMIN, Collections.singleton("extensionA-admin"));
		permissionService.setBranchRoleGroups("MAIN", Role.AUTHOR, Collections.singleton("int-author-group"));
		permissionService.setBranchRoleGroups("MAIN/SNOMEDCT-A", Role.AUTHOR, Collections.singleton("extensionA-author-group"));

		codeSystemService.createCodeSystem(new CodeSystem("SNOMEDCT", "MAIN"));

		branchService.create("MAIN/ProjectA");
		codeSystemService.createCodeSystem(new CodeSystem("SNOMEDCT-A", "MAIN/SNOMEDCT-A"));

		extensionBCodeSystem = new CodeSystem("SNOMEDCT-B", "MAIN/SNOMEDCT-B");
	}

	protected ResponseEntity<String> testStatusCode(HttpStatus expectedStatusCode, HttpHeaders imsHeaders, RequestEntity<Object> requestEntity) {
		return testStatusCode(expectedStatusCode.value(), imsHeaders, requestEntity);
	}

	protected ResponseEntity<Object> testExchange(HttpHeaders imsHeaders, RequestEntity<Object> requestEntity) {
		HttpHeaders combinedHeaders = new HttpHeaders();
		combinedHeaders.addAll(requestEntity.getHeaders());
		combinedHeaders.addAll(imsHeaders);

		return restTemplate.exchange(new RequestEntity<>(requestEntity.getBody(), combinedHeaders, requestEntity.getMethod(), requestEntity.getUrl()), Object.class);
	}

	protected ResponseEntity<String> testStatusCode(int expectedStatusCode, HttpHeaders imsHeaders, RequestEntity<Object> requestEntity) {
		HttpHeaders combinedHeaders = new HttpHeaders();
		combinedHeaders.addAll(requestEntity.getHeaders());
		combinedHeaders.addAll(imsHeaders);
		ResponseEntity<String> response = restTemplate.exchange(new RequestEntity<>(requestEntity.getBody(), combinedHeaders, requestEntity.getMethod(), requestEntity.getUrl()), String.class);
		assertEquals(expectedStatusCode, response.getStatusCodeValue(), response.getBody());
		return response;
	}


}
