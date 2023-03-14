package org.snomed.snowstorm.core.data.services;

import io.kaicode.elasticvc.api.BranchService;
import io.kaicode.elasticvc.domain.Commit;
import org.assertj.core.util.Lists;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.snomed.snowstorm.AbstractTest;
import org.snomed.snowstorm.core.data.services.pojo.AsyncRefsetMemberChangeBatch;
import org.snomed.snowstorm.core.data.domain.*;
import org.snomed.snowstorm.core.data.services.pojo.MemberSearchRequest;
import org.snomed.snowstorm.core.data.services.pojo.PageWithBucketAggregations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.snomed.snowstorm.core.data.services.ReferenceSetMemberService.AGGREGATION_MEMBER_COUNTS_BY_REFERENCE_SET;
import static org.snomed.snowstorm.core.data.services.pojo.AsyncRefsetMemberChangeBatch.Status.*;

@ExtendWith(SpringExtension.class)
class ReferenceSetMemberServiceTest extends AbstractTest {

	@Autowired
	private ReferenceSetMemberService memberService;

	@Autowired
	private ConceptService conceptService;

	@Autowired
	private BranchService branchService;

	@Autowired
	private BranchMetadataHelper branchMetadataHelper;

	private static final String MAIN = "MAIN";
	private static final PageRequest PAGE = PageRequest.of(0, 10);

	@BeforeEach
	void setup() throws ServiceException, InterruptedException {
		conceptService.deleteAll();
		conceptService.create(new Concept(Concepts.SNOMEDCT_ROOT), MAIN);
		conceptService.create(new Concept(Concepts.ISA)
				.addAxiom(new Relationship(Concepts.ISA, Concepts.SNOMEDCT_ROOT)), MAIN);
		conceptService.create(new Concept(Concepts.CLINICAL_FINDING)
				.addAxiom(new Relationship(Concepts.ISA, Concepts.SNOMEDCT_ROOT)), MAIN);
		conceptService.create(new Concept(Concepts.REFSET_HISTORICAL_ASSOCIATION)
				.addAxiom(new Relationship(Concepts.ISA, Concepts.SNOMEDCT_ROOT)), MAIN);
		conceptService.create(new Concept(Concepts.REFSET_POSSIBLY_EQUIVALENT_TO_ASSOCIATION)
				.addAxiom(new Relationship(Concepts.ISA, Concepts.REFSET_HISTORICAL_ASSOCIATION)), MAIN);
	}

	@Test
	void createFindDeleteMember() {
		assertEquals(0, memberService.findMembers(MAIN, Concepts.HEART_STRUCTURE, PAGE).getTotalElements());

		memberService.createMember(
				MAIN, new ReferenceSetMember(Concepts.CORE_MODULE, Concepts.REFSET_POSSIBLY_EQUIVALENT_TO_ASSOCIATION, Concepts.HEART_STRUCTURE));

		Page<ReferenceSetMember> members = memberService.findMembers(MAIN, Concepts.HEART_STRUCTURE, PAGE);
		assertEquals(1, members.getTotalElements());

		assertEquals(0, memberService.findMembers(MAIN, new MemberSearchRequest().active(true).referenceSet(Concepts.REFSET_HISTORICAL_ASSOCIATION), PAGE).getTotalElements());
		assertEquals(1, memberService.findMembers(MAIN, new MemberSearchRequest().active(true).referenceSet("<<" + Concepts.REFSET_HISTORICAL_ASSOCIATION), PAGE).getTotalElements());

		memberService.deleteMember(MAIN, members.getContent().get(0).getMemberId());

		assertEquals(0, memberService.findMembers(MAIN, Concepts.HEART_STRUCTURE, PAGE).getTotalElements());
	}

	@Test
	void findMemberByOwlExpressionConceptId() {
		memberService.createMember(MAIN,
				new ReferenceSetMember(Concepts.CORE_MODULE, Concepts.OWL_AXIOM_REFERENCE_SET, "90253000")
						.setAdditionalField(ReferenceSetMember.OwlExpressionFields.OWL_EXPRESSION,
								"SubClassOf(" +
										":90253000 " +
										"ObjectIntersectionOf(:20484008 " +
											"ObjectSomeValuesFrom(:609096000 " +
												"ObjectIntersectionOf(ObjectSomeValuesFrom(:116676008 :68245003) ObjectSomeValuesFrom(:363698007 :280369009)))))"));

		assertEquals("Number not in axiom.", 0, findOwlMembers("999").getTotalElements());
		assertEquals("Number in left hand part of axiom.", 1, findOwlMembers("90253000").getTotalElements());
		assertEquals("Number in right hand part of axiom.", 1, findOwlMembers("116676008").getTotalElements());
		assertEquals("Partial number should not match.", 0, findOwlMembers("11667600").getTotalElements());
		assertEquals("Partial number should not match.", 0, findOwlMembers("9096000").getTotalElements());
	}

