package org.snomed.snowstorm.core.data.services;

import io.kaicode.elasticvc.api.BranchService;
import io.kaicode.elasticvc.domain.Branch;
import io.kaicode.elasticvc.domain.Metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snomed.snowstorm.AbstractTest;
import org.snomed.snowstorm.core.data.domain.*;
import org.snomed.snowstorm.core.data.services.pojo.MemberSearchRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.*;

import static org.junit.Assert.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
/**
 * Use cases to be coded into test cases:
 * 1. BE where we have the Belgian edition dependent on the common French before the International
 * 2. NO where the majority of concepts are in a sub-module (can't make assumptions about "most concepts")
 * 3. NZ where module 1 and module 2 are mutually dependent on each other.  Module 1 has no content and is defined in Module 2.
 *
 */
class ModuleDependencyServiceTest extends AbstractTest {
	
	private static String TEST_MODULE = "10123400";
	private static String TEST_MODULE2 = "20123400";
	private static String TEST_ET = "20990131";
	private static String TEST_DEPENDENCY_ET = "19990131";
	private static String TEST_CS_INT = "SNOMEDCT-INT";
	private static String TEST_CS_MS = "SNOMEDCT-XY";
	private static String TEST_CS_PATH = "MAIN/" + TEST_CS_MS;

	@Autowired
	private CodeSystemService codeSystemService;
	
	@Autowired
	private ConceptService conceptService;

	@Autowired
	private BranchService branchService;

	@Autowired
	private ModuleDependencyService mdService;
	
	@Autowired
	private ReferenceSetMemberService rmService;


	@BeforeEach
	void setUp() throws Exception {
		branchService.deleteAll();
		conceptService.deleteAll();
		branchService.create("MAIN");
		conceptService.create(new Concept(Concepts.SNOMEDCT_ROOT), Branch.MAIN);
		conceptService.create(new Concept(Concepts.CORE_MODULE).setModuleId(Concepts.CORE_MODULE), Branch.MAIN);
		conceptService.create(new Concept(Concepts.MODEL_MODULE).setModuleId(Concepts.MODEL_MODULE), Branch.MAIN);
		
		CodeSystem codeSystemINT = new CodeSystem(TEST_CS_INT, Branch.MAIN);
		codeSystemService.createCodeSystem(codeSystemINT);
		
		//This is interesting because creating a version is now generating and persisting the MDR members
		//So even the setup is a good test.
		codeSystemService.createVersion(codeSystemINT, Integer.parseInt(TEST_DEPENDENCY_ET), "TESTING");
	}
	
	@Test
	void testVersionedCodeSystemGeneration() throws InterruptedException, ServiceException {
		MemberSearchRequest searchRequest = new MemberSearchRequest().referenceSet(Concepts.REFSET_MODULE_DEPENDENCY);
		Page<ReferenceSetMember> mdrPage = rmService.findMembers(Branch.MAIN, searchRequest, PageRequest.of(0,10));
		//The model module has no dependencies, so we only expect 1 row for the core module
		assertEquals(1, mdrPage.getContent().size());
		assertEquals(Concepts.CORE_MODULE, mdrPage.getContent().get(0).getModuleId());
		assertEquals(Concepts.MODEL_MODULE, mdrPage.getContent().get(0).getReferencedComponentId());
		assertEquals(TEST_DEPENDENCY_ET, mdrPage.getContent().get(0).getEffectiveTime());
		assertEquals(TEST_DEPENDENCY_ET, mdrPage.getContent().get(0).getAdditionalField(ModuleDependencyService.SOURCE_ET));
		assertEquals(TEST_DEPENDENCY_ET, mdrPage.getContent().get(0).getAdditionalField(ModuleDependencyService.TARGET_ET));
	}

	@Test
	void testInternationalMdrGeneration() throws InterruptedException, ServiceException {
		createConcept("116680003", Concepts.CORE_MODULE, Branch.MAIN);
		createConcept("10000200", Concepts.MODEL_MODULE, Branch.MAIN);
		Set<ReferenceSetMember> mdr = mdService.generateModuleDependencies(Branch.MAIN, TEST_ET, null, false, null);
		//The model module has no dependencies, so we only expect 1 row for the core module
		assertEquals(1, mdr.size());
		ReferenceSetMember first = mdr.iterator().next();
		assertEquals(Concepts.CORE_MODULE, first.getModuleId());
		assertEquals(Concepts.MODEL_MODULE, first.getReferencedComponentId());
		assertEquals(TEST_ET, first.getEffectiveTime());
		assertEquals(TEST_ET, first.getAdditionalField(ModuleDependencyService.SOURCE_ET));
		assertEquals(TEST_ET, first.getAdditionalField(ModuleDependencyService.TARGET_ET));
	}
	
	@Test
	void testMsMdrGeneration() throws InterruptedException, ServiceException {
		createConcept("116680003", Concepts.CORE_MODULE, Branch.MAIN);
		CodeSystem codeSystemXY = new CodeSystem(TEST_CS_MS, TEST_CS_PATH);
		
		//Creating the code system after MAIN has been versioned should ensure the dependencyVersionEffectiveTime 
		//is picked up from there
		codeSystemService.createCodeSystem(codeSystemXY);
		createConcept(TEST_MODULE, TEST_MODULE, TEST_CS_PATH);
		
		//To be detected as an extension, and therefore not include modules found on MAIN
		//We need to list a dependencyPackage in the metadata
		Metadata metadata = new Metadata();
		metadata.putString(BranchMetadataKeys.DEPENDENCY_PACKAGE, "Some Value");
		branchService.updateMetadata(TEST_CS_PATH, metadata);
		Set<ReferenceSetMember> mdr = mdService.generateModuleDependencies(TEST_CS_PATH, TEST_ET, null, false, null);
		
		//Working with a single MS module we expect to have dependencies to both the core and model module
		assertEquals(2, mdr.size());
		assertTrue(resultsContain(mdr, TEST_MODULE, Concepts.CORE_MODULE, TEST_ET, TEST_ET, TEST_DEPENDENCY_ET));
		assertTrue(resultsContain(mdr, TEST_MODULE, Concepts.MODEL_MODULE, TEST_ET, TEST_ET, TEST_DEPENDENCY_ET));
	}
	