	@Test
	void findMemberByOwlExpressionGCI() {
		memberService.createMember(MAIN,
				new ReferenceSetMember(Concepts.CORE_MODULE, Concepts.OWL_AXIOM_REFERENCE_SET, "90253000")
						.setAdditionalField(ReferenceSetMember.OwlExpressionFields.OWL_EXPRESSION,
								// Normal class axiom
								"SubClassOf(" +
										// Named concept on left hand side
										":90253000 " +
										// Expression on the right hand side
										"ObjectIntersectionOf(:20484008 " +
											"ObjectSomeValuesFrom(:609096000 " +
												"ObjectIntersectionOf(ObjectSomeValuesFrom(:116676008 :68245003) ObjectSomeValuesFrom(:363698007 :280369009)))))"));

		memberService.createMember(MAIN,
				new ReferenceSetMember(Concepts.CORE_MODULE, Concepts.OWL_AXIOM_REFERENCE_SET, "703264005")
						.setAdditionalField(ReferenceSetMember.OwlExpressionFields.OWL_EXPRESSION,
								// GCI axiom
								"SubClassOf(" +
										// Expression on the left hand side
										"ObjectIntersectionOf(:64859006 " +
											"ObjectSomeValuesFrom(:609096000 " +
												"ObjectSomeValuesFrom(:363698007 :272673000)) ObjectSomeValuesFrom(:609096000 ObjectSomeValuesFrom(:42752001 :64572001))) " +
										// Named concept on right hand side
										":703264005)"));

		assertEquals("Match normal class axiom.", 1, findOwlMembers("116676008", null).getTotalElements());
		assertEquals("Should not match normal class axiom.", 0, findOwlMembers("116676008", true).getTotalElements());
		assertEquals("Match normal class axiom.", 1, findOwlMembers("116676008", false).getTotalElements());

		assertEquals("Match GCI axiom.", 1, findOwlMembers("703264005", null).getTotalElements());
		assertEquals("Match GCI axiom.", 1, findOwlMembers("703264005", true).getTotalElements());
		assertEquals("Should not match GCI axiom.", 0, findOwlMembers("703264005", false).getTotalElements());
	}

	public Page<ReferenceSetMember> findOwlMembers(String owlExpressionConceptId) {
		return findOwlMembers(owlExpressionConceptId, null);
	}

	public Page<ReferenceSetMember> findOwlMembers(String owlExpressionConceptId, Boolean owlExpressionGCI) {
		return memberService.findMembers(
				MAIN,
				new MemberSearchRequest()
						.owlExpressionConceptId(owlExpressionConceptId)
						.owlExpressionGCI(owlExpressionGCI),
				PAGE);
	}

	@Test
	void createFindUpdateMember() {
		assertEquals(0, memberService.findMembers(MAIN, Concepts.HEART_STRUCTURE, PAGE).getTotalElements());

		ReferenceSetMember axiom = new ReferenceSetMember(Concepts.CORE_MODULE, Concepts.OWL_AXIOM_REFERENCE_SET, Concepts.HEART_STRUCTURE);
		axiom.setAdditionalField("owlExpression", "SubClassOf(:80891009 :138875005)");// Junk axiom for test
		axiom = memberService.createMember(MAIN, axiom);

		ReferenceSetMember found = memberService.findMember(MAIN, axiom.getMemberId());
		assertEquals(axiom.getMemberId(), found.getMemberId());
		assertEquals(axiom.getRefsetId(), found.getRefsetId());
		assertEquals(axiom.getAdditionalField("owlExpression"), found.getAdditionalField("owlExpression"));

		ReferenceSetMember updatedMember = new ReferenceSetMember(axiom.getModuleId(), axiom.getRefsetId(), axiom.getReferencedComponentId());
		updatedMember.setAdditionalField("owlExpression", "SubClassOf(:80891009 :100138875005)");
		updatedMember.setMemberId(axiom.getMemberId());
		updatedMember = memberService.updateMember(MAIN, updatedMember);

		ReferenceSetMember result = memberService.findMember(MAIN, updatedMember.getMemberId());
		assertEquals("SubClassOf(:80891009 :100138875005)", result.getAdditionalField("owlExpression"));
		assertEquals(updatedMember.getRefsetId(), result.getRefsetId());
		assertEquals(updatedMember.getMemberId(), result.getMemberId());
	}