	@Test
	void testCircularMdrGeneration() throws InterruptedException, ServiceException {
		createConcept("116680003", Concepts.CORE_MODULE, Branch.MAIN);
		CodeSystem codeSystemXY = new CodeSystem(TEST_CS_MS, TEST_CS_PATH);
		
		//Creating the code system after MAIN has been versioned should ensure the dependencyVersionEffectiveTime 
		//is picked up from there
		codeSystemService.createCodeSystem(codeSystemXY);
		createConcept(TEST_MODULE, TEST_MODULE, TEST_CS_PATH);
		
		//To be detected as an extension, and therefore not include modules found on MAIN
		//We need to list a dependencyPackage in the metadata
		Metadata metadata = new Metadata();
		metadata.putString(BranchMetadataKeys.DEPENDENCY_PACKAGE, "Some Value");
		branchService.updateMetadata(TEST_CS_PATH, metadata);
		
		//Now create two exisiting MDRS members that reference each other
		ReferenceSetMember rm1 = createRefsetMemberMdrs(TEST_MODULE, TEST_MODULE2);
		rmService.createMember(TEST_CS_PATH, rm1);
		
		ReferenceSetMember rm2 = createRefsetMemberMdrs(TEST_MODULE2, TEST_MODULE);
		rmService.createMember(TEST_CS_PATH, rm2);
		
		Set<ReferenceSetMember> mdr = mdService.generateModuleDependencies(TEST_CS_PATH, TEST_ET, null, false, null);
		
		//Working with a 2 mutually dependent modules we expect to see 6 entries
		//Each module to both the core and the model module (2 x 2 = 4)
		//And each module to the other (1 x 2 = 2)
		//Watch that TEST_ET is the time of the extension and the Depedency Time is the ET on MAIN
		assertEquals(6, mdr.size());
		assertTrue(resultsContain(mdr, TEST_MODULE, Concepts.CORE_MODULE, TEST_ET, TEST_ET, TEST_DEPENDENCY_ET));
		assertTrue(resultsContain(mdr, TEST_MODULE, Concepts.MODEL_MODULE, TEST_ET, TEST_ET, TEST_DEPENDENCY_ET));
		assertTrue(resultsContain(mdr, TEST_MODULE2, Concepts.CORE_MODULE, TEST_ET, TEST_ET, TEST_DEPENDENCY_ET));
		assertTrue(resultsContain(mdr, TEST_MODULE2, Concepts.MODEL_MODULE, TEST_ET, TEST_ET, TEST_DEPENDENCY_ET));
		assertTrue(resultsContain(mdr, TEST_MODULE, TEST_MODULE2, TEST_ET, TEST_ET, TEST_ET));
		assertTrue(resultsContain(mdr, TEST_MODULE2, TEST_MODULE, TEST_ET, TEST_ET, TEST_ET));
	}

	private ReferenceSetMember createRefsetMemberMdrs(String moduleId, String targetModuleId) {
		ReferenceSetMember rm = new ReferenceSetMember();
		rm.setMemberId(UUID.randomUUID().toString());
		rm.setModuleId(moduleId);
		rm.setRefsetId(Concepts.REFSET_MODULE_DEPENDENCY);
		rm.setReferencedComponentId(targetModuleId);
		rm.setActive(true);
		rm.setCreating(true);
		return rm;
	}

	private boolean resultsContain(Set<ReferenceSetMember> mdr, String sourceModule, String targetModule, String effectiveDate,
			String sourceEffectiveDate, String targetEffectiveDate) {
		for (ReferenceSetMember rm : mdr) {
			if (rm.getModuleId().equals(sourceModule) &&
					rm.getReferencedComponentId().equals(targetModule) &&
					rm.getEffectiveTime().equals(effectiveDate) &&
					rm.getAdditionalField(ModuleDependencyService.SOURCE_ET).equals(sourceEffectiveDate) &&
					rm.getAdditionalField(ModuleDependencyService.TARGET_ET).equals(targetEffectiveDate)) {
				return true;
			}
		}
		return false;
	}

	private void createConcept(String conceptId, String moduleId, String path) throws ServiceException {
		conceptService.create(
				new Concept(conceptId)
						.setModuleId(moduleId)
						.addDescription(
								new Description("Heart")
										.setCaseSignificance("CASE_INSENSITIVE")
										.setAcceptabilityMap(Collections.singletonMap(Concepts.US_EN_LANG_REFSET,
												Concepts.descriptionAcceptabilityNames.get(Concepts.ACCEPTABLE)))
						)
						.addDescription(
								new Description("Heart structure (body structure)")
										.setTypeId(Concepts.FSN)
										.setCaseSignificance("CASE_INSENSITIVE")
										.setAcceptabilityMap(Collections.singletonMap(Concepts.US_EN_LANG_REFSET,
												Concepts.descriptionAcceptabilityNames.get(Concepts.ACCEPTABLE))))
						.addRelationship(
								new Relationship(Concepts.ISA, Concepts.SNOMEDCT_ROOT)
						)
						.addAxiom(
								new Relationship(Concepts.ISA, Concepts.SNOMEDCT_ROOT)
						),
				path);
	}

}