	@Test
	void updatePublishedMember() {
		ReferenceSetMember simpleRefset = savePublishedSimpleMember(MAIN);
		ReferenceSetMember saved = memberService.findMember(MAIN, simpleRefset.getMemberId());
		assertEquals("800aa109-431f-4407-a431-6fe65e9db161", saved.getMemberId());
		assertTrue(saved.isReleased());
		assertTrue(saved.isActive());
		assertNotNull(saved.getReleaseHash());
		assertEquals("20170731", saved.getEffectiveTime());

		simpleRefset.setActive(false);
		memberService.updateMember(MAIN, simpleRefset);
		ReferenceSetMember updated = memberService.findMember(MAIN, simpleRefset.getMemberId());
		assertTrue(updated.isReleased());
		assertFalse(updated.isActive());
		assertNotNull(updated.getReleaseHash());
		assertNull(updated.getEffectiveTime());

	}

	private ReferenceSetMember savePublishedSimpleMember(String branch) {
		ReferenceSetMember simpleRefset = new ReferenceSetMember("800aa109-431f-4407-a431-6fe65e9db161", 20170731, true,
				"900000000000207008", "723264001", "731819006");
		simpleRefset.release(20170731);
		try (Commit commit = branchService.openCommit(branch, branchMetadataHelper.getBranchLockMetadata("Releasing reference set member " + simpleRefset.getMemberId()))) {
			simpleRefset.markChanged();
			memberService.doSaveBatchMembers(Arrays.asList(simpleRefset), commit);
			commit.markSuccessful();
		}
		return simpleRefset;
	}

	@Test
	void revertUpdatingPublishedMember() {
		updatePublishedMember();
		ReferenceSetMember simpleRefset = memberService.findMember(MAIN,"800aa109-431f-4407-a431-6fe65e9db161");
		assertEquals(false, simpleRefset.isActive());
		//reverting
		simpleRefset.setActive(true);
		ReferenceSetMember result = memberService.updateMember(MAIN, simpleRefset);
		assertEquals("800aa109-431f-4407-a431-6fe65e9db161", result.getMemberId());
		assertTrue(result.isReleased());
		assertTrue(result.isActive());
		assertNotNull(result.getReleaseHash());
		assertEquals("20170731", result.getEffectiveTime());

	}

	@Test
	void forceDeletePublishedMember() {
		ReferenceSetMember simpleMember = savePublishedSimpleMember(MAIN);
		memberService.deleteMember(MAIN, simpleMember.getMemberId(), true);
		assertNull(memberService.findMember(MAIN, simpleMember.getMemberId()));
	}

	@Test
	void deletePublishedMemberWithoutForce() {
		ReferenceSetMember simpleMember = savePublishedSimpleMember(MAIN);
		String memberId = simpleMember.getMemberId();
		assertThrows(IllegalStateException.class, () -> memberService.deleteMember(MAIN, memberId, false));
	}

	@Test
	void updateUnpublishedInactiveMember() {
		ReferenceSetMember simple = new ReferenceSetMember("900000000000207008", "723264001", "731819006");
		memberService.createMember(MAIN, simple);
		simple.setActive(false);
		assertThrows(IllegalStateException.class, () -> memberService.updateMember(MAIN, simple));
	}

	@Test
	void testBatchCreateUpdate() throws InterruptedException {
		ReferenceSetMember simple = new ReferenceSetMember("900000000000207008", "723264001", "731819006");
		memberService.createMember(MAIN, simple);

		final String batchId = memberService.newCreateUpdateAsyncJob();
		simple.setModuleId(Concepts.MODEL_MODULE);
		ReferenceSetMember newMember = new ReferenceSetMember("900000000000207008", "723264001", Concepts.CLINICAL_FINDING);

		memberService.createUpdateAsync(batchId, MAIN, Lists.newArrayList(simple, newMember), SecurityContextHolder.getContext());

		final AsyncRefsetMemberChangeBatch batch = waitForAsyncCompletion(batchId);
		assertEquals(COMPLETED, batch.getStatus());
		assertEquals(2, batch.getMemberIds().size());
		final List<ReferenceSetMember> members = memberService.findMembers(MAIN, batch.getMemberIds());
		assertEquals(2, members.size());
	}

	private AsyncRefsetMemberChangeBatch waitForAsyncCompletion(String batchId) throws InterruptedException {
		AsyncRefsetMemberChangeBatch batchChange = memberService.getBatchChange(batchId);
		for (int i = 0; batchChange.getStatus() == RUNNING && i < 20; i++) {
			Thread.sleep(1_000);
		}
		if (batchChange.getStatus() == RUNNING) {
			Assertions.fail("Batch job timeout.");
		}
		return batchChange;
	}

	@Test
	void testAggregationsWithActiveAndInactiveMembers() {
		//create an inactive simple reference set member
		ReferenceSetMember saved = savePublishedSimpleMember(MAIN);
		saved.setActive(false);
		memberService.updateMember(MAIN, saved);

		// create an active simple reference set member
		ReferenceSetMember simple = new ReferenceSetMember("900000000000207008", "723264001", "731819006");
		memberService.createMember(MAIN, simple);

		//create an association reference set member
		memberService.createMember(
				MAIN, new ReferenceSetMember(Concepts.CORE_MODULE, Concepts.REFSET_POSSIBLY_EQUIVALENT_TO_ASSOCIATION, Concepts.CLINICAL_FINDING));

		PageWithBucketAggregations<ReferenceSetMember> allResults = memberService.findReferenceSetMembersWithAggregations(MAIN, PageRequest.of(0, 10), new MemberSearchRequest());
		assertNotNull(allResults);
		String key = allResults.getBuckets().keySet().iterator().next();

		assertEquals(3, allResults.getBuckets().get(key).values().size());
		assertEquals(1, allResults.getBuckets().get(key).get(Concepts.REFSET_POSSIBLY_EQUIVALENT_TO_ASSOCIATION).intValue());
		assertEquals(2, allResults.getBuckets().get(key).get("723264001").intValue());
		assertEquals(4, allResults.getBuckets().get(key).get(Concepts.OWL_AXIOM_REFERENCE_SET).intValue());

		PageWithBucketAggregations<ReferenceSetMember> activeResults = memberService.findReferenceSetMembersWithAggregations(MAIN, PageRequest.of(0, 1), new MemberSearchRequest().active(true));
		assertNotNull(activeResults);
		assertEquals(3, activeResults.getBuckets().get(key).values().size());
		assertEquals(1, activeResults.getBuckets().get(key).get(Concepts.REFSET_POSSIBLY_EQUIVALENT_TO_ASSOCIATION).intValue());
		assertEquals(1, activeResults.getBuckets().get(key).get("723264001").intValue());


		PageWithBucketAggregations<ReferenceSetMember> inActiveResults = memberService.findReferenceSetMembersWithAggregations(MAIN, PageRequest.of(0, 1), new MemberSearchRequest().active(false));
		assertNotNull(inActiveResults);
		assertEquals(1, inActiveResults.getBuckets().get(key).size());
		assertEquals(1, inActiveResults.getBuckets().get(key).get("723264001").intValue());
	}

	@Test
	void testAggregationsWithTwoReferenceSets() {
		//create an inactive simple reference set member
		ReferenceSetMember saved = savePublishedSimpleMember(MAIN);
		saved.setActive(false);
		memberService.updateMember(MAIN, saved);

		//create an association reference set member
		memberService.createMember(
				MAIN, new ReferenceSetMember(Concepts.CORE_MODULE, Concepts.REFSET_POSSIBLY_EQUIVALENT_TO_ASSOCIATION, Concepts.CLINICAL_FINDING));

		PageWithBucketAggregations<ReferenceSetMember> allResults = memberService.findReferenceSetMembersWithAggregations(MAIN, PageRequest.of(0, 10), new MemberSearchRequest());
		assertNotNull(allResults);
		assertEquals(3, allResults.getBuckets().get(AGGREGATION_MEMBER_COUNTS_BY_REFERENCE_SET).size());
		String key = allResults.getBuckets().keySet().iterator().next();

		assertEquals(3, allResults.getBuckets().get(key).values().size());
		assertEquals(1, allResults.getBuckets().get(key).get(Concepts.REFSET_POSSIBLY_EQUIVALENT_TO_ASSOCIATION).intValue());
		assertEquals(1, allResults.getBuckets().get(key).get("723264001").intValue());

		PageWithBucketAggregations<ReferenceSetMember> activeResults = memberService.findReferenceSetMembersWithAggregations(MAIN, PageRequest.of(0, 1), new MemberSearchRequest().active(true));
		assertNotNull(activeResults);
		assertEquals(2, activeResults.getBuckets().get(key).values().size());
		assertEquals(1, activeResults.getBuckets().get(key).get(Concepts.REFSET_POSSIBLY_EQUIVALENT_TO_ASSOCIATION).intValue());

		PageWithBucketAggregations<ReferenceSetMember> inActiveResults = memberService.findReferenceSetMembersWithAggregations(MAIN, PageRequest.of(0, 1), new MemberSearchRequest().active(false));
		assertNotNull(inActiveResults);
		assertEquals(1, inActiveResults.getBuckets().get(key).size());
		assertEquals(1, inActiveResults.getBuckets().get(key).get("723264001").intValue());

		assertEquals(1, memberService.findReferenceSetMembersWithAggregations(MAIN, PageRequest.of(0, 10), new MemberSearchRequest().referenceSet(Concepts.REFSET_POSSIBLY_EQUIVALENT_TO_ASSOCIATION))
				.getBuckets().get(AGGREGATION_MEMBER_COUNTS_BY_REFERENCE_SET).size());

	}

	@AfterEach
	void tearDown() throws InterruptedException {
		conceptService.deleteAll();
	}

}
